package ru.hznik.devicebridge.feature.home

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenText: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberServerPermissionLauncher { result ->
        val localNetworkWasRequested =
            Manifest.permission.ACCESS_LOCAL_NETWORK in result
        val localNetworkGranted =
            result[Manifest.permission.ACCESS_LOCAL_NETWORK] == true
        val canAskForLocalNetworkAgain =
            !localNetworkWasRequested ||
                localNetworkGranted ||
                activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        it,
                        Manifest.permission.ACCESS_LOCAL_NETWORK,
                    )
                } == true
        viewModel.onAction(
            HomeAction.PermissionsResolved(canAskForLocalNetworkAgain),
        )
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onAction(
            HomeAction.PermissionsResolved(localNetworkCanAskAgain = false),
        )
    }

    LaunchedEffect(viewModel, permissionLauncher) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.RequestPermissions ->
                    permissionLauncher.launch(effect.permissions.toTypedArray())
                HomeEffect.OpenAppSettings -> settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
    }

    HomeScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onOpenText = onOpenText,
        onOpenFiles = onOpenFiles,
        onOpenSettings = onOpenSettings,
    )
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
