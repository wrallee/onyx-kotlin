async function post(
  path: string,
  body?: Record<string, string>
): Promise<void> {
  const response = await fetch(path, {
    method: "POST",
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail ?? "Request failed");
  }
}

export const startFullReindex = (modelName: string) =>
  post("/api/search-settings/reindex/full", { model_name: modelName });

export const startSyncAndSwitch = (modelName: string) =>
  post("/api/search-settings/reindex/sync-and-switch", {
    model_name: modelName,
  });

export const cancelReindex = () =>
  post("/api/search-settings/cancel-new-embedding");

export const retryReindex = (pairId: number) =>
  post(`/api/search-settings/reindex/${pairId}/retry`);

export const retryAllReindex = () =>
  post("/api/search-settings/reindex/retry-all");
