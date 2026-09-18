"use client";

import { useState } from "react";
import { mutate } from "swr";
import { useTranslations } from "next-intl";
import { Button, Card, Text } from "@opal/components";
import {
  ConfirmationModalLayout,
  PageLoader,
  SettingsLayouts,
  toast,
} from "@opal/layouts";
import { SvgAlertCircle } from "@opal/icons";
import { ADMIN_ROUTES } from "@/lib/admin-routes";
import { SWR_KEYS } from "@/lib/swr-keys";
import {
  useCurrentSearchSettings,
  useLocalEmbeddingModels,
  useSecondarySearchSettings,
} from "@/lib/indexing/hooks";
import {
  cancelReindex,
  startFullReindex,
  startSyncAndSwitch,
} from "@/lib/indexing/svc";
import ReindexProgressBanner from "./ReindexProgressBanner";

const route = ADMIN_ROUTES.INDEX_SETTINGS;

export default function IndexSettingsPage() {
  const t = useTranslations("admin.indexSettings.localModels");
  const { data: models, isLoading: modelsLoading } = useLocalEmbeddingModels();
  const { data: current, isLoading: currentLoading } =
    useCurrentSearchSettings();
  const { data: future } = useSecondarySearchSettings();
  const [busyModel, setBusyModel] = useState<string | null>(null);
  const [confirmModel, setConfirmModel] = useState<string | null>(null);

  async function run(modelName: string, sync: boolean) {
    setBusyModel(modelName);
    try {
      await (sync
        ? startSyncAndSwitch(modelName)
        : startFullReindex(modelName));
      await mutate(SWR_KEYS.secondarySearchSettings);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("requestFailed"));
    } finally {
      setBusyModel(null);
    }
  }

  async function cancel() {
    try {
      await cancelReindex();
      await mutate(SWR_KEYS.secondarySearchSettings);
      await mutate(SWR_KEYS.reindexProgress);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("requestFailed"));
    }
  }

  if (modelsLoading || currentLoading) return <PageLoader />;

  return (
    <SettingsLayouts.Root>
      <SettingsLayouts.Header
        icon={route.icon}
        title={route.title}
        description={t("description")}
        divider
      />
      <SettingsLayouts.Body>
        {future?.reindex_started_at && (
          <ReindexProgressBanner
            onCancel={cancel}
            isCanceling={Boolean(future.cancel_requested_at)}
          />
        )}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {models?.map((model) => {
            const isCurrent = current?.model_name === model.model_name;
            const disabled = !model.available || !!future?.reindex_started_at;
            return (
              <Card
                key={model.model_name}
                border="solid"
                rounding={4}
                padding={4}
              >
                <div className="flex flex-col gap-3">
                  <div>
                    <div className="flex items-start justify-between gap-2">
                      <div className="break-all">
                        <Text font="main-ui-body">{model.model_name}</Text>
                      </div>
                      {isCurrent && (
                        <div className="shrink-0">
                          <Text color="status-success-05">{t("current")}</Text>
                        </div>
                      )}
                    </div>
                    <Text color="text-03">
                      {t("dimensions", { count: model.dimension })}
                    </Text>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {!isCurrent &&
                      model.compatible_past_settings_id !== null && (
                        <Button
                          disabled={disabled || busyModel === model.model_name}
                          onClick={() => run(model.model_name, true)}
                        >
                          {t("syncAndSwitch")}
                        </Button>
                      )}
                    <Button
                      variant="danger"
                      disabled={disabled || busyModel === model.model_name}
                      onClick={() => setConfirmModel(model.model_name)}
                    >
                      {t("fullReindex")}
                    </Button>
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      </SettingsLayouts.Body>
      {confirmModel && (
        <ConfirmationModalLayout
          icon={SvgAlertCircle}
          title={t("confirmFullReindexTitle")}
          onClose={() => setConfirmModel(null)}
          submit={
            <Button
              variant="danger"
              disabled={busyModel === confirmModel}
              onClick={() => {
                const target = confirmModel;
                setConfirmModel(null);
                void run(target, false);
              }}
            >
              {t("fullReindex")}
            </Button>
          }
        >
          <Text color="text-03">
            {t("confirmFullReindexDescription", { model: confirmModel })}
          </Text>
        </ConfirmationModalLayout>
      )}
    </SettingsLayouts.Root>
  );
}
