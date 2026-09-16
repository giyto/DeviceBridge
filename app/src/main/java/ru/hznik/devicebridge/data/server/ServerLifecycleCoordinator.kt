package ru.hznik.devicebridge.data.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.devicebridge.data.network.LanNetworkEvent
import ru.hznik.devicebridge.data.network.LanNetworkObserver
import ru.hznik.devicebridge.data.permission.PermissionRevocationObserver
import ru.hznik.devicebridge.data.permission.ServerPermissionGateway
import ru.hznik.devicebridge.data.permission.ServerPermissionSnapshot
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.ServerLifecycleEvent
import ru.hznik.devicebridge.domain.model.ServerLifecycleReducer
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository
import ru.hznik.devicebridge.di.ApplicationScope

@Singleton
class ServerLifecycleCoordinator @Inject constructor(
    private val runtimeFactory: ServerRuntimeFactory,
    private val monotonicClock: MonotonicClock,
    @param:ApplicationScope
    private val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val permissionRevocationObserver: PermissionRevocationObserver =
        NoOpPermissionRevocationObserver,
    private val lanNetworkObserver: LanNetworkObserver = NoOpLanNetworkObserver,
    private val stopTimeoutPolicy: StopTimeoutPolicy = StopTimeoutPolicy(),
    private val sessionJournal: ServerSessionJournal = NoOpServerSessionJournal,
    private val permissionGateway: ServerPermissionGateway = AlwaysGrantedPermissionGateway,
) : ServerLifecycleRepository {

    private val mutex = Mutex()
    private var generation: Long = 0
    private val mutableState = MutableStateFlow<ServerLifecycleState>(
        recoverInterruptedSession(),
    )
    private var activeRuntime: ServerRuntime? = null

    override val state: StateFlow<ServerLifecycleState> = mutableState.asStateFlow()

    override suspend fun start() {
        mutex.withLock {
            if (
                mutableState.value !is ServerLifecycleState.Stopped &&
                mutableState.value !is ServerLifecycleState.Error
            ) {
                return
            }

            val currentGeneration = ++generation
            mutableState.value = ServerLifecycleReducer.reduce(
                mutableState.value,
                ServerLifecycleEvent.StartRequested(currentGeneration),
            )

            var newRuntime: ServerRuntime? = null
            try {
                newRuntime = runtimeFactory.create()
                activeRuntime = newRuntime
                val endpoint = newRuntime.start()
                newRuntime.activateSessionGeneration(currentGeneration)
                mutableState.value = ServerLifecycleReducer.reduce(
                    mutableState.value,
                    ServerLifecycleEvent.Started(
                        generation = currentGeneration,
                        endpoint = endpoint,
                        startedAtElapsedRealtimeMs = monotonicClock.nowMs(),
                    ),
                )
                sessionJournal.markRunning()
                permissionRevocationObserver.start {
                    applicationScope.launch {
                        reportFailure(
                            generation = currentGeneration,
                            cause = ServerLifecycleError.PermissionRevoked,
                        )
                    }
                }
                observeNetwork(
                    generation = currentGeneration,
                    endpointHost = endpoint.host,
                    networkFingerprint = newRuntime.networkFingerprint,
                )
            } catch (throwable: Throwable) {
                permissionRevocationObserver.stop()
                lanNetworkObserver.stop()
                runCatching { newRuntime?.closeSessionGeneration() }
                runCatching { newRuntime?.stop() }
                activeRuntime = null
                sessionJournal.clear()
                mutableState.value = ServerLifecycleReducer.reduce(
                    mutableState.value,
                    ServerLifecycleEvent.Failed(
                        generation = currentGeneration,
                        cause = (throwable as? ServerRuntimeStartException)
                            ?.lifecycleError
                            ?: ServerLifecycleError.ServerStartFailed,
                    ),
                )
            }
        }
    }

    override suspend fun stop(reason: ServerStopReason) {
        mutex.withLock {
            val currentGeneration = mutableState.value.generationOrNull()
            if (currentGeneration == null) {
                if (mutableState.value is ServerLifecycleState.Error) {
                    mutableState.value = ServerLifecycleState.Stopped
                }
                sessionJournal.clear()
                return
            }

            mutableState.value = ServerLifecycleReducer.reduce(
                mutableState.value,
                ServerLifecycleEvent.StopRequested(currentGeneration),
            )
            permissionRevocationObserver.stop()
            lanNetworkObserver.stop()
            val runtime = activeRuntime
            activeRuntime = null
            try {
                runtime?.closeSessionGeneration()
                withTimeout(stopTimeoutPolicy.timeoutMillis) {
                    runtime?.stop()
                }
                mutableState.value = ServerLifecycleReducer.reduce(
                    mutableState.value,
                    ServerLifecycleEvent.Stopped(currentGeneration),
                )
                sessionJournal.clear()
            } catch (_: TimeoutCancellationException) {
                mutableState.value = ServerLifecycleReducer.reduce(
                    mutableState.value,
                    ServerLifecycleEvent.Failed(
                        generation = currentGeneration,
                        cause = ServerLifecycleError.StopTimedOut,
                    ),
                )
                sessionJournal.clear()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                mutableState.value = ServerLifecycleReducer.reduce(
                    mutableState.value,
                    ServerLifecycleEvent.Failed(
                        generation = currentGeneration,
                        cause = ServerLifecycleError.Unexpected(throwable.message),
                    ),
                )
                sessionJournal.clear()
            }
        }
    }

    suspend fun reportExternalStartFailure(cause: ServerLifecycleError) {
        mutex.withLock {
            if (
                mutableState.value !is ServerLifecycleState.Stopped &&
                mutableState.value !is ServerLifecycleState.Error
            ) {
                return
            }
            mutableState.value = ServerLifecycleState.Error(
                generation = ++generation,
                cause = cause,
            )
            sessionJournal.clear()
        }
    }

    suspend fun reportFailure(
        generation: Long,
        cause: ServerLifecycleError,
    ) {
        mutex.withLock {
            if (mutableState.value.generationOrNull() != generation) {
                return
            }
            mutableState.value = ServerLifecycleReducer.reduce(
                mutableState.value,
                ServerLifecycleEvent.Failed(generation, cause),
            )
            permissionRevocationObserver.stop()
            lanNetworkObserver.stop()
            val runtime = activeRuntime
            activeRuntime = null
            runCatching { runtime?.closeSessionGeneration() }
            runCatching { runtime?.stop() }
            if (cause != ServerLifecycleError.PermissionRevoked) {
                sessionJournal.clear()
            }
        }
    }

    private fun recoverInterruptedSession(): ServerLifecycleState {
        if (!sessionJournal.consumeInterruptedSession()) {
            return ServerLifecycleState.Stopped
        }
        val permissions = permissionGateway.snapshot()
        return if (
            permissions.sdkInt >= 37 &&
            !permissions.localNetworkGranted
        ) {
            ServerLifecycleState.Error(
                generation = ++generation,
                cause = ServerLifecycleError.PermissionRevoked,
            )
        } else {
            ServerLifecycleState.Stopped
        }
    }

    private fun observeNetwork(
        generation: Long,
        endpointHost: String,
        networkFingerprint: String?,
    ) {
        lanNetworkObserver.start { event ->
            val error = when (event) {
                is LanNetworkEvent.CapabilitiesChanged ->
                    if (event.isSuitableWifi) null else ServerLifecycleError.NetworkLost

                is LanNetworkEvent.LinkPropertiesChanged -> {
                    val currentFingerprint =
                        event.interfaceName?.let { it + "|" + endpointHost }
                    if (
                        endpointHost !in event.addresses ||
                        networkFingerprint != null &&
                        currentFingerprint != networkFingerprint
                    ) {
                        ServerLifecycleError.AddressChanged
                    } else {
                        null
                    }
                }

                LanNetworkEvent.Lost -> ServerLifecycleError.NetworkLost
            }

            if (error != null) {
                applicationScope.launch {
                    reportFailure(generation, error)
                }
            }
        }
    }

    private data object NoOpPermissionRevocationObserver :
        PermissionRevocationObserver {
        override fun start(onRevoked: () -> Unit) = Unit

        override fun stop() = Unit
    }

    private data object NoOpLanNetworkObserver : LanNetworkObserver {
        override fun start(onEvent: (LanNetworkEvent) -> Unit) = Unit

        override fun stop() = Unit
    }

    private data object NoOpServerSessionJournal : ServerSessionJournal {
        override fun consumeInterruptedSession(): Boolean = false

        override fun markRunning() = Unit

        override fun clear() = Unit
    }

    private data object AlwaysGrantedPermissionGateway : ServerPermissionGateway {
        override fun snapshot(localNetworkCanAskAgain: Boolean): ServerPermissionSnapshot =
            ServerPermissionSnapshot(
                sdkInt = 0,
                localNetworkGranted = true,
                notificationsGranted = true,
            )
    }

    private fun ServerLifecycleState.generationOrNull(): Long? = when (this) {
        ServerLifecycleState.Stopped -> null
        is ServerLifecycleState.Starting -> generation
        is ServerLifecycleState.Running -> generation
        is ServerLifecycleState.Stopping -> generation
        is ServerLifecycleState.Error -> generation
    }
}
