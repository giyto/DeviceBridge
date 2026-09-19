package ru.hznik.devicebridge.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.usecase.ClearHistoryUseCase
import ru.hznik.devicebridge.domain.usecase.DeleteHistoryRecordUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveHistoryUseCase

private const val FILTER_DIRECTIONS_KEY = "history_filter_directions"
private const val FILTER_KINDS_KEY = "history_filter_kinds"
private const val FILTER_STATUSES_KEY = "history_filter_statuses"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val observeHistory: ObserveHistoryUseCase,
    private val deleteHistoryRecord: DeleteHistoryRecordUseCase,
    private val clearHistory: ClearHistoryUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private sealed interface LoadResult {
        data object Loading : LoadResult
        data class Loaded(val records: List<HistoryRecord>) : LoadResult
        data object Failed : LoadResult
    }

    private val filter = MutableStateFlow(savedStateHandle.restoreHistoryFilter())
    private val reloadRevision = MutableStateFlow(0)
    private val mutableUiState = MutableStateFlow(
        HistoryUiState(filter = filter.value),
    )
    val uiState: StateFlow<HistoryUiState> = mutableUiState

    init {
        viewModelScope.launch {
            combine(filter, reloadRevision) { currentFilter, _ -> currentFilter }
                .flatMapLatest { currentFilter ->
                    observeHistory(currentFilter)
                        .map<List<HistoryRecord>, LoadResult>(LoadResult::Loaded)
                        .onStart { emit(LoadResult.Loading) }
                        .catch { emit(LoadResult.Failed) }
                }
                .collect(::applyLoadResult)
        }
    }

    fun onAction(action: HistoryAction) {
        when (action) {
            is HistoryAction.ToggleDirection ->
                updateFilter(filter.value.copy(directions = filter.value.directions.toggle(action.direction)))
            is HistoryAction.ToggleKind ->
                updateFilter(filter.value.copy(kinds = filter.value.kinds.toggle(action.kind)))
            is HistoryAction.ToggleStatus ->
                updateFilter(filter.value.copy(statuses = filter.value.statuses.toggle(action.status)))
            HistoryAction.ResetFilters -> updateFilter(HistoryFilter())

            is HistoryAction.OpenDetails -> mutableUiState.update { state ->
                state.copy(
                    selectedRecord = state.records.firstOrNull { it.id == action.recordId },
                )
            }
            HistoryAction.CloseDetails ->
                mutableUiState.update { it.copy(selectedRecord = null) }
            is HistoryAction.RequestDelete ->
                mutableUiState.update { it.copy(pendingDeleteId = action.recordId) }
            HistoryAction.CancelDelete ->
                mutableUiState.update { it.copy(pendingDeleteId = null) }
            HistoryAction.ConfirmDelete -> deletePendingRecord()
            HistoryAction.RequestClear ->
                mutableUiState.update { it.copy(clearConfirmationVisible = true) }
            HistoryAction.CancelClear ->
                mutableUiState.update { it.copy(clearConfirmationVisible = false) }
            HistoryAction.ConfirmClear -> clearAllRecords()
            HistoryAction.DismissError ->
                mutableUiState.update { it.copy(errorMessage = null) }
            HistoryAction.RetryLoad ->
                reloadRevision.update(Int::inc)
        }
    }

    private fun applyLoadResult(result: LoadResult) {
        mutableUiState.update { current ->
            when (result) {
                LoadResult.Loading -> current.copy(
                    loadState = HistoryLoadState.LOADING,
                    filter = filter.value,
                    errorMessage = null,
                )
                is LoadResult.Loaded -> current.copy(
                    loadState = if (result.records.isEmpty()) {
                        HistoryLoadState.EMPTY
                    } else {
                        HistoryLoadState.CONTENT
                    },
                    records = result.records,
                    filter = filter.value,
                    selectedRecord = current.selectedRecord?.let { selected ->
                        result.records.firstOrNull { it.id == selected.id }
                    },
                    errorMessage = null,
                )
                LoadResult.Failed -> current.copy(
                    loadState = HistoryLoadState.ERROR,
                    records = emptyList(),
                    filter = filter.value,
                    selectedRecord = null,
                    errorMessage = "Не удалось прочитать локальную историю.",
                )
            }
        }
    }

    private fun updateFilter(value: HistoryFilter) {
        filter.value = value
        savedStateHandle[FILTER_DIRECTIONS_KEY] = value.directions.map(Enum<*>::name)
        savedStateHandle[FILTER_KINDS_KEY] = value.kinds.map(Enum<*>::name)
        savedStateHandle[FILTER_STATUSES_KEY] = value.statuses.map(Enum<*>::name)
    }

    private fun deletePendingRecord() {
        val recordId = mutableUiState.value.pendingDeleteId ?: return
        mutableUiState.update { it.copy(isMutating = true, errorMessage = null) }
        viewModelScope.launch {
            val deleted = runCatching { deleteHistoryRecord(recordId) }.getOrDefault(false)
            mutableUiState.update { state ->
                if (deleted) {
                    state.copy(
                        pendingDeleteId = null,
                        selectedRecord = state.selectedRecord?.takeUnless { it.id == recordId },
                        isMutating = false,
                    )
                } else {
                    state.copy(
                        isMutating = false,
                        errorMessage = "Не удалось удалить запись.",
                    )
                }
            }
        }
    }

    private fun clearAllRecords() {
        mutableUiState.update { it.copy(isMutating = true, errorMessage = null) }
        viewModelScope.launch {
            val result = runCatching { clearHistory() }
            mutableUiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        clearConfirmationVisible = false,
                        selectedRecord = null,
                        isMutating = false,
                    )
                } else {
                    state.copy(
                        isMutating = false,
                        errorMessage = "Не удалось очистить историю.",
                    )
                }
            }
        }
    }
}

private fun SavedStateHandle.restoreHistoryFilter(): HistoryFilter = HistoryFilter(
    directions = enumSet(get<List<String>>(FILTER_DIRECTIONS_KEY), HistoryDirection.entries),
    kinds = enumSet(get<List<String>>(FILTER_KINDS_KEY), HistoryKind.entries),
    statuses = enumSet(get<List<String>>(FILTER_STATUSES_KEY), HistoryStatus.entries),
)

private fun <T : Enum<T>> enumSet(
    stored: List<String>?,
    values: List<T>,
): Set<T> = stored.orEmpty().mapNotNull { name ->
    values.firstOrNull { it.name == name }
}.toSet()

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value
