package ru.hznik.devicebridge.data.file

import ru.hznik.devicebridge.domain.file.FileTransferState

enum class FileRetrySourceValidation {
    VALID,
    UNAVAILABLE,
    CHANGED,
}

fun interface FileRetrySourceValidator {
    suspend fun validate(item: FileTransferState): FileRetrySourceValidation

    companion object {
        fun alwaysValid(): FileRetrySourceValidator =
            FileRetrySourceValidator { FileRetrySourceValidation.VALID }
    }
}
