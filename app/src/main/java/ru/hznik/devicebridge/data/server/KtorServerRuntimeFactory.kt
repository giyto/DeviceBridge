package ru.hznik.devicebridge.data.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.data.network.LanEndpointResolution
import ru.hznik.devicebridge.data.network.LanEndpointResolver
import ru.hznik.devicebridge.data.network.LanNetworkSnapshotProvider
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.session.SessionGenerationHandle
import ru.hznik.devicebridge.data.text.TextTransferCoordinator
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.data.file.CompletedFileRegistry
import ru.hznik.devicebridge.data.file.AutoAcceptLifecycle
import ru.hznik.devicebridge.data.file.FileDestinationLeaseRegistry
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.usecase.ObserveSettingsUseCase
import ru.hznik.devicebridge.di.ApplicationScope
import ru.hznik.devicebridge.di.PhysicalDeviceName
import ru.hznik.devicebridge.web.WebAssetProvider
import ru.hznik.devicebridge.web.installSessionRoutes
import ru.hznik.devicebridge.web.installTextRoutes
import ru.hznik.devicebridge.web.RemoteClientAddress
import ru.hznik.devicebridge.web.installWebRoutes
import ru.hznik.devicebridge.web.installFileRoutes
import ru.hznik.devicebridge.web.FileSessionEventBridge
import ru.hznik.devicebridge.web.installSecureModeGuard
import ru.hznik.devicebridge.data.tls.RelayedConnectionRegistry
import ru.hznik.devicebridge.data.tls.SecureTransport
import ru.hznik.devicebridge.data.tls.ServerTlsMaterial
import ru.hznik.devicebridge.data.tls.TlsFrontDoor
import ru.hznik.devicebridge.data.tls.TlsMaterialException
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import java.net.Inet4Address
import java.net.InetAddress

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProductionServerPort

const val DEFAULT_PRODUCTION_SERVER_PORT = 8_787

@Singleton
class EffectiveFileLimitProvider private constructor(
    private val settingsState: StateFlow<DeviceSettings>,
) {
    @Inject
    constructor(
        observeSettings: ObserveSettingsUseCase,
        @ApplicationScope applicationScope: CoroutineScope,
        @PhysicalDeviceName initialDeviceName: String,
    ) : this(
        observeSettings().stateIn(
            applicationScope,
            SharingStarted.Eagerly,
            DeviceSettings.defaults().copy(deviceName = initialDeviceName),
        ),
    )

    fun currentBytes(): Long =
        ru.hznik.devicebridge.domain.file.effectiveFileLimitBytes(
            settingsState.value.effectiveFileLimitBytes,
        )

    fun currentDeviceName(): String = settingsState.value.deviceName

    fun secureModeEnabled(): Boolean = settingsState.value.secureModeEnabled

    /** Suspends until [secureModeEnabled] reports [enabled]. */
    suspend fun awaitSecureMode(enabled: Boolean) {
        settingsState.first { it.secureModeEnabled == enabled }
    }

    companion object {
        internal fun hardLimit(): EffectiveFileLimitProvider =
            EffectiveFileLimitProvider(MutableStateFlow(DeviceSettings.defaults()))
    }
}

