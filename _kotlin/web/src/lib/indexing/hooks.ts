"use client";

import useSWR, { mutate } from "swr";
import { errorHandlingFetcher } from "@/lib/fetcher";
import { SWR_KEYS } from "@/lib/swr-keys";
import type {
  LocalEmbeddingModel,
  ReindexErrorRow,
  ReindexProgress,
  SearchSettings,
} from "@/lib/indexing/types";

export function secondaryRefreshInterval<T>(value: T | null | undefined) {
  return value ? 5000 : 60000;
}

export const useCurrentSearchSettings = () =>
  useSWR<SearchSettings>(SWR_KEYS.currentSearchSettings, errorHandlingFetcher);

export const useSecondarySearchSettings = () =>
  useSWR<SearchSettings | null>(
    SWR_KEYS.secondarySearchSettings,
    errorHandlingFetcher,
    {
      refreshInterval: secondaryRefreshInterval,
      onSuccess: (secondary) => {
        if (!secondary) {
          void mutate(SWR_KEYS.currentSearchSettings);
          void mutate(SWR_KEYS.localEmbeddingModels);
        }
      },
    }
  );

export const useLocalEmbeddingModels = () =>
  useSWR<LocalEmbeddingModel[]>(
    SWR_KEYS.localEmbeddingModels,
    errorHandlingFetcher
  );

export const useReindexProgress = () =>
  useSWR<ReindexProgress | null>(
    SWR_KEYS.reindexProgress,
    errorHandlingFetcher,
    {
      refreshInterval: 5000,
    }
  );

export const useReindexErrors = (enabled: boolean) =>
  useSWR<ReindexErrorRow[]>(
    enabled ? SWR_KEYS.reindexErrors : null,
    errorHandlingFetcher,
    { refreshInterval: 5000 }
  );
