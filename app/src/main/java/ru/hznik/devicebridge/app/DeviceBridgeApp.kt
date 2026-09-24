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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import ru.hznik.devicebridge.feature.home.HomeScreen
import ru.hznik.devicebridge.feature.home.HomeAction
import ru.hznik.devicebridge.feature.home.HomeEffect
import ru.hznik.devicebridge.feature.home.HomeViewModel
import ru.hznik.devicebridge.feature.home.rememberServerPermissionLauncher
import ru.hznik.devicebridge.feature.settings.SettingsScreen
import ru.hznik.devicebridge.feature.settings.SettingsAction
import ru.hznik.devicebridge.feature.settings.SettingsEffect
import ru.hznik.devicebridge.feature.settings.SettingsViewModel
import ru.hznik.devicebridge.data.tls.FileProviderRootCertificateExporter
import ru.hznik.devicebridge.feature.text.TextScreen
import ru.hznik.devicebridge.feature.text.SharedTextDraft
import ru.hznik.devicebridge.feature.text.TextAction
import ru.hznik.devicebridge.feature.text.TextViewModel
import ru.hznik.devicebridge.feature.text.AndroidTextPlatformGateway
import ru.hznik.devicebridge.feature.file.FileScreen
import ru.hznik.devicebridge.feature.file.FileUiState
import ru.hznik.devicebridge.feature.file.FileViewModel
import ru.hznik.devicebridge.feature.file.FileAction
import ru.hznik.devicebridge.feature.file.FileEffect
import ru.hznik.devicebridge.feature.file.SharedFileDraft
import ru.hznik.devicebridge.feature.file.AndroidFileSelectionPreparer
import ru.hznik.devicebridge.data.file.AndroidFileDestinationGateway
import ru.hznik.devicebridge.data.file.AndroidFileSourcePickerGateway
import ru.hznik.devicebridge.data.file.ContentResolverDocumentTreePermissionGateway
import ru.hznik.devicebridge.data.file.DestinationApproval
import ru.hznik.devicebridge.data.file.PersistedDestinationPermissionController
import ru.hznik.devicebridge.data.file.SettingsDestinationApproval
import ru.hznik.devicebridge.data.file.CompletedFileRegistry
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.data.file.AndroidCompletedFileOpener
import ru.hznik.devicebridge.data.file.CompletedFileOpenResult
import ru.hznik.devicebridge.data.file.ContextExternalFileViewerGateway
import ru.hznik.devicebridge.domain.file.FileTransferId
import kotlinx.coroutines.launch

private const val TEXT_ROUTE = "text"
private const val FILE_ROUTE = "files"

internal val LocalNavigationWidthOverride = staticCompositionLocalOf<Dp?> { null }

@Composable
fun DeviceBridgeApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    sharedTextDraft: SharedTextDraft? = null,
    sharedFileDraft: SharedFileDraft? = null,
    startServerRequest: Long? = null,
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
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("top_level_icon_${destination.route}"),
                                )
                            },
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
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("top_level_icon_${destination.route}"),
                                )
                            },
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
                        navController.navigate(TopLevelDestination.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
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
}

@Composable
private fun SettingsRoute(viewModel: SettingsViewModel) {
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

@Composable
private fun HomeRoute(
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

@Composable
private fun TextRoute(
    viewModel: TextViewModel,
    onBack: () -> Unit,
    sharedDraft: SharedTextDraft?,
    onSharedTextConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val platformGateway = remember(context) {
        AndroidTextPlatformGateway(context)
    }
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sharedDraft?.requestId) {
        sharedDraft?.let {
            viewModel.onAction(TextAction.SharedDraftReceived(it.text))
            onSharedTextConsumed(it.requestId)
        }
    }
    TextScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onPasteRequested = {
            coroutineScope.launch {
                platformGateway.readClipboardText()?.let { clipboardText ->
                    viewModel.onAction(TextAction.DraftChanged(clipboardText))
                }
            }
        },
        onOpenLinkRequested = platformGateway::openHttpLink,
        onBack = onBack,
    )
}

@Composable
private fun FileRoute(
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
    val destinationGateway = remember(context) {
        AndroidFileDestinationGateway(
            ContentResolverDocumentTreePermissionGateway(context.contentResolver),
        )
    }
    val persistedDestinationController = remember(context) {
        PersistedDestinationPermissionController(
            ContentResolverDocumentTreePermissionGateway(context.contentResolver),
        )
    }
    val completedFileOpener = remember(context) {
        AndroidCompletedFileOpener(ContextExternalFileViewerGateway(context))
    }
    var pendingDestinationId by rememberSaveable { mutableStateOf<String?>(null) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            ) {
                is DestinationApproval.Approved -> {
                    val leases = destinationLeaseRegistry
                    if (leases == null) {
                        approval.lease.release()
                        viewModel.onAction(FileAction.DestinationUnavailable(transferId))
                    } else {
                        val destinationId = leases.register(transferId, approval.lease)
                        viewModel.onAction(
                            FileAction.DestinationSelected(
                                transferId,
                                destinationId,
                            ),
                        )
                    }
                }
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
                            val leases = destinationLeaseRegistry
                            if (leases == null) {
                                approval.lease.release()
                                viewModel.onAction(
                                    FileAction.DestinationUnavailable(effect.transferId),
                                )
                            } else {
                                val destinationId = leases.register(
                                    effect.transferId,
                                    approval.lease,
                                )
                                pendingDestinationId = null
                                viewModel.onAction(
                                    FileAction.DestinationSelected(
                                        effect.transferId,
                                        destinationId,
                                    ),
                                )
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

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
