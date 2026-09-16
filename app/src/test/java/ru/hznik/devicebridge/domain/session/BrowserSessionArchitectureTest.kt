package ru.hznik.devicebridge.domain.session

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionArchitectureTest {

    @Test
    fun productionDomainHasNoAndroidKtorComposeOrInjectionImports() {
        val domainRoot = Path.of("src/main/java/ru/hznik/devicebridge/domain")
        val forbiddenImports = Regex(
            """(?m)^import (android\.|androidx\.|io\.ktor\.|dagger\.|javax\.inject\.)""",
        )

        val violations = kotlinSources(domainRoot).filter { source ->
            forbiddenImports.containsMatchIn(Files.readString(source))
        }

        assertTrue("Domain import violations: $violations", violations.isEmpty())
    }

    @Test
    fun homeViewModelDoesNotOwnServerCryptoOrStorageInfrastructure() {
        val viewModel = Files.readString(
            Path.of("src/main/java/ru/hznik/devicebridge/feature/home/HomeViewModel.kt"),
        )
        val forbidden = listOf(
            "io.ktor.",
            "ServerForegroundService",
            "ServerLifecycleCoordinator",
            "java.security.",
            "SecureRandom",
            "SharedPreferences",
            "DataStore",
            "android.app.Service",
        )

        val violations = forbidden.filter(viewModel::contains)

        assertTrue("HomeViewModel infrastructure violations: $violations", violations.isEmpty())
    }

    private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths
            .filter { Files.isRegularFile(it) && it.extension == "kt" }
            .toList()
    }
}