@Singleton
class KtorServerRuntimeFactory @Inject constructor(
    private val networkSnapshotProvider: LanNetworkSnapshotProvider,
    private val endpointResolver: LanEndpointResolver,
    private val webAssetProvider: WebAssetProvider,
    private val browserSessionCoordinator: BrowserSessionCoordinator,
    private val textTransferCoordinator: TextTransferCoordinator,
    private val sessionEventDispatcher: SessionEventDispatcher,
    private val fileTransferCoordinator: FileTransferCoordinator,
    private val uploadTargetFactory: FileUploadTargetFactory,
    private val downloadSourceFactory: FileDownloadSourceFactory,
    private val fileSourceRegistry: FileSourceRegistry,
    private val completedFileRegistry: CompletedFileRegistry,
    private val destinationLeaseRegistry: FileDestinationLeaseRegistry = FileDestinationLeaseRegistry(),
    @Suppress("unused") private val fileSessionEventBridge: FileSessionEventBridge,
    private val monotonicClock: MonotonicClock,
    private val effectiveFileLimitProvider: EffectiveFileLimitProvider =
        EffectiveFileLimitProvider.hardLimit(),
    @param:ProductionServerPort
    private val preferredPort: Int = DEFAULT_PRODUCTION_SERVER_PORT,
    private val autoAccept: AutoAcceptLifecycle = AutoAcceptLifecycle.None,
    private val secureTransport: SecureTransport = SecureTransport.Disabled,
) : ServerRuntimeFactory {

    init {
        require(preferredPort in 0..65_535)
    }

    override fun create(): ServerRuntime = KtorServerRuntime(
        networkSnapshotProvider = networkSnapshotProvider,
        endpointResolver = endpointResolver,
        webAssetProvider = webAssetProvider,
        browserSessionCoordinator = browserSessionCoordinator,
        textTransferCoordinator = textTransferCoordinator,
        sessionEventDispatcher = sessionEventDispatcher,
        fileTransferCoordinator = fileTransferCoordinator,
        uploadTargetFactory = uploadTargetFactory,
        downloadSourceFactory = downloadSourceFactory,
        fileSourceRegistry = fileSourceRegistry,
        completedFileRegistry = completedFileRegistry,
        destinationLeaseRegistry = destinationLeaseRegistry,
        monotonicClock = monotonicClock,
        effectiveFileLimitBytes = effectiveFileLimitProvider::currentBytes,
        deviceName = effectiveFileLimitProvider::currentDeviceName,
        preferredPort = preferredPort,
        autoAccept = autoAccept,
        secureTransport = secureTransport,
    )
}

