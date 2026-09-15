package ru.hznik.devicebridge.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.hznik.devicebridge.feature.history.HistoryScreen
import ru.hznik.devicebridge.feature.home.HomeScreen
import ru.hznik.devicebridge.feature.home.HomeAction
import ru.hznik.devicebridge.feature.home.HomeEffect
import ru.hznik.devicebridge.feature.home.HomeViewModel
import ru.hznik.devicebridge.feature.home.rememberServerPermissionLauncher
import ru.hznik.devicebridge.feature.settings.SettingsScreen

@Composable
fun DeviceBridgeApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    homeContent: @Composable () -> Unit = {
        val homeViewModel: HomeViewModel = hiltViewModel()
        HomeRoute(homeViewModel)
    },
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Text(
                                text = destination.symbol,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        label = { Text(destination.label) },
                        modifier = Modifier.semantics {
                            contentDescription = "Раздел ${destination.label}"
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(TopLevelDestination.Home.route) {
                homeContent()
            }
            composable(TopLevelDestination.History.route) {
                HistoryScreen()
            }
            composable(TopLevelDestination.Settings.route) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun HomeRoute(viewModel: HomeViewModel) {
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
    )
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
