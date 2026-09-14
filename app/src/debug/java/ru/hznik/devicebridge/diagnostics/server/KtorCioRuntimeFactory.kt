package ru.hznik.devicebridge.diagnostics.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.hznik.devicebridge.web.WebAssetProvider
import ru.hznik.devicebridge.web.installWebRoutes

class KtorCioRuntimeFactory @JvmOverloads constructor(
    private val sdkIntProvider: () -> Int = { 0 },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val webAssetProvider: WebAssetProvider? = null,
    private val webHostNames: Set<String> = setOf("127.0.0.1", "localhost"),
) : DiagnosticServerRuntimeFactory {

    override suspend fun start(
        preferredPort: Int,
        token: String,
    ): DiagnosticServerRuntime {
        val startedAtMillis = clockMillis()
        val serverPort = preferredPort.coerceAtLeast(0)
        if (webAssetProvider != null) {
            require(serverPort > 0) {
                "A fixed port is required when debug web routes are enabled."
            }
            require(webHostNames.isNotEmpty()) {
                "At least one debug web host name is required."
            }
        }
        val engine = embeddedServer(
            factory = CIO,
            host = BIND_ADDRESS,
            port = serverPort,
            module = {
                installDiagnosticRoutes(
                    token = token,
                    sdkInt = sdkIntProvider(),
                    startedAtMillis = startedAtMillis,
                    clockMillis = clockMillis,
                )
                webAssetProvider?.let { provider ->
                    installWebRoutes(
                        webAssetProvider = provider,
                        allowedHosts = webHostNames.mapTo(mutableSetOf()) { hostName ->
                            "$hostName:$serverPort"
                        },
                    )
                }
            },
        )

        engine.start(wait = false)
        return try {
            val connector = engine.engine.resolvedConnectors().single()
            KtorCioRuntime(
                stopServer = {
                    engine.stop(
                        gracePeriodMillis = 500,
                        timeoutMillis = STOP_TIMEOUT_MILLIS,
                    )
                },
                address = connector.host,
                port = connector.port,
            )
        } catch (error: Exception) {
            engine.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
            throw error
        }
    }

    private companion object {
        const val BIND_ADDRESS = "0.0.0.0"
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}

private class KtorCioRuntime(
    private val stopServer: () -> Unit,
    override val address: String,
    override val port: Int,
) : DiagnosticServerRuntime {

    override suspend fun stop() {
        withContext(NonCancellable) {
            stopServer()
        }
    }
}
