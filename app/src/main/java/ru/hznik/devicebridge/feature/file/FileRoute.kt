package ru.hznik.devicebridge.feature.file

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.data.file.AndroidCompletedFileOpener
import ru.hznik.devicebridge.data.file.AndroidFileDestinationGateway
import ru.hznik.devicebridge.data.file.AndroidFileSourcePickerGateway
import ru.hznik.devicebridge.data.file.CompletedFileOpenResult
import ru.hznik.devicebridge.data.file.CompletedFileRegistry
import ru.hznik.devicebridge.data.file.ContentResolverDocumentTreePermissionGateway
import ru.hznik.devicebridge.data.file.ContextExternalFileViewerGateway
import ru.hznik.devicebridge.data.file.DOCUMENT_TREE_READ_WRITE_FLAGS
import ru.hznik.devicebridge.data.file.DestinationApproval
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.data.file.PersistedDestinationPermissionController
import ru.hznik.devicebridge.data.file.ScopedDocumentTreeLease
import ru.hznik.devicebridge.domain.file.FileTransferId

@Composable
internal fun FileRoute(
    viewModel: FileViewModel,
    onBack: () -> Unit,
    sharedDraft: SharedFileDraft?,
    onSharedFileConsumed: (Long) -> Unit,
    completedFileRegistry: CompletedFileRegistry?,
    destinationLeaseRegistry: FileDestinationLeaseRegistry?,
    fileSourceRegistry: FileSourceRegistry?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val effectiveFileSourceRegistry = remember(fileSourceRegistry) {
        fileSourceRegistry ?: FileSourceRegistry()
    }
    val preparer = remember(context, effectiveFileSourceRegistry) {
        AndroidFileSelectionPreparer(
            context = context,
            sourceRegistry = effectiveFileSourceRegistry,
        )
    }
    val documentTreePermissions = remember(context) {
        ContentResolverDocumentTreePermissionGateway(context.contentResolver)
    }
    val destinationGateway = remember(documentTreePermissions) {
        AndroidFileDestinationGateway(documentTreePermissions)
    }
    val persistedDestinationController = remember(documentTreePermissions) {
        PersistedDestinationPermissionController(documentTreePermissions)
    }
    val completedFileOpener = remember(context) {
        AndroidCompletedFileOpener(ContextExternalFileViewerGateway(context))
    }
    var pendingDestinationId by rememberSaveable { mutableStateOf<String?>(null) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * Hands an approved folder to the transfer. Without a lease registry the folder cannot be
     * kept for it, so the lease is released and the folder reported unavailable.
     */
    fun registerDestination(transferId: FileTransferId, lease: ScopedDocumentTreeLease): Boolean {
        val leases = destinationLeaseRegistry
        if (leases == null) {
            lease.release()
            viewModel.onAction(FileAction.DestinationUnavailable(transferId))
            return false
        }
        val destinationId = leases.register(transferId, lease)
        viewModel.onAction(FileAction.DestinationSelected(transferId, destinationId))
        return true
    }

    val sourcePicker = rememberLauncherForActivityResult(
        AndroidFileSourcePickerGateway.contract(),
    ) { uris ->
        scope.launch {
            val result = preparer.prepare(
                uris = uris.map(Uri::toString),
                effectiveFileLimitBytes = uiState.effectiveFileLimitBytes,
            )
            viewModel.onAction(FileAction.SelectionReceived(result.items))
            viewModel.onAction(FileAction.SelectionRejected(result.rejectedCount))
        }
    }
    val destinationPicker = rememberLauncherForActivityResult(
        AndroidFileDestinationGateway.contract(),
    ) { uri ->
        val transferId = pendingDestinationId?.let(::FileTransferId)
        pendingDestinationId = null
        if (transferId != null) {
            when (
                val approval = destinationGateway.approve(
                    uri?.toString(),
                    DOCUMENT_TREE_READ_WRITE_FLAGS,
                )
            ) {
                is DestinationApproval.Approved -> registerDestination(transferId, approval.lease)
                DestinationApproval.Cancelled ->
                    viewModel.onAction(FileAction.DestinationCancelled(transferId))
                DestinationApproval.Unavailable ->
                    viewModel.onAction(FileAction.DestinationUnavailable(transferId))
            }
        }
    }

    LaunchedEffect(viewModel, sourcePicker, destinationPicker) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FileEffect.ChooseFiles -> sourcePicker.launch(arrayOf("*/*"))
                is FileEffect.ChooseDestination -> {
                    pendingDestinationId = effect.transferId.value
                    destinationPicker.launch(null)
                }
                is FileEffect.UseDefaultDestination -> {
                    pendingDestinationId = effect.transferId.value
                    when (val approval = persistedDestinationController.openPersisted(effect.uri)) {
                        is DestinationApproval.Approved -> {
                            if (registerDestination(effect.transferId, approval.lease)) {
                                pendingDestinationId = null
                            }
                        }
                        DestinationApproval.Unavailable -> destinationPicker.launch(null)
                        DestinationApproval.Cancelled -> destinationPicker.launch(null)
                    }
                }
                is FileEffect.OpenCompleted -> {
                    val item = viewModel.uiState.value.transfers
                        .firstOrNull { it.id == effect.transferId }
                    val uri = completedFileRegistry?.uri(effect.transferId)
                    val result = if (item == null || uri == null) {
                        CompletedFileOpenResult.UnsafeUri
                    } else {
                        completedFileOpener.open(item.phase, uri, item.mimeType)
                    }
                    if (result != CompletedFileOpenResult.Opened) {
                        val message = when (result) {
                            CompletedFileOpenResult.NoViewer ->
                                "На устройстве нет приложения для открытия этого файла."
                            CompletedFileOpenResult.NotCompleted ->
                                "Файл ещё не завершён и не может быть открыт."
                            CompletedFileOpenResult.UnsafeUri ->
                                "Сохранённый файл больше недоступен."
                            CompletedFileOpenResult.Opened -> ""
                        }
                        viewModel.onAction(FileAction.OpenFailed(message))
                    }
                }
            }
        }
    }
    LaunchedEffect(sharedDraft?.requestId) {
        sharedDraft?.let { draft ->
            val uris = draft.items.filter { it.error == null }.map { it.uri }
            val result = preparer.prepare(
                uris = uris,
                stageTemporarySources = true,
                effectiveFileLimitBytes = uiState.effectiveFileLimitBytes,
            )
            viewModel.onAction(FileAction.SharedSelectionReceived(result.items))
            viewModel.onAction(FileAction.SelectionRejected(result.rejectedCount))
            onSharedFileConsumed(draft.requestId)
        }
    }

    FileScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}
