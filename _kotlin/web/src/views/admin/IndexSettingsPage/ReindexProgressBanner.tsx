"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Button, MessageCard, ProgressBar, Text } from "@opal/components";
import { useReindexProgress } from "@/lib/indexing/hooks";
import ReindexErrorsModal from "./ReindexErrorsModal";

export default function ReindexProgressBanner({
  onCancel,
}: {
  onCancel: () => void;
}) {
  const { data } = useReindexProgress();
  const t = useTranslations("admin.indexSettings.localModels");
  const [errorsOpen, setErrorsOpen] = useState(false);
  const title = data?.mode === "SYNC" ? t("syncing") : t("reindexing");

  return (
    <>
      {errorsOpen && (
        <ReindexErrorsModal onClose={() => setErrorsOpen(false)} />
      )}
      <MessageCard
        variant="pending"
        title={title}
        description={t("boundedDescription")}
        bottomChildren={
          <div className="flex w-full items-center gap-4 px-2 py-1">
            <div className="flex flex-1 flex-col gap-2">
              <Text color="text-03">
                {data
                  ? t("progressSummary", {
                      completed: data.completed,
                      total: data.total,
                      running: data.in_progress,
                      failed: data.failed,
                    })
                  : t("preparing")}
              </Text>
              <ProgressBar
                value={data?.completed ?? 0}
                max={data?.total || 1}
                color="blue"
                aria-label={t("reindexProgress")}
              />
            </div>
            {(data?.failed ?? 0) > 0 && (
              <Button variant="danger" onClick={() => setErrorsOpen(true)}>
                {t("errors")}
              </Button>
            )}
            <Button variant="danger" onClick={onCancel}>
              {t("cancel")}
            </Button>
          </div>
        }
      />
    </>
  );
}
