package ru.hznik.devicebridge.data.tls

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class TlsFingerprintsTest {

    @Test
    fun matchesFingerprintsComputedByOpenSsl() {
        val fingerprints = CertificateFingerprints.of(Base64.getDecoder().decode(REFERENCE_DER))

        assertEquals("E101A32A C458EB0F 2CDF4C3D BEA241A6 69319FE1", fingerprints.sha1)
        assertEquals(
            "9F 5D 00 12 11 7F 5D B4 5E 1E A6 3D D2 D1 03 D9 " +
                "1B CD 44 B4 60 E9 FC E4 28 DA 4B C3 7E B4 01 B7",
            fingerprints.sha256,
        )
    }

    private companion object {
        /** `openssl req -x509 -newkey ec` output; fingerprints from `openssl x509 -fingerprint`. */
        const val REFERENCE_DER =
            "MIIBljCCATugAwIBAgIUHFix5XxZufwxERsYA3mBqJBN+ocwCgYIKoZIzj0EAwIwIDEeMBwGA1UEAwwVRmlu" +
                "Z2VycHJpbnQgcmVmZXJlbmNlMB4XDTI2MDkyNDA5MDA0NloXDTM2MDkyMTA5MDA0NlowIDEeMBwGA1UE" +
                "AwwVRmluZ2VycHJpbnQgcmVmZXJlbmNlMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExvdY5XD0yx80" +
                "/1NT+5kPRwxdmX1yaG9WUnQc/fjp1Ym5kU2DXHdbaJY0aTyx0c58h/2yoZSiqYEOLxMzcT8oRqNTMFEw" +
                "HQYDVR0OBBYEFM64BLPFNtfHrZ1Mw+30Slhj6O7kMB8GA1UdIwQYMBaAFM64BLPFNtfHrZ1Mw+30Slhj" +
                "6O7kMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIhAITBR+NGxYUH+b47gHAioCpM4U1U" +
                "T5KbcROMQIBEE9ZZAiEA/2HOjzcpAoanjLCTBaWqZ518zYO66OqSGnYWV6KncD4="
    }
}
