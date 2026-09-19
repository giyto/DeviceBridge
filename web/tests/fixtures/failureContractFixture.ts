import type { FailureSeverity, RecoveryActionId } from "../../src/failureCatalog";

export interface FailureContractFixture {
  readonly severity: FailureSeverity;
  readonly actions: readonly RecoveryActionId[];
}

export const failureContractFixture = {
  unknown_error: contract("recoverable", "retry"),
  local_network_permission_denied: contract("recoverable", "request_permission"),
  local_network_permission_revoked: contract("recoverable", "open_settings"),
  no_lan_network: contract("recoverable", "connect_to_local_network"),
  ambiguous_lan_network: contract("recoverable", "connect_to_local_network"),
  network_lost: contract("recoverable", "connect_to_local_network"),
  address_changed: contract("recoverable", "start_server"),
  foreground_start_not_allowed: contract("recoverable", "retry"),
  server_start_failed: contract("recoverable", "retry"),
  server_stop_timeout: contract("recoverable", "retry"),
  session_capacity_reached: contract("recoverable", "manage_sessions"),
  server_generation_closed: contract("terminal", "start_server"),
  pairing_request_expired: contract("terminal", "pair_again"),
  pairing_request_not_found: contract("terminal", "pair_again"),
  text_connection_lost: contract("recoverable", "retry"),
  session_closed: contract("terminal", "select_session"),
  protocol_version_unsupported: contract("terminal", "update_client"),
  empty_content: contract("terminal", "edit_content"),
  content_too_large: contract("terminal", "edit_content"),
  message_conflict: contract("terminal", "start_new_operation"),
  message_not_found: contract("terminal", "start_new_operation"),
  file_checksum_mismatch: contract("terminal", "select_file"),
  file_stream_failed: contract("recoverable", "retry"),
  file_storage_unavailable: contract("recoverable", "select_destination"),
  file_insufficient_space: contract("recoverable", "select_destination"),
  file_source_unavailable: contract("terminal", "select_file"),
  file_capacity_reached: contract("recoverable", "reduce_selection"),
  invalid_transfer_id: contract("terminal", "start_new_operation"),
  invalid_file_name: contract("terminal", "select_file"),
  invalid_file_size: contract("terminal", "select_file"),
  file_too_large: contract("terminal", "reduce_selection"),
  invalid_checksum: contract("terminal", "select_file"),
  file_size_mismatch: contract("terminal", "select_file"),
  history_write_failed: contract("recoverable", "retry"),
  invalid_device_name: contract("terminal", "edit_setting"),
  invalid_retention_days: contract("terminal", "edit_setting"),
  invalid_file_limit: contract("terminal", "edit_setting"),
  invalid_payload: contract("terminal", "start_new_operation"),
  invalid_pairing_code: contract("recoverable", "pair_again"),
  invalid_trusted_credential: contract("terminal", "pair_again"),
  pairing_denied: contract("terminal", "pair_again"),
  rate_limited: contract("recoverable", "retry"),
  session_unauthorized: contract("terminal", "pair_again"),
  file_not_approved: contract("recoverable", "select_destination"),
  transfer_cancelled: contract("terminal", "start_new_operation"),
} as const;

function contract(
  severity: FailureSeverity,
  ...actions: readonly RecoveryActionId[]
): FailureContractFixture {
  return { severity, actions };
}
