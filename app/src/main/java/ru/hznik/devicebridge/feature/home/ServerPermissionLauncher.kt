package ru.hznik.devicebridge.feature.home

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

@Composable
fun rememberServerPermissionLauncher(
    onResult: (Map<String, Boolean>) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>> =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = onResult,
    )
