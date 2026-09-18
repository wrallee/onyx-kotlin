"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Button, MessageCard, ProgressBar, Text } from "@opal/components";
import { useReindexProgress } from "@/lib/indexing/hooks";
import ReindexErrorsModal from "./ReindexErrorsModal";

export default function ReindexProgressBanner({
  onCancel,
  isCanceling = false,
}: {
  onCancel: () => void;
  isCanceling?: boolean;
}) {
  const { data } = useReindexProgress();
  const t = useTranslations("admin.indexSettings.localModels");
  const [errorsOpen, setErrorsOpen] = useState(false);
  const title = isCanceling
    ? t("canceling")
    : data?.mode === "SYNC"
      ? t("syncing")
      : t("reindexing");

  const completedDocs = data?.completed_documents ?? data?.completed ?? 0;
  const totalDocs = data?.total_documents ?? data?.total ?? 0;
  const runningConnectors =
    data?.in_progress_connectors ?? data?.in_progress ?? 0;
  const failedConnectors = data?.failed_connectors ?? data?.failed ?? 0;

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
                      completed: completedDocs,
                      total: totalDocs,
                      running: runningConnectors,
                      failed: failedConnectors,
                    })
                  : t("preparing")}
              </Text>
              <ProgressBar
                value={completedDocs}
                max={Math.max(totalDocs, 1)}
                color="blue"
                aria-label={t("reindexProgress")}
              />
            </div>
            {(data?.failed ?? 0) > 0 && (
              <Button variant="danger" onClick={() => setErrorsOpen(true)}>
                {t("errors")}
              </Button>
            )}
            <Button variant="danger" disabled={isCanceling} onClick={onCancel}>
              {isCanceling ? t("canceling") : t("cancel")}
            </Button>
          </div>
        }
      />
    </>
  );
}
