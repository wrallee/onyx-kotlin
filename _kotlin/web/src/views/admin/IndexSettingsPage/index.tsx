"use client";

import { useState } from "react";
import { mutate } from "swr";
import { useTranslations } from "next-intl";
import { Button, Card, Text } from "@opal/components";
import { PageLoader, SettingsLayouts, toast } from "@opal/layouts";
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
          <ReindexProgressBanner onCancel={cancel} />
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
                    <div className="flex items-center gap-2">
                      <Text font="main-ui-body">{model.display_name}</Text>
                      {isCurrent && (
                        <Text color="status-success-05">{t("current")}</Text>
                      )}
                    </div>
                    <Text color="text-03">
                      {t("dimensions", { count: model.dimension })}
                    </Text>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {model.compatible_past_settings_id !== null && (
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
                      onClick={() => run(model.model_name, false)}
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
    </SettingsLayouts.Root>
  );
}
