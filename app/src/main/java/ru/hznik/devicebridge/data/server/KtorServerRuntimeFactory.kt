package ru.hznik.devicebridge.data.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.data.network.LanEndpointResolution
import ru.hznik.devicebridge.data.network.LanEndpointResolver
import ru.hznik.devicebridge.data.network.LanNetworkSnapshotProvider
import ru.hznik.devicebridge.domain.model.ServerEndpoint
import ru.hznik.devicebridge.web.WebAssetProvider
import ru.hznik.devicebridge.web.installWebRoutes

@Singleton
class KtorServerRuntimeFactory @Inject constructor(
    private val networkSnapshotProvider: LanNetworkSnapshotProvider,
    private val endpointResolver: LanEndpointResolver,
    private val webAssetProvider: WebAssetProvider,
) : ServerRuntimeFactory {

    override fun create(): ServerRuntime = KtorServerRuntime(
        networkSnapshotProvider = networkSnapshotProvider,
        endpointResolver = endpointResolver,
        webAssetProvider = webAssetProvider,
    )
}

private class KtorServerRuntime(
    private val networkSnapshotProvider: LanNetworkSnapshotProvider,
    private val endpointResolver: LanEndpointResolver,
    private val webAssetProvider: WebAssetProvider,
) : ServerRuntime {

    private var stopServer: (() -> Unit)? = null
    private var startedNetworkFingerprint: String? = null

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
        val engine = embeddedServer(
            factory = CIO,
            host = ALL_LOCAL_INTERFACES,
            port = DYNAMIC_PORT,
            module = {
                installWebRoutes(
                    webAssetProvider = webAssetProvider,
                    allowedHosts = { allowedAuthorities.get() },
                )
            },
        )

        engine.start(wait = false)
        return try {
            val connector = engine.engine.resolvedConnectors().single()
            val endpoint = ServerEndpoint(
                host = candidate.host,
                port = connector.port,
            )
            allowedAuthorities.set(setOf(endpoint.host + ":" + endpoint.port))
            startedNetworkFingerprint = candidate.networkFingerprint
            stopServer = {
                engine.stop(
                    gracePeriodMillis = STOP_GRACE_PERIOD_MILLIS,
                    timeoutMillis = STOP_TIMEOUT_MILLIS,
                )
            }
            endpoint
        } catch (throwable: Throwable) {
            engine.stop(
                gracePeriodMillis = 0,
                timeoutMillis = STOP_TIMEOUT_MILLIS,
            )
            throw throwable
        }
    }

    override suspend fun stop() {
        val stop = stopServer ?: return
        stopServer = null
        startedNetworkFingerprint = null
        withContext(NonCancellable) {
            stop()
        }
    }

    private companion object {
        const val ALL_LOCAL_INTERFACES = "0.0.0.0"
        const val DYNAMIC_PORT = 0
        const val STOP_GRACE_PERIOD_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
