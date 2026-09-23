package ru.hznik.devicebridge.domain.file

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Transfers accepted automatically and offers whose automatic acceptance is paused. */
interface AutoAcceptStatusSource {
    val autoAccepted: StateFlow<Set<FileTransferId>>
    val paused: StateFlow<Set<FileTransferId>>

    object None : AutoAcceptStatusSource {
        override val autoAccepted: StateFlow<Set<FileTransferId>> = MutableStateFlow(emptySet())
        override val paused: StateFlow<Set<FileTransferId>> = MutableStateFlow(emptySet())
    }
}
