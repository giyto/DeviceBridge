package ru.hznik.devicebridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.file.FileTransferDirection
import ru.hznik.devicebridge.domain.file.FileTransferId
import ru.hznik.devicebridge.domain.session.BrowserSessionId
import ru.hznik.devicebridge.domain.session.PairingRequestId
import ru.hznik.devicebridge.domain.text.TextMessageId

class EventNotificationModelTest {

    private val factory = EventNotificationModelFactory()
    private val session = BrowserSessionId("session-1")

    @Test
    fun pairingRequestOnlyOpensTheAppAndHasNoSecrets() {
        val model = factory.create(
            EventNotice.PairingRequest(PairingRequestId("request-1"), "Chrome, Windows", 45_000),
        )

        assertEquals("Запрос подключения", model.title)
        assertTrue(model.text.startsWith("Chrome, Windows просит доступ"))
        assertEquals(listOf(EventNotificationAction.OPEN), model.actions)
        assertEquals(EventNotificationTarget.HOME, model.target)
        assertEquals(45_000L, model.timeoutMs)
        assertEquals("Запрос подключения", model.publicTitle)
        assertFalse(model.text.contains("request-1"))
        assertFalse(model.text.contains("token", ignoreCase = true))
    }

    @Test
    fun incomingFilesWithAFolderCanBeAcceptedOrDeclined() {
        val model = factory.create(files(count = 3, acceptable = true))

        assertEquals("3 файла с компьютера", model.title)
        assertEquals("report.pdf и ещё 2 - 1${decimal()}5 МБ", model.text)
        assertEquals(
            listOf(EventNotificationAction.ACCEPT_FILES, EventNotificationAction.DECLINE_FILES),
            model.actions,
        )
        assertEquals("Файлы с компьютера", model.publicTitle)
        assertEquals(EventNotificationTarget.FILES, model.target)
    }

    @Test
    fun incomingFilesWithoutAFolderOnlyOpenTheFilesScreen() {
        val model = factory.create(files(count = 1, acceptable = false))

        assertEquals("1 файл с компьютера", model.title)
        assertTrue(model.text.endsWith("Откройте приложение, чтобы выбрать папку."))
        assertEquals(listOf(EventNotificationAction.OPEN), model.actions)
    }

    @Test
    fun incomingFileNamesAreSafe() {
        val model = factory.create(
            EventNotice.IncomingFiles(
                sessionId = session,
                transferIds = listOf(FileTransferId("t1")),
                firstName = "..\\secret/‮exe.txt\u0007",
                totalBytes = 10,
                acceptable = true,
            ),
        )

        assertTrue(model.text.startsWith("exe.txt - "))
        assertFalse(model.text.contains('‮'))
    }

    @Test
    fun textCanBeCopiedAndLinksOpened() {
        val text = factory.create(
            EventNotice.IncomingText(session, TextMessageId("m1"), "Привет\n<b>мир</b>", false),
        )
        val link = factory.create(
            EventNotice.IncomingText(session, TextMessageId("m2"), "https://example.com/a", true),
        )

        assertEquals("Текст с компьютера", text.title)
        assertEquals("Привет\n<b>мир</b>", text.text)
        assertEquals(listOf(EventNotificationAction.COPY_TEXT), text.actions)
        assertNull(text.link)
        assertEquals("Текст с компьютера", text.publicTitle)
        assertEquals("Ссылка с компьютера", link.title)
        assertEquals(
            listOf(EventNotificationAction.COPY_TEXT, EventNotificationAction.OPEN_LINK),
            link.actions,
        )
        assertEquals("https://example.com/a", link.link)
        assertEquals("Текст с компьютера", link.publicTitle)
    }

    @Test
    fun longTextIsShortenedWithoutBidiOverrides() {
        val model = factory.create(
            EventNotice.IncomingText(session, TextMessageId("m1"), "‮" + "а".repeat(900), false),
        )

        assertEquals(501, model.text.length)
        assertTrue(model.text.endsWith("…"))
        assertFalse(model.text.contains('‮'))
    }

    @Test
    fun resultsUseRussianPluralsAndCountFailures() {
        fun result(direction: FileTransferDirection, completed: Int, failed: Int = 0, cancelled: Int = 0) =
            factory.create(EventNotice.TransferResult(session, direction, completed, failed, cancelled))

        val incoming = FileTransferDirection.BROWSER_TO_ANDROID
        val outgoing = FileTransferDirection.ANDROID_TO_BROWSER
        assertEquals("Сохранено 3 файла", result(incoming, 3).text)
        assertEquals("Файлы сохранены", result(incoming, 3).title)
        assertEquals("Сохранён 1 файл", result(incoming, 1).text)
        assertEquals("Сохранено 11 файлов", result(incoming, 11).text)
        assertEquals("Сохранён 21 файл", result(incoming, 21).text)
        assertEquals("Сохранено 2 из 3, 1 не удалось", result(incoming, 2, failed = 1).text)
        assertEquals("Передача прервалась", result(incoming, 2, failed = 1).title)
        assertEquals("Сохранено 2 из 3, 1 отменено", result(incoming, 2, cancelled = 1).text)
        assertEquals("Передано 5 файлов на компьютер", result(outgoing, 5).text)
        assertEquals("Файлы переданы", result(outgoing, 5).title)
        assertEquals(listOf(EventNotificationAction.SHOW_FILES), result(outgoing, 5).actions)
        assertEquals("Передача завершена", result(outgoing, 5).publicTitle)
    }

    private fun files(count: Int, acceptable: Boolean) = EventNotice.IncomingFiles(
        sessionId = session,
        transferIds = List(count) { FileTransferId("t$it") },
        firstName = "report.pdf",
        totalBytes = 1_572_864,
        acceptable = acceptable,
    )

    private fun decimal(): Char = "%.1f".format(1.5)[1]
}
