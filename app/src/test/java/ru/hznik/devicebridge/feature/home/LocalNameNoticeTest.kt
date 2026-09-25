package ru.hznik.devicebridge.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.model.LocalNameStatus
import ru.hznik.devicebridge.domain.model.ServerEndpoint

class LocalNameNoticeTest {

    @Test
    fun ownNameOrNoMdnsAtAllNeedNoExplanation() {
        assertNull(named("devicebridge.local", LocalNameStatus.Claimed("devicebridge", false)).localNameNotice())
        assertNull(ServerEndpoint("192.168.1.24", 8787).localNameNotice())
    }

    @Test
    fun takenNameExplainsTheSuffixAndLeadsToSettings() {
        val notice = named("devicebridge-2.local", LocalNameStatus.Claimed("devicebridge", true)).localNameNotice()!!

        assertTrue(notice.text, notice.text.contains("«devicebridge» уже занято"))
        assertTrue(notice.text.contains("«devicebridge-2»"))
        assertTrue(notice.opensSettings)
    }

    @Test
    fun everyUnavailableReasonHasItsOwnExplanation() {
        val texts = LocalNameStatus.Reason.entries.associateWith { reason ->
            unnamed(LocalNameStatus.Unavailable(reason, "nikita")).localNameNotice()!!
        }

        assertTrue(texts.getValue(LocalNameStatus.Reason.TAKEN).text.contains("заняты другими устройствами"))
        assertTrue(texts.getValue(LocalNameStatus.Reason.CONFLICT).text.contains("начало отвечать на имя «nikita»"))
        assertTrue(texts.getValue(LocalNameStatus.Reason.NETWORK).text.contains("не пропускают"))
        assertFalse(texts.getValue(LocalNameStatus.Reason.NETWORK).opensSettings)
        assertTrue(texts.getValue(LocalNameStatus.Reason.CERTIFICATE).text.contains("сбросьте сертификат"))
        assertTrue(texts.getValue(LocalNameStatus.Reason.CERTIFICATE).text.contains("nikita.local"))
        assertEquals(4, texts.values.map { it.text }.distinct().size)
        texts.values.forEach { assertFalse(it.text, it.text.contains("\u2014")) }
    }

    private fun named(name: String, status: LocalNameStatus) =
        ServerEndpoint("192.168.1.24", 8787, localName = name, nameStatus = status)

    private fun unnamed(status: LocalNameStatus) = ServerEndpoint("192.168.1.24", 8787, nameStatus = status)
}
