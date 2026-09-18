/**
 * @jest-environment jsdom
 */

import { render, screen, setupUser } from "@tests/setup/test-utils";
import useSWR from "swr";
import IndexSettingsPage from "@/views/admin/IndexSettingsPage";
import { SWR_KEYS } from "@/lib/swr-keys";
import * as indexingSvc from "@/lib/indexing/svc";
import type {
  LocalEmbeddingModel,
  ReindexProgress,
  SearchSettings,
} from "@/lib/indexing/types";

jest.mock("swr", () => ({
  __esModule: true,
  ...jest.requireActual("swr"),
  default: jest.fn(),
  mutate: jest.fn(),
}));

jest.mock("@/lib/indexing/svc", () => ({
  startFullReindex: jest.fn(),
  startSyncAndSwitch: jest.fn(),
  cancelReindex: jest.fn(),
}));

jest.mock("next/navigation", () => ({
  useRouter: () => ({ push: jest.fn(), back: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/admin/indexing",
}));

const mockUseSWR = useSWR as jest.MockedFunction<typeof useSWR>;
const mockStartFullReindex =
  indexingSvc.startFullReindex as jest.MockedFunction<
    typeof indexingSvc.startFullReindex
  >;
const mockStartSyncAndSwitch =
  indexingSvc.startSyncAndSwitch as jest.MockedFunction<
    typeof indexingSvc.startSyncAndSwitch
  >;
const mockCancelReindex = indexingSvc.cancelReindex as jest.MockedFunction<
  typeof indexingSvc.cancelReindex
>;

const CURRENT_SETTINGS: SearchSettings = {
  id: 1,
  model_name: "ibm-granite/granite-embedding-311m-multilingual-r2",
  index_name: "onyx-granite",
  status: "PRESENT",
  reindex_started_at: null,
  cancel_requested_at: null,
  cutover_at: null,
};

const MODELS: LocalEmbeddingModel[] = [
  {
    model_name: "ibm-granite/granite-embedding-311m-multilingual-r2",
    display_name: "Granite",
    dimension: 768,
    available: true,
    status: "READY",
    compatible_past_settings_id: null,
  },
  {
    model_name: "microsoft/harrier-oss-v1-0.6b",
    display_name: "Harrier",
    dimension: 1024,
    available: true,
    status: "READY",
    compatible_past_settings_id: 2,
  },
  {
    model_name: "nomic-ai/nomic-embed-text-v1.5",
    display_name: "Nomic",
    dimension: 768,
    available: false,
    status: "UNAVAILABLE",
    compatible_past_settings_id: null,
  },
];

function setupSwr(overrides?: {
  current?: SearchSettings | null;
  secondary?: SearchSettings | null;
  models?: LocalEmbeddingModel[];
  progress?: ReindexProgress | null;
}) {
  mockUseSWR.mockImplementation((key: unknown) => {
    if (key === SWR_KEYS.currentSearchSettings) {
      return {
        data: overrides?.current ?? CURRENT_SETTINGS,
        isLoading: false,
      } as any;
    }
    if (key === SWR_KEYS.secondarySearchSettings) {
      return { data: overrides?.secondary ?? null, isLoading: false } as any;
    }
    if (key === SWR_KEYS.localEmbeddingModels) {
      return { data: overrides?.models ?? MODELS, isLoading: false } as any;
    }
    if (key === SWR_KEYS.reindexProgress) {
      return { data: overrides?.progress ?? null, isLoading: false } as any;
    }
    return { data: null, isLoading: false } as any;
  });
}

describe("IndexSettingsPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test("displays full model names and current badge for current model", () => {
    setupSwr();
    render(<IndexSettingsPage />);

    expect(
      screen.getByText("ibm-granite/granite-embedding-311m-multilingual-r2")
    ).toBeInTheDocument();
    expect(
      screen.getByText("microsoft/harrier-oss-v1-0.6b")
    ).toBeInTheDocument();
    expect(
      screen.getByText("nomic-ai/nomic-embed-text-v1.5")
    ).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
  });

  test("sync & switch is hidden for current model and shown for non-current model with past settings", () => {
    setupSwr();
    render(<IndexSettingsPage />);

    const buttons = screen.getAllByRole("button");
    const syncButtons = buttons.filter((b) =>
      b.textContent?.includes("Sync & Switch")
    );
    expect(syncButtons).toHaveLength(1);

    const fullReindexButtons = buttons.filter((b) =>
      b.textContent?.includes("Full Reindex")
    );
    expect(fullReindexButtons).toHaveLength(3);
    // nomic is unavailable, so its full reindex button is disabled
    expect(fullReindexButtons[2]).toBeDisabled();
  });

  test("prompts confirmation modal before starting full reindex", async () => {
    setupSwr();
    const user = setupUser();
    render(<IndexSettingsPage />);

    const buttons = screen.getAllByRole("button");
    const harrierFullReindex = buttons.find(
      (b) =>
        b.textContent?.includes("Full Reindex") && !b.hasAttribute("disabled")
    );
    expect(harrierFullReindex).toBeDefined();

    await user.click(harrierFullReindex!);

    expect(mockStartFullReindex).not.toHaveBeenCalled();

    expect(screen.getByText("Confirm Full Reindex")).toBeInTheDocument();

    const modalConfirmButton = screen
      .getAllByRole("button")
      .find(
        (b) => b.textContent === "Full Reindex" && b.closest("[role='dialog']")
      );
    expect(modalConfirmButton).toBeDefined();

    await user.click(modalConfirmButton!);

    expect(mockStartFullReindex).toHaveBeenCalledWith(
      "ibm-granite/granite-embedding-311m-multilingual-r2"
    );
  });

  test("displays document-based progress and canceling state when cancel is requested", () => {
    setupSwr({
      secondary: {
        id: 2,
        model_name: "microsoft/harrier-oss-v1-0.6b",
        index_name: "onyx-harrier",
        status: "FUTURE",
        reindex_started_at: "2026-09-18T01:00:00Z",
        cancel_requested_at: "2026-09-18T01:30:00Z",
        cutover_at: null,
      },
      progress: {
        mode: "FULL",
        total: 500,
        waiting: 0,
        in_progress: 1,
        completed: 150,
        failed: 0,
        total_documents: 500,
        completed_documents: 150,
        total_connectors: 2,
        completed_connectors: 0,
        in_progress_connectors: 1,
        failed_connectors: 0,
      },
    });

    render(<IndexSettingsPage />);

    expect(screen.getAllByText("Canceling…")).toHaveLength(2);
    expect(
      screen.getByText(
        "150/500 documents complete · 1 connectors running · 0 failed"
      )
    ).toBeInTheDocument();

    const cancelBtn = screen.getByRole("button", { name: "Canceling…" });
    expect(cancelBtn).toBeDisabled();
  });
});
