package ru.hznik.devicebridge.domain.file

sealed interface FileTransferEvent {
    data object Connecting : FileTransferEvent
    data object Started : FileTransferEvent

    data class Progressed(
        val bytesTransferred: Long,
        val speedBytesPerSecond: Long,
    ) : FileTransferEvent

    data object Verifying : FileTransferEvent
    data object Completed : FileTransferEvent
    data object Cancelled : FileTransferEvent
    data class Failed(val failure: FileTransferFailure) : FileTransferEvent
}

object FileTransferReducer {

    fun reduce(
        current: FileTransferState,
        event: FileTransferEvent,
    ): FileTransferState {
        if (current.phase.isTerminal) return current

        return when (event) {
            FileTransferEvent.Connecting ->
                current.transition(FileTransferPhase.QUEUED, FileTransferPhase.CONNECTING)

            FileTransferEvent.Started ->
                current.transition(FileTransferPhase.CONNECTING, FileTransferPhase.TRANSFERRING)

            is FileTransferEvent.Progressed ->
                if (
                    current.phase == FileTransferPhase.TRANSFERRING &&
                    event.bytesTransferred in current.bytesTransferred..current.metadata.sizeBytes &&
                    event.speedBytesPerSecond >= 0
                ) {
                    current.evolve(
                        bytesTransferred = event.bytesTransferred,
                        speedBytesPerSecond = event.speedBytesPerSecond,
                    )
                } else {
                    current
                }

            FileTransferEvent.Verifying ->
                if (
                    current.phase == FileTransferPhase.TRANSFERRING &&
                    current.bytesTransferred == current.metadata.sizeBytes
                ) {
                    current.evolve(
                        phase = FileTransferPhase.VERIFYING,
                        speedBytesPerSecond = 0,
                    )
                } else {
                    current
                }

            FileTransferEvent.Completed ->
                current.transition(FileTransferPhase.VERIFYING, FileTransferPhase.COMPLETED)

            FileTransferEvent.Cancelled ->
                current.evolve(
                    phase = FileTransferPhase.CANCELLED,
                    speedBytesPerSecond = 0,
                )

            is FileTransferEvent.Failed ->
                current.evolve(
                    phase = FileTransferPhase.FAILED,
                    speedBytesPerSecond = 0,
                    failure = event.failure,
                )
        }
    }

    private fun FileTransferState.transition(
        from: FileTransferPhase,
        to: FileTransferPhase,
    ): FileTransferState =
        if (phase == from) {
            evolve(phase = to, speedBytesPerSecond = 0)
        } else {
            this
        }
}
