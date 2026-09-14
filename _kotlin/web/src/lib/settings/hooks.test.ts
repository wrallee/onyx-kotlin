/** @jest-environment jsdom */
import { expect, test } from "@jest/globals";
import { renderHook } from "@testing-library/react";

import { useSettings } from "@/lib/settings/hooks";

jest.mock("swr", () => ({
  __esModule: true,
  default: jest.fn(() => ({
    data: undefined,
    error: undefined,
    isLoading: false,
  })),
}));

test("provides the seven-day pruning default to connector creation", () => {
  const { result } = renderHook(() => useSettings());

  expect(result.current.default_pruning_freq).toBe(7 * 24 * 60 * 60);
});
