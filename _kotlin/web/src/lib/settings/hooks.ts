"use client";

import useSWR from "swr";
import { errorHandlingFetcher } from "@/lib/fetcher";
import { SWR_KEYS } from "@/lib/swr-keys";
import type { AppSettings, Settings } from "@/lib/settings/types";
import {
  ApplicationStatus,
  QueryHistoryType,
  Tier,
} from "@/lib/settings/types";

const DEFAULT_SETTINGS: Settings = {
  auto_scroll: true,
  application_status: ApplicationStatus.ACTIVE,
  gpu_enabled: false,
  maximum_chat_retention_days: null,
  notifications: [],
  needs_reindexing: false,
  anonymous_user_enabled: false,
  invite_only_enabled: false,
  deep_research_enabled: false,
  multi_model_chat_enabled: false,
  temperature_override_enabled: false,
  reasoning_override_enabled: false,
  query_history_type: QueryHistoryType.DISABLED,
  vector_db_enabled: true,
  show_extra_connectors: false,
  onyx_craft_available: false,
  hooks_enabled: false,
  tier: Tier.COMMUNITY,
  default_pruning_freq: 7 * 24 * 60 * 60,
};

/** Fetch the Kotlin backend's feature flags without probing Enterprise settings. */
export function useSettings(): AppSettings {
  const { data, error, isLoading } = useSWR<Settings>(
    SWR_KEYS.settings,
    errorHandlingFetcher,
    {
      revalidateOnFocus: false,
      revalidateOnReconnect: false,
      revalidateIfStale: false,
      dedupingInterval: 30_000,
    }
  );
  const settings = data ?? DEFAULT_SETTINGS;

  return {
    ...settings,
    enterprise: null,
    appName: "Onyx",
    logoUrl: null,
    vectorDbEnabled:
      !isLoading && !error && settings.vector_db_enabled !== false,
    isLoading,
    error,
  };
}

export function useIsSearchModeAvailable(): boolean {
  return false;
}