private class KtorServerRuntime(
    private val networkSnapshotProvider: LanNetworkSnapshotProvider,
    private val endpointResolver: LanEndpointResolver,
    private val webAssetProvider: WebAssetProvider,
    private val browserSessionCoordinator: BrowserSessionCoordinator,
    private val textTransferCoordinator: TextTransferCoordinator,
    private val sessionEventDispatcher: SessionEventDispatcher,
    private val fileTransferCoordinator: FileTransferCoordinator,
    private val uploadTargetFactory: FileUploadTargetFactory,
    private val downloadSourceFactory: FileDownloadSourceFactory,
    private val fileSourceRegistry: FileSourceRegistry,
    private val completedFileRegistry: CompletedFileRegistry,
    private val destinationLeaseRegistry: FileDestinationLeaseRegistry,
    private val monotonicClock: MonotonicClock,
    private val effectiveFileLimitBytes: () -> Long,
    private val deviceName: () -> String,
    private val preferredPort: Int,
    private val autoAccept: AutoAcceptLifecycle,
    private val secureTransport: SecureTransport,
) : ServerRuntime {

    private var stopServer: (() -> Unit)? = null
    private var startedNetworkFingerprint: String? = null
    private val sessionHandle = AtomicReference<SessionGenerationHandle?>(null)

    override val networkFingerprint: String?
        get() = startedNetworkFingerprint

    override suspend fun start(): ServerEndpoint {
        check(stopServer == null) { "Server runtime is already started" }
        val candidate = when (
            val resolution = endpointResolver.resolve(networkSnapshotProvider.snapshot())
        ) {
            is LanEndpointResolution.Resolved -> resolution.candidate
            is LanEndpointResolution.Failed ->
                throw ServerRuntimeStartException(resolution.error)
        }
        val allowedAuthorities = AtomicReference<Set<String>>(emptySet())
        // In secure mode the server only listens on loopback; browsers reach it through the
        // TLS front door on the published port, which records who each connection came from.
        val tls = if (secureTransport.isEnabled()) prepareTls(candidate.host) else null
        val relayed = RelayedConnectionRegistry()
        val engine = embeddedServer(
            factory = CIO,
            host = if (tls != null) TlsFrontDoor.BACKEND_HOST.hostAddress!! else ALL_LOCAL_INTERFACES,
            port = if (tls != null) 0 else preferredPort,
            module = {
                if (tls != null) {
                    installSecureModeGuard(
                        peerFor = relayed::peerFor,
                        rootCertificate = { tls.root.encoded },
                        webAssetProvider = webAssetProvider,
                        allowedHosts = { allowedAuthorities.get() },
                    )
                }
                installWebRoutes(
                    webAssetProvider = webAssetProvider,
                    allowedHosts = { allowedAuthorities.get() },
                )
                installSessionRoutes(
                    coordinator = browserSessionCoordinator,
                    generationHandle = { sessionHandle.get() },
                    allowedHosts = { allowedAuthorities.get() },
                    sourceIpv4 = { call ->
                        val remote = if (tls != null) {
                            relayed.peerFor(call.request.local.remotePort)?.address.orEmpty()
                        } else {
                            call.request.local.remoteHost
                        }
                        RemoteClientAddress.canonicalIpv4(remote)
                    },
                    monotonicClockMs = monotonicClock::nowMs,
                    wallClockMs = System::currentTimeMillis,
                    textCoordinator = textTransferCoordinator,
                    eventDispatcher = sessionEventDispatcher,
                    fileCoordinator = fileTransferCoordinator,
                    effectiveFileLimitBytes = effectiveFileLimitBytes,
                    deviceName = deviceName,
                )
                installTextRoutes(
                    sessionCoordinator = browserSessionCoordinator,
                    textCoordinator = textTransferCoordinator,
                    generationHandle = { sessionHandle.get() },
                    allowedHosts = { allowedAuthorities.get() },
                    wallClockMs = System::currentTimeMillis,
                )
                installFileRoutes(
                    sessionCoordinator = browserSessionCoordinator,
                    fileCoordinator = fileTransferCoordinator,
                    generationHandle = { sessionHandle.get() },
                    allowedHosts = { allowedAuthorities.get() },
                    wallClockMs = System::currentTimeMillis,
                    uploadTargetFactory = uploadTargetFactory,
                    downloadSourceFactory = downloadSourceFactory,
                    effectiveFileLimitBytes = effectiveFileLimitBytes,
                )
            },
        )

        engine.start(wait = false)
        var frontDoor: TlsFrontDoor? = null
        return try {
            val connector = engine.engine.resolvedConnectors().single()
            val publishedPort = if (tls != null) {
                val door = TlsFrontDoor(TlsFrontDoor.serverContext(tls), connector.port, relayed)
                frontDoor = door
                door.start(InetAddress.getByName(ALL_LOCAL_INTERFACES), preferredPort)
            } else {
                connector.port
            }
            val endpoint = ServerEndpoint(
                host = candidate.host,
                port = publishedPort,
                secure = tls != null,
            )
            allowedAuthorities.set(setOf(endpoint.host + ":" + endpoint.port))
            startedNetworkFingerprint = candidate.networkFingerprint
            stopServer = {
                frontDoor?.close()
                engine.stop(
                    gracePeriodMillis = STOP_GRACE_PERIOD_MILLIS,
                    timeoutMillis = STOP_TIMEOUT_MILLIS,
                )
            }
            endpoint
        } catch (throwable: Throwable) {
            frontDoor?.close()
            engine.stop(
                gracePeriodMillis = 0,
                timeoutMillis = STOP_TIMEOUT_MILLIS,
            )
            throw throwable
        }
    }

    private fun prepareTls(host: String): ServerTlsMaterial = try {
        secureTransport.serverMaterial(InetAddress.getByName(host) as Inet4Address)
    } catch (failure: TlsMaterialException) {
        throw ServerRuntimeStartException(ServerLifecycleError.SecureCertificateUnavailable)
    }

    override suspend fun activateSessionGeneration(generation: Long) {
        check(stopServer != null) { "Listener must be started before session generation" }
        check(sessionHandle.get() == null) { "Session generation is already active" }
        val generationId = ServerGenerationId(generation)
        val handle = browserSessionCoordinator.activate(generationId)
        textTransferCoordinator.activate(generationId)
        fileTransferCoordinator.activate(generationId)
        sessionHandle.set(handle)
        autoAccept.activate()
    }

    override suspend fun closeSessionGeneration() {
        val handle = sessionHandle.getAndSet(null) ?: return
        autoAccept.deactivate()
        fileTransferCoordinator.close(handle.generationId)
        textTransferCoordinator.close(handle.generationId)
        browserSessionCoordinator.closeGeneration(handle)
        fileSourceRegistry.clear()
        completedFileRegistry.clear()
        destinationLeaseRegistry.releaseAll()
    }

    override suspend fun stop() {
        closeSessionGeneration()
        val stop = stopServer ?: return
        stopServer = null
        startedNetworkFingerprint = null
        withContext(NonCancellable) {
            stop()
        }
    }

    private companion object {
        const val ALL_LOCAL_INTERFACES = "0.0.0.0"
        const val STOP_GRACE_PERIOD_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
