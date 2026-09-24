package ru.hznik.devicebridge.domain.session

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.domain.repository.BrowserSessionRepository

class BrowserSessionRepositoryContractTest {

    @Test
    fun repositoryContractCannotExposeRawTokenToAndroidUi() {
        val source = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/domain/repository/BrowserSessionRepository.kt"),
        )

        assertFalse(source.contains("token", ignoreCase = true))
        assertTrue(source.contains("PairingRequestId"))
        assertTrue(source.contains("BrowserSessionId"))
        assertEquals(
            StateFlow::class.java,
            BrowserSessionRepository::class.java.declaredMethods
                .single { it.name == "getState" }
                .returnType,
        )
    }
}
