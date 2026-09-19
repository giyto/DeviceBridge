import type { FileTransferUiState } from "./fileTransferController";
import type { SessionUiState } from "./sessionController";
import type { TextTransferUiState } from "./textTransferController";

export type UiPresentationState =
  | "loading"
  | "empty"
  | "ready"
  | "disabled"
  | "offline"
  | "error"
  | "cancelled";

export function sessionPresentationState(state: SessionUiState): UiPresentationState {
  switch (state.kind) {
    case "checking":
    case "submitting":
    case "awaiting":
    case "reconnecting":
      return "loading";
    case "uncertain":
      return state.checking ? "loading" : "error";
    case "ready":
    case "connected":
      return "ready";
    case "offline":
      return "offline";
    case "needsUserAction":
    case "blocked":
    case "expired":
    case "denied":
    case "sessionLost":
      return "error";
  }
}

export function textPresentationState(state: TextTransferUiState): UiPresentationState {
  if (state.kind === "inactive") return "disabled";
  if (state.error !== undefined) return "error";
  if (!state.connectionAvailable) return "offline";
  if (state.sending) return "loading";
  if (state.items.length === 0 && state.draft.trim().length === 0) return "empty";
  return "ready";
}

export function filePresentationState(state: FileTransferUiState): UiPresentationState {
  if (state.kind === "inactive") return "disabled";
  if (state.error !== undefined) return "error";
  if (!state.connectionAvailable) return "offline";
  if (state.preparing) return "loading";
  if (state.selection.length === 0 && state.transfers.length === 0) return "empty";
  if (
    state.selection.length === 0 &&
    state.transfers.length > 0 &&
    state.transfers.every((item) => item.status === "CANCELLED")
  ) return "cancelled";
  return "ready";
}

export function transferItemPresentationState(
  status: "QUEUED" | "CONNECTING" | "TRANSFERRING" | "VERIFYING" |
    "COMPLETED" | "CANCELLED" | "FAILED",
): UiPresentationState {
  switch (status) {
    case "QUEUED":
    case "CONNECTING":
    case "TRANSFERRING":
    case "VERIFYING":
      return "loading";
    case "COMPLETED":
      return "ready";
    case "CANCELLED":
      return "cancelled";
    case "FAILED":
      return "error";
  }
}

export function textItemPresentationState(
  status: "PENDING" | "SENDING" | "UNCERTAIN" | "DELIVERED" | "FAILED",
): UiPresentationState {
  switch (status) {
    case "PENDING":
    case "SENDING":
      return "loading";
    case "DELIVERED":
      return "ready";
    case "UNCERTAIN":
    case "FAILED":
      return "error";
  }
}
