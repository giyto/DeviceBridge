package ru.hznik.devicebridge.feature.history

import androidx.lifecycle.SavedStateHandle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.hznik.devicebridge.domain.history.HistoryDirection
import ru.hznik.devicebridge.domain.history.HistoryFilter
import ru.hznik.devicebridge.domain.history.HistoryInsertResult
import ru.hznik.devicebridge.domain.history.HistoryKind
import ru.hznik.devicebridge.domain.history.HistoryOperationId
import ru.hznik.devicebridge.domain.history.HistoryRecord
import ru.hznik.devicebridge.domain.history.HistoryRecordId
import ru.hznik.devicebridge.domain.history.HistoryStatus
import ru.hznik.devicebridge.domain.repository.HistoryRepository
import ru.hznik.devicebridge.domain.usecase.ClearHistoryUseCase
import ru.hznik.devicebridge.domain.usecase.DeleteHistoryRecordUseCase
import ru.hznik.devicebridge.domain.usecase.ObserveHistoryUseCase

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadingBecomesEmptyThenContent() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val viewModel = viewModel(repository)
        assertEquals(HistoryLoadState.LOADING, viewModel.uiState.value.loadState)

        runCurrent()
        assertEquals(HistoryLoadState.EMPTY, viewModel.uiState.value.loadState)

        repository.records.value = listOf(record())
        runCurrent()
        assertEquals(HistoryLoadState.CONTENT, viewModel.uiState.value.loadState)
        assertEquals("record-1", viewModel.uiState.value.records.single().id.value)
    }

    @Test
    fun filtersAreCombinedAndSaved() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val savedState = SavedStateHandle()
        val viewModel = viewModel(repository, savedState)
        runCurrent()

        viewModel.onAction(HistoryAction.ToggleKind(HistoryKind.FILE))
        viewModel.onAction(
            HistoryAction.ToggleDirection(HistoryDirection.BROWSER_TO_ANDROID),
        )
        viewModel.onAction(HistoryAction.ToggleStatus(HistoryStatus.COMPLETED))
        runCurrent()

        assertEquals(setOf(HistoryKind.FILE), repository.lastFilter.kinds)
        assertEquals(
            setOf(HistoryDirection.BROWSER_TO_ANDROID),
            repository.lastFilter.directions,
        )
        assertEquals(setOf(HistoryStatus.COMPLETED), repository.lastFilter.statuses)
        assertEquals(listOf("FILE"), savedState.get<List<String>>("history_filter_kinds"))
    }


    @Test
    fun resetFiltersClearsAllSelectionsAndSavedState() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val savedState = SavedStateHandle()
        val viewModel = viewModel(repository, savedState)
        runCurrent()

        viewModel.onAction(HistoryAction.ToggleKind(HistoryKind.FILE))
        viewModel.onAction(HistoryAction.ToggleDirection(HistoryDirection.BROWSER_TO_ANDROID))
        viewModel.onAction(HistoryAction.ToggleStatus(HistoryStatus.COMPLETED))
        viewModel.onAction(HistoryAction.ResetFilters)
        runCurrent()

        assertEquals(HistoryFilter(), repository.lastFilter)
        assertEquals(emptyList<String>(), savedState.get<List<String>>("history_filter_kinds"))
        assertEquals(emptyList<String>(), savedState.get<List<String>>("history_filter_directions"))
        assertEquals(emptyList<String>(), savedState.get<List<String>>("history_filter_statuses"))
    }
    @Test
    fun repositoryFailureBecomesRecoverableErrorState() = runTest(dispatcher) {
        val repository = FakeHistoryRepository(failReads = true)
        val viewModel = viewModel(repository)

        runCurrent()

        assertEquals(HistoryLoadState.ERROR, viewModel.uiState.value.loadState)
        assertTrue(requireNotNull(viewModel.uiState.value.errorMessage).isNotBlank())

        repository.failReads = false
        repository.records.value = listOf(record())
        viewModel.onAction(HistoryAction.RetryLoad)
        assertEquals(HistoryLoadState.ERROR, viewModel.uiState.value.loadState)

        runCurrent()

        assertEquals(HistoryLoadState.CONTENT, viewModel.uiState.value.loadState)
        assertEquals(listOf("record-1"), viewModel.uiState.value.records.map { it.id.value })
    }

    @Test
    fun deleteFailureKeepsRecordAndConfirmationInsteadOfShowingFalseSuccess() =
        runTest(dispatcher) {
            val repository = FakeHistoryRepository(deleteSucceeds = false)
            repository.records.value = listOf(record())
            val viewModel = viewModel(repository)
            runCurrent()

            viewModel.onAction(HistoryAction.RequestDelete(HistoryRecordId("record-1")))
            viewModel.onAction(HistoryAction.ConfirmDelete)
            runCurrent()

            assertEquals(listOf("record-1"), viewModel.uiState.value.records.map { it.id.value })
            assertEquals(HistoryRecordId("record-1"), viewModel.uiState.value.pendingDeleteId)
            assertTrue(requireNotNull(viewModel.uiState.value.errorMessage).isNotBlank())
        }

    @Test
    fun confirmedClearUpdatesRepositoryBackedState() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        repository.records.value = listOf(record())
        val viewModel = viewModel(repository)
        runCurrent()

        viewModel.onAction(HistoryAction.RequestClear)
        viewModel.onAction(HistoryAction.ConfirmClear)
        runCurrent()

        assertEquals(HistoryLoadState.EMPTY, viewModel.uiState.value.loadState)
        assertEquals(false, viewModel.uiState.value.clearConfirmationVisible)
    }

    private fun viewModel(
        repository: HistoryRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = HistoryViewModel(
        observeHistory = ObserveHistoryUseCase(repository),
        deleteHistoryRecord = DeleteHistoryRecordUseCase(repository),
        clearHistory = ClearHistoryUseCase(repository),
        savedStateHandle = savedStateHandle,
    )

    private fun record() = HistoryRecord(
        id = HistoryRecordId("record-1"),
        operationId = HistoryOperationId("operation-1"),
        kind = HistoryKind.TEXT,
        direction = HistoryDirection.ANDROID_TO_BROWSER,
        browserLabel = "Chrome",
        timestampEpochMillis = 1_000,
        status = HistoryStatus.DELIVERED,
        textPreview = "hello",
        file = null,
        failureReason = null,
    )

    private class FakeHistoryRepository(
        var failReads: Boolean = false,
        private val deleteSucceeds: Boolean = true,
    ) : HistoryRepository {
        val records = MutableStateFlow<List<HistoryRecord>>(emptyList())
        var lastFilter = HistoryFilter()

        override fun observe(filter: HistoryFilter): Flow<List<HistoryRecord>> {
            lastFilter = filter
            if (failReads) {
                return flow { throw IOException("database unavailable") }
            }
            return records.map { items ->
                items.filter { item ->
                    (filter.kinds.isEmpty() || item.kind in filter.kinds) &&
                        (filter.directions.isEmpty() || item.direction in filter.directions) &&
                        (filter.statuses.isEmpty() || item.status in filter.statuses)
                }
            }
        }

        override suspend fun insert(record: HistoryRecord): HistoryInsertResult =
            HistoryInsertResult.Inserted

        override suspend fun delete(recordId: HistoryRecordId): Boolean {
            if (!deleteSucceeds) return false
            val before = records.value.size
            records.value = records.value.filterNot { it.id == recordId }
            return before != records.value.size
        }

        override suspend fun clear(): Int {
            val count = records.value.size
            records.value = emptyList()
            return count
        }
    }
}
