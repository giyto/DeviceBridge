package ru.hznik.devicebridge.data.file

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.hznik.devicebridge.domain.file.FileTransferId

/**
 * Incoming files the person accepted with "Принять" in a notification. They are accepted into
 * the default folder the same way as a trusted browser's files, each once it is its turn.
 */
@Singleton
class NotificationAcceptedTransfers @Inject constructor() {
    private val mutableIds = MutableStateFlow<Set<FileTransferId>>(emptySet())
    val ids: StateFlow<Set<FileTransferId>> = mutableIds.asStateFlow()

    fun accept(transferIds: Collection<FileTransferId>) = mutableIds.update { it + transferIds }

    /** The decision is used once: a later retry of the same file is offered again. */
    fun consume(transferId: FileTransferId) = mutableIds.update { it - transferId }

    fun clear() {
        mutableIds.value = emptySet()
    }
}
