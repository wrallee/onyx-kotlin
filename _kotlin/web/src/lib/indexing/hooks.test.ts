/**
 * @jest-environment jsdom
 */
import { renderHook } from "@testing-library/react";
import useSWR, { mutate } from "swr";
import { useSecondarySearchSettings } from "@/lib/indexing/hooks";
import { SWR_KEYS } from "@/lib/swr-keys";

jest.mock("swr", () => ({
  __esModule: true,
  default: jest.fn(),
  mutate: jest.fn(),
}));

const mockUseSWR = useSWR as jest.MockedFunction<typeof useSWR>;
const mockMutate = mutate as jest.MockedFunction<typeof mutate>;

test("refreshes model settings after a reindex disappears", () => {
  mockUseSWR.mockReturnValue({} as ReturnType<typeof useSWR>);
  renderHook(() => useSecondarySearchSettings());
  const options = mockUseSWR.mock.calls[0][2] as {
    onSuccess: (value: null) => void;
  };

  options.onSuccess(null);

  expect(mockMutate).toHaveBeenCalledWith(SWR_KEYS.currentSearchSettings);
  expect(mockMutate).toHaveBeenCalledWith(SWR_KEYS.localEmbeddingModels);
});
