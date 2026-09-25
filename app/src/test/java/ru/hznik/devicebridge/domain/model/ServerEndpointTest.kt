package ru.hznik.devicebridge.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerEndpointTest {

    @Test
    fun addressByNameIsTheOnlyAddress() {
        val endpoint = ServerEndpoint(
            host = "192.168.1.37",
            port = 8787,
            localName = "devicebridge.local",
            nameStatus = LocalNameStatus.Claimed("devicebridge", requestedTaken = false),
        )

        assertEquals("http://devicebridge.local:8787", endpoint.url)
        assertEquals("192.168.1.37:8787", endpoint.ipAuthority)
        assertEquals(setOf("devicebridge.local:8787"), endpoint.authorities)
    }

    @Test
    fun withoutANameEverythingIsByIp() {
        val endpoint = ServerEndpoint("192.168.1.37", 8787)

        assertEquals("http://192.168.1.37:8787", endpoint.url)
        assertEquals(setOf("192.168.1.37:8787"), endpoint.authorities)
    }

    @Test
    fun losingTheNameKeepsTheRequestedLabelForTheExplanation() {
        val lost = ServerEndpoint(
            host = "192.168.1.37",
            port = 8787,
            localName = "nikita-2.local",
            nameStatus = LocalNameStatus.Claimed("nikita", requestedTaken = true),
        ).withNameLost()

        assertEquals(null, lost.localName)
        assertEquals(LocalNameStatus.Unavailable(LocalNameStatus.Reason.CONFLICT, "nikita"), lost.nameStatus)
        assertEquals(setOf("192.168.1.37:8787"), lost.authorities)
    }

    @Test
    fun nameAndStatusMustAgree() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint("192.168.1.37", 8787, localName = "devicebridge.local")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEndpoint("192.168.1.37", 8787, nameStatus = LocalNameStatus.Claimed("devicebridge", false))
        }
    }
}
