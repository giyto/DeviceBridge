package ru.hznik.devicebridge.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.hznik.devicebridge.feature.history.HistoryScreen
import ru.hznik.devicebridge.feature.history.HistoryViewModel
import ru.hznik.devicebridge.feature.home.HomeRoute
import ru.hznik.devicebridge.feature.home.HomeViewModel
import ru.hznik.devicebridge.feature.settings.SettingsRoute
import ru.hznik.devicebridge.feature.settings.SettingsViewModel
import ru.hznik.devicebridge.feature.text.SharedTextDraft
import ru.hznik.devicebridge.feature.text.TextRoute
import ru.hznik.devicebridge.feature.text.TextViewModel
import ru.hznik.devicebridge.feature.file.FileRoute
import ru.hznik.devicebridge.feature.file.FileViewModel
import ru.hznik.devicebridge.feature.file.SharedFileDraft
import ru.hznik.devicebridge.data.file.CompletedFileRegistry
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.data.file.FileSourceRegistry

private const val TEXT_ROUTE = "text"
private const val FILE_ROUTE = "files"

/** A notification asked to show one screen: "home", "text" or "files". */
data class OpenSectionRequest(val id: Long, val section: String)

internal val LocalNavigationWidthOverride = staticCompositionLocalOf<Dp?> { null }

@Composable
fun DeviceBridgeApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    sharedTextDraft: SharedTextDraft? = null,
    sharedFileDraft: SharedFileDraft? = null,
    startServerRequest: Long? = null,
    openSectionRequest: OpenSectionRequest? = null,
    onOpenSectionConsumed: (Long) -> Unit = {},
    onSharedTextConsumed: (Long) -> Unit = {},
    onSharedFileConsumed: (Long) -> Unit = {},
    completedFileRegistry: CompletedFileRegistry? = null,
    destinationLeaseRegistry: FileDestinationLeaseRegistry? = null,
    fileSourceRegistry: FileSourceRegistry? = null,
    homeContent: @Composable (
        onOpenText: () -> Unit,
        onOpenFiles: () -> Unit,
        onOpenSettings: () -> Unit,
    ) -> Unit = { onOpenText, onOpenFiles, onOpenSettings ->
        val homeViewModel: HomeViewModel = hiltViewModel()
        HomeRoute(homeViewModel, onOpenText, onOpenFiles, onOpenSettings)
    },
    textContent: @Composable (
        onBack: () -> Unit,
        sharedDraft: SharedTextDraft?,
    ) -> Unit = { onBack, sharedDraft ->
        val textViewModel: TextViewModel = hiltViewModel()
        TextRoute(
            viewModel = textViewModel,
            onBack = onBack,
            sharedDraft = sharedDraft,
            onSharedTextConsumed = onSharedTextConsumed,
        )
    },
    fileContent: @Composable (
        onBack: () -> Unit,
        sharedDraft: SharedFileDraft?,
    ) -> Unit = { onBack, sharedDraft ->
        val fileViewModel: FileViewModel = hiltViewModel()
        FileRoute(
            fileViewModel,
            onBack,
            sharedDraft,
            onSharedFileConsumed,
            completedFileRegistry,
            destinationLeaseRegistry,
            fileSourceRegistry,
        )
    },
    historyContent: @Composable () -> Unit = {
        val historyViewModel: HistoryViewModel = hiltViewModel()
        val historyUiState by historyViewModel.uiState.collectAsStateWithLifecycle()
        HistoryScreen(
            uiState = historyUiState,
            onAction = historyViewModel::onAction,
        )
    },
    settingsContent: @Composable () -> Unit = {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        SettingsRoute(settingsViewModel)
    },
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val initialRoute = remember(navController) {
        if (sharedFileDraft != null) {
            FILE_ROUTE
        } else if (sharedTextDraft == null) {
            TopLevelDestination.Home.route
        } else {
            TEXT_ROUTE
        }
    }

    val showTopLevelNavigation = TopLevelDestination.entries.any { it.route == currentRoute }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val navigationWidth = LocalNavigationWidthOverride.current ?: maxWidth
        val useNavigationRail = navigationWidth >= 600.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!useNavigationRail && showTopLevelNavigation) {
                    NavigationBar(
                        modifier = Modifier.testTag("top_level_navigation_bar"),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = { TopLevelIcon(destination) },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = "Раздел ${destination.label}"
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (useNavigationRail && showTopLevelNavigation) {
                NavigationRail(
                    modifier = Modifier.testTag("top_level_navigation_rail"),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = { TopLevelIcon(destination) },
                            label = { Text(destination.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = "Раздел ${destination.label}"
                            },
                        )
                    }
                }
            }

        NavHost(
            navController = navController,
            startDestination = initialRoute,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        ) {
            composable(TopLevelDestination.Home.route) {
                homeContent(
                    {
                        navController.navigate(TEXT_ROUTE) { launchSingleTop = true }
                    },
                    {
                        navController.navigate(FILE_ROUTE) { launchSingleTop = true }
                    },
                    {
                        navController.navigateTopLevel(TopLevelDestination.Settings.route)
                    },
                )
            }
            composable(TopLevelDestination.History.route) {
                historyContent()
            }
            composable(TopLevelDestination.Settings.route) {
                settingsContent()
            }
            composable(TEXT_ROUTE) {
                textContent(
                    { navController.popBackStack() },
                    sharedTextDraft,
                )
            }
            composable(FILE_ROUTE) {
                fileContent(
                    { navController.popBackStack() },
                    sharedFileDraft,
                )
            }
        }
        }
    }
    }

    LaunchedEffect(sharedTextDraft?.requestId, currentRoute) {
        if (
            sharedTextDraft != null &&
            currentRoute != null &&
            currentRoute != TEXT_ROUTE
        ) {
            navController.navigate(TEXT_ROUTE) {
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(startServerRequest, currentRoute) {
        val home = TopLevelDestination.Home.route
        if (startServerRequest != null && currentRoute != null && currentRoute != home) {
            navController.navigate(home) { launchSingleTop = true }
        }
    }
    LaunchedEffect(sharedFileDraft?.requestId, currentRoute) {
        if (sharedFileDraft != null && currentRoute != null && currentRoute != FILE_ROUTE) {
            navController.navigate(FILE_ROUTE) { launchSingleTop = true }
        }
    }
    LaunchedEffect(openSectionRequest?.id, currentRoute != null) {
        val request = openSectionRequest ?: return@LaunchedEffect
        if (currentRoute == null) return@LaunchedEffect
        val route = when (request.section) {
            "text" -> TEXT_ROUTE
            "files" -> FILE_ROUTE
            else -> TopLevelDestination.Home.route
        }
        if (currentRoute != route) {
            navController.navigate(route) { launchSingleTop = true }
        }
        onOpenSectionConsumed(request.id)
    }
}

/** Opens a top-level section the way the navigation bar does, keeping each section's state. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TopLevelIcon(destination: TopLevelDestination) {
    Icon(
        imageVector = destination.icon,
        contentDescription = null,
        modifier = Modifier
            .size(28.dp)
            .testTag("top_level_icon_${destination.route}"),
    )
}
