package ru.hznik.devicebridge.diagnostics.server

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTokenContractTest {

    @Test
    fun secureGeneratorProducesUniqueUrlSafeTokensAndLifecycleExposesTokenSeam() {
        val result = runCatching {
            val generatorClass = Class.forName(
                "ru.hznik.devicebridge.diagnostics.server.SecureDiagnosticTokenGenerator",
                false,
                javaClass.classLoader,
            )
            val generator = generatorClass.getDeclaredConstructor().newInstance()
            val generate = generatorClass.getMethod("generate")
            val tokens = List(100) { generate.invoke(generator) as String }

            assertTrue(tokens.all { it.length >= 43 })
            assertTrue(tokens.all { it.matches(Regex("[A-Za-z0-9_-]+")) })
            assertTrue(tokens.toSet().size == tokens.size)

            val runningClass = Class.forName(
                "ru.hznik.devicebridge.diagnostics.server.ServerState\$Running",
                false,
                javaClass.classLoader,
            )
            assertTrue(runningClass.methods.any { it.name == "getToken" })

            val controllerClass = Class.forName(
                "ru.hznik.devicebridge.diagnostics.server.ManagedEmbeddedServerController",
                false,
                javaClass.classLoader,
            )
            assertTrue(
                controllerClass.constructors.any { constructor ->
                    constructor.parameterTypes.any {
                        it.simpleName == "DiagnosticTokenGenerator"
                    }
                },
            )
        }

        assertTrue(
            result.exceptionOrNull()?.stackTraceToString() ?: "Token contract failed",
            result.isSuccess,
        )
    }
}
