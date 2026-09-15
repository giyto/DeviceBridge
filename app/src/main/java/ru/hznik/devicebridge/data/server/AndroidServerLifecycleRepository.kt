package ru.hznik.devicebridge.data.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import ru.hznik.devicebridge.domain.model.ServerLifecycleState
import ru.hznik.devicebridge.domain.model.ServerStopReason
import ru.hznik.devicebridge.domain.repository.ServerLifecycleRepository
import ru.hznik.devicebridge.server.ServerForegroundService

interface ServerServiceCommandGateway {
    fun requestStart()
    fun requestStop()
}

@Singleton
class AndroidServerServiceCommandGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ServerServiceCommandGateway {
    override fun requestStart() {
        ContextCompat.startForegroundService(
            context,
            serviceIntent(ServerForegroundService.ACTION_START),
        )
    }

    override fun requestStop() {
        context.startService(serviceIntent(ServerForegroundService.ACTION_STOP))
    }

    private fun serviceIntent(action: String): Intent =
        Intent(context, ServerForegroundService::class.java).setAction(action)
}

class AndroidServerLifecycleRepository(
    override val state: StateFlow<ServerLifecycleState>,
    private val serviceCommands: ServerServiceCommandGateway,
) : ServerLifecycleRepository {
    override suspend fun start() = serviceCommands.requestStart()

    override suspend fun stop(reason: ServerStopReason) =
        serviceCommands.requestStop()
}
