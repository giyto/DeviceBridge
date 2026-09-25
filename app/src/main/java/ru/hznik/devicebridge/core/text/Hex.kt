package ru.hznik.devicebridge.core.text

import java.security.MessageDigest

/** Lowercase hexadecimal, two digits per byte. */
internal fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    this@toLowerHex.forEach { byte ->
        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0F])
        append(HEX_DIGITS[byte.toInt() and 0x0F])
    }
}

/** SHA-256 of the string's UTF-8 bytes as lowercase hexadecimal. */
internal fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).toLowerHex()

private const val HEX_DIGITS = "0123456789abcdef"
