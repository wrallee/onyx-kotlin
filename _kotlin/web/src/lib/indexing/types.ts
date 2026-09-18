export interface SearchSettings {
  id: number;
  model_name: string;
  index_name: string;
  status: "PRESENT" | "FUTURE" | "PAST";
  reindex_started_at: string | null;
  cancel_requested_at: string | null;
  cutover_at: string | null;
}

export interface LocalEmbeddingModel {
  model_name: string;
  display_name: string;
  dimension: number;
  available: boolean;
  status: string;
  compatible_past_settings_id: number | null;
}

export interface ReindexProgress {
  mode: "FULL" | "SYNC";
  total: number;
  waiting: number;
  in_progress: number;
  completed: number;
  failed: number;
  total_connectors?: number;
  completed_connectors?: number;
  in_progress_connectors?: number;
  failed_connectors?: number;
  total_documents?: number;
  completed_documents?: number;
}

export interface ReindexErrorRow {
  cc_pair_id: number;
  name: string;
  status: string;
  error_message: string | null;
}
