"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { mutate } from "swr";
import { Button, Modal, Text } from "@opal/components";
import { SWR_KEYS } from "@/lib/swr-keys";
import { useReindexErrors } from "@/lib/indexing/hooks";
import { retryAllReindex, retryReindex } from "@/lib/indexing/svc";

export default function ReindexErrorsModal({
  onClose,
}: {
  onClose: () => void;
}) {
  const { data: rows } = useReindexErrors(true);
  const t = useTranslations("admin.indexSettings.localModels");
  const [busy, setBusy] = useState(false);

  async function retry(action: () => Promise<void>) {
    setBusy(true);
    try {
      await action();
      await Promise.all([
        mutate(SWR_KEYS.reindexErrors),
        mutate(SWR_KEYS.reindexProgress),
      ]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onOpenChange={onClose}>
      <Modal.Content width="lg" height="sm">
        <Modal.Header title={t("errorsTitle")} onClose={onClose} />
        <Modal.Body>
          <div className="flex w-full flex-col gap-3">
            <div className="flex justify-end gap-2">
              <Button
                variant="danger"
                disabled={busy || !rows?.length}
                onClick={() => retry(retryAllReindex)}
              >
                {t("retryAll")}
              </Button>
              <Button
                onClick={() => window.open("/admin/indexing/status", "_self")}
              >
                {t("indexingStatus")}
              </Button>
            </div>
            {rows?.map((row) => (
              <div
                key={row.cc_pair_id}
                className="flex items-center justify-between gap-4 border-b py-2"
              >
                <div>
                  <Text>{row.name}</Text>
                  <Text color="text-03">{row.error_message ?? row.status}</Text>
                </div>
                <Button
                  disabled={busy}
                  onClick={() => retry(() => retryReindex(row.cc_pair_id))}
                >
                  {t("retry")}
                </Button>
              </div>
            ))}
          </div>
        </Modal.Body>
      </Modal.Content>
    </Modal>
  );
}
