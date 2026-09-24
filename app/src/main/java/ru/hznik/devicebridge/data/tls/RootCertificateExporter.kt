package ru.hznik.devicebridge.data.tls

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hands the phone's root certificate to another app, so it can reach a computer without Wi-Fi. */
fun interface RootCertificateExporter {
    /** A `content://` address of `DeviceBridge-CA.crt`, or `null` when there is no usable root. */
    suspend fun export(): String?
}

/**
 * Writes the root's DER to the cache and serves it through [FileProvider]. The file holds only
 * the certificate; its key stays in AndroidKeyStore.
 */
class FileProviderRootCertificateExporter(
    private val context: Context,
    private val authority: LocalCertificateAuthority,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RootCertificateExporter {

    override suspend fun export(): String? = withContext(io) {
        val root = runCatching { authority.rootOrNull() }.getOrNull() ?: return@withContext null
        val directory = File(context.cacheDir, SHARED_DIRECTORY).apply { mkdirs() }
        val file = File(directory, FILE_NAME)
        file.writeBytes(root.encoded)
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file).toString()
    }

    companion object {
        const val FILE_NAME = "DeviceBridge-CA.crt"
        const val MIME_TYPE = "application/x-x509-ca-cert"
        private const val SHARED_DIRECTORY = "shared-certificate"
        private const val AUTHORITY_SUFFIX = ".certificates"
    }
}
