package ru.hznik.devicebridge.data.file

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.domain.file.FileTransferPhase

@RunWith(AndroidJUnit4::class)
class AndroidCompletedFileOpenerTest {

    @Test
    fun opensOnlyCompletedContentUriWithTemporaryReadGrant() {
        val viewer = FakeViewer()
        val opener = AndroidCompletedFileOpener(viewer)

        val result = opener.open(
            phase = FileTransferPhase.COMPLETED,
            contentUri = "content://provider/document/1",
            mimeType = "application/pdf",
        )

        assertEquals(CompletedFileOpenResult.Opened, result)
        assertEquals(Intent.ACTION_VIEW, viewer.opened?.action)
        assertEquals("content", viewer.opened?.data?.scheme)
        assertTrue(
            viewer.opened?.flags?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
    }

    @Test
    fun rejectsNonCompletedAndFileUrisWithoutLaunchingViewer() {
        val viewer = FakeViewer()
        val opener = AndroidCompletedFileOpener(viewer)

        assertEquals(
            CompletedFileOpenResult.NotCompleted,
            opener.open(
                FileTransferPhase.TRANSFERRING,
                "content://provider/document/1",
                "application/pdf",
            ),
        )
        assertEquals(
            CompletedFileOpenResult.UnsafeUri,
            opener.open(
                FileTransferPhase.COMPLETED,
                "file:///private/result.pdf",
                "application/pdf",
            ),
        )
        assertNull(viewer.opened)
    }

    @Test
    fun reportsMissingViewerWithoutThrowing() {
        val viewer = FakeViewer(available = false)
        val opener = AndroidCompletedFileOpener(viewer)

        val result = opener.open(
            FileTransferPhase.COMPLETED,
            "content://provider/document/1",
            "application/x-unknown",
        )

        assertEquals(CompletedFileOpenResult.NoViewer, result)
        assertNull(viewer.opened)
    }

    private class FakeViewer(
        private val available: Boolean = true,
    ) : ExternalFileViewerGateway {
        var opened: Intent? = null

        override fun canOpen(intent: Intent): Boolean = available

        override fun open(intent: Intent) {
            opened = intent
        }
    }
}
