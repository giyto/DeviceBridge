package ru.hznik.devicebridge.domain.file

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferArchitectureTest {

    @Test
    fun fileDomainAndContractsDoNotDependOnAndroidKtorComposeOrServices() {
        val roots = listOf(
            Path.of("src/main/java/ru/hznik/devicebridge/domain/file"),
            Path.of("src/main/java/ru/hznik/devicebridge/domain/repository/FileTransferRepository.kt"),
            Path.of("src/main/java/ru/hznik/devicebridge/domain/usecase/FileTransferUseCases.kt"),
        )
        val forbidden = Regex(
            """(?m)(^import (android\.|androidx\.|io\.ktor\.|dagger\.|javax\.inject\.)|ContentResolver|android\.app\.Service)""",
        )
        val violations = roots.flatMap(::kotlinSources).filter { source ->
            forbidden.containsMatchIn(Files.readString(source))
        }

        assertTrue("File-domain infrastructure violations: $violations", violations.isEmpty())
    }

    private fun kotlinSources(path: Path): List<Path> =
        if (Files.isDirectory(path)) {
            Files.walk(path).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
            }
        } else {
            listOf(path)
        }
}
