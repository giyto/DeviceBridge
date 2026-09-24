package ru.hznik.devicebridge.data.file

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.hznik.devicebridge.di.ApplicationScope

/** Drops expired partial uploads when the app or the server starts. */
@Singleton
class PartialUploadCleanup(
    private val store: PartialUploadStore,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long,
) {
    @Inject
    constructor(
        store: PartialUploadStore,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        store = store,
        scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
        nowEpochMillis = System::currentTimeMillis,
    )

    fun run(): Job = scope.launch {
        runCatching { store.cleanup(nowEpochMillis()) }
    }
}
