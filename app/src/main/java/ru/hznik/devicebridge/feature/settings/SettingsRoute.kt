package ru.hznik.devicebridge.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.devicebridge.data.file.AndroidFileDestinationGateway
import ru.hznik.devicebridge.data.file.ContentResolverDocumentTreePermissionGateway
import ru.hznik.devicebridge.data.file.DestinationApproval
import ru.hznik.devicebridge.data.file.PersistedDestinationPermissionController
import ru.hznik.devicebridge.data.file.SettingsDestinationApproval
import ru.hznik.devicebridge.data.tls.FileProviderRootCertificateExporter

@Composable
internal fun SettingsRoute(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val permissionController = remember(context) {
        PersistedDestinationPermissionController(
            ContentResolverDocumentTreePermissionGateway(context.contentResolver),
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val destinationPicker = rememberLauncherForActivityResult(
        AndroidFileDestinationGateway.contract(),
    ) { uri ->
        when (val result = permissionController.approve(uri?.toString())) {
            is SettingsDestinationApproval.Approved ->
                viewModel.onAction(SettingsAction.DestinationSelected(result.uri))
            SettingsDestinationApproval.Cancelled ->
                viewModel.onAction(SettingsAction.DestinationCancelled)
            SettingsDestinationApproval.Unavailable ->
                viewModel.onAction(SettingsAction.DestinationPermissionUnavailable)
        }
    }
    val savedDestinationUri = uiState.settings.destinationTree?.value
    LaunchedEffect(savedDestinationUri) {
        val uri = savedDestinationUri ?: return@LaunchedEffect
        when (val approval = permissionController.openPersisted(uri)) {
            is DestinationApproval.Approved -> {
                approval.lease.release()
                viewModel.onAction(
                    SettingsAction.DestinationAvailabilityChecked(uri, isAvailable = true),
                )
            }
            DestinationApproval.Unavailable,
            DestinationApproval.Cancelled,
            -> viewModel.onAction(
                SettingsAction.DestinationAvailabilityChecked(uri, isAvailable = false),
            )
        }
    }

    LaunchedEffect(viewModel, destinationPicker) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.ChooseDestination -> {
                    val initialUri = viewModel.uiState.value.settings.destinationTree
                        ?.value
                        ?.let(Uri::parse)
                    destinationPicker.launch(initialUri)
                }
                is SettingsEffect.ShareCertificate -> {
                    val uri = Uri.parse(effect.contentUri)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(FileProviderRootCertificateExporter.MIME_TYPE)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TITLE, FileProviderRootCertificateExporter.FILE_NAME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = android.content.ClipData.newRawUri(
                        FileProviderRootCertificateExporter.FILE_NAME,
                        uri,
                    )
                    context.startActivity(Intent.createChooser(send, "Поделиться сертификатом"))
                }
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
    )
}
