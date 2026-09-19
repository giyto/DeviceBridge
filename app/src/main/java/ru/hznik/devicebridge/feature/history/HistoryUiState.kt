package ru.hznik.devicebridge.feature.history

import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus

enum class HistoryLoadState {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
}

data class HistoryUiState(
    val loadState: HistoryLoadState = HistoryLoadState.LOADING,
    val records: List<HistoryRecord> = emptyList(),
    val filter: HistoryFilter = HistoryFilter(),
    val selectedRecord: HistoryRecord? = null,
    val pendingDeleteId: HistoryRecordId? = null,
    val clearConfirmationVisible: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface HistoryAction {
    data class ToggleDirection(val direction: HistoryDirection) : HistoryAction
    data class ToggleKind(val kind: HistoryKind) : HistoryAction
    data class ToggleStatus(val status: HistoryStatus) : HistoryAction
    data object ResetFilters : HistoryAction
    data class OpenDetails(val recordId: HistoryRecordId) : HistoryAction
    data object CloseDetails : HistoryAction
    data class RequestDelete(val recordId: HistoryRecordId) : HistoryAction
    data object CancelDelete : HistoryAction
    data object ConfirmDelete : HistoryAction
    data object RequestClear : HistoryAction
    data object CancelClear : HistoryAction
    data object ConfirmClear : HistoryAction
    data object DismissError : HistoryAction
    data object RetryLoad : HistoryAction
}
