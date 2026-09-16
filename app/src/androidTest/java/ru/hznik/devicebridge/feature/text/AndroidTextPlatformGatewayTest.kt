package ru.hznik.devicebridge.feature.text

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTextPlatformGatewayTest {
    @Test
    fun constructingGatewayDoesNotReadClipboardAndExplicitReadDoes() = runBlocking {
        val context = RecordingContext(
            ApplicationProvider.getApplicationContext(),
        )

        val gateway = AndroidTextPlatformGateway(context)

        assertEquals(0, context.clipboardServiceRequests)
        gateway.readClipboardText()
        assertEquals(1, context.clipboardServiceRequests)
    }

    @Test
    fun onlyExplicitlyOpenedCanonicalHttpLinksCreateActionView() {
        val context = RecordingContext(
            ApplicationProvider.getApplicationContext(),
        )
        val gateway = AndroidTextPlatformGateway(context)

        assertNull(context.startedIntent)
        assertEquals(false, gateway.openHttpLink("javascript:alert(1)"))
        assertEquals(false, gateway.openHttpLink("data:text/plain,hello"))
        assertEquals(false, gateway.openHttpLink("file:///tmp/demo"))
        assertEquals(false, gateway.openHttpLink("ordinary text"))
        assertNull(context.startedIntent)

        assertEquals(true, gateway.openHttpLink("https://example.com/path?q=1"))
        val started = context.startedIntent
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("https", started?.data?.scheme)
        assertEquals("example.com", started?.data?.host)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var clipboardServiceRequests: Int = 0
        var startedIntent: Intent? = null

        override fun getSystemService(name: String): Any? {
            if (name == Context.CLIPBOARD_SERVICE) {
                clipboardServiceRequests += 1
            }
            return super.getSystemService(name)
        }

        override fun startActivity(intent: Intent) {
            startedIntent = intent
        }
    }
}
