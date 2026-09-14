package ru.hznik.devicebridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.diagnostics.server.ServerState

class DiagnosticsUiModelTest {

    @Test
    fun stoppedRunningAndErrorStatesMapToReadableUi() {
        val result = runCatching {
            val mapperClass = Class.forName(
                "ru.hznik.devicebridge.diagnostics.DiagnosticsUiModelKt",
                false,
                javaClass.classLoader,
            )
            val mapper = mapperClass.getMethod(
                "toDiagnosticsUiModel",
                ServerState::class.java,
            )

            val stopped = requireNotNull(mapper.invoke(null, ServerState.Stopped))
            assertEquals("Сервер остановлен", stopped.readString("getStatusLabel"))
            assertEquals("Запустить сервер", stopped.readString("getPrimaryActionLabel"))
            assertNull(stopped.readNullableString("getAddress"))
            assertNull(stopped.readNullableString("getToken"))

            val running = requireNotNull(
                mapper.invoke(
                    null,
                    ServerState.Running(
                        address = "192.168.1.10",
                        port = 87_87,
                        token = "secret-token",
                    ),
                ),
            )
            assertEquals("Сервер запущен", running.readString("getStatusLabel"))
            assertEquals("Остановить сервер", running.readString("getPrimaryActionLabel"))
            assertEquals("http://192.168.1.10:8787", running.readString("getAddress"))
            assertEquals("secret-token", running.readString("getToken"))

            val error = requireNotNull(
                mapper.invoke(null, ServerState.Error("Port is busy")),
            )
            assertEquals("Ошибка запуска", error.readString("getStatusLabel"))
            assertEquals("Повторить запуск", error.readString("getPrimaryActionLabel"))
            assertEquals("Port is busy", error.readString("getErrorMessage"))
        }

        assertTrue(
            result.exceptionOrNull()?.stackTraceToString() ?: "UI mapping failed",
            result.isSuccess,
        )
    }

    private fun Any.readString(getter: String): String {
        return javaClass.getMethod(getter).invoke(this) as String
    }

    private fun Any.readNullableString(getter: String): String? {
        return javaClass.getMethod(getter).invoke(this) as String?
    }
}
