package ru.hznik.devicebridge.data.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPrivacyRulesTest {

    @Test
    fun roomSettingsTrustedMaterialAndStagedSourcesStayOutOfBackup() {
        val legacy = read("src/main/res/xml/backup_rules.xml")
        val extraction = read("src/main/res/xml/data_extraction_rules.xml")
        val preparer = read(
            "src/main/java/ru/hznik/devicebridge/feature/file/AndroidFileSelectionPreparer.kt",
        )

        assertTrue(legacy.contains("domain=\"database\"") && legacy.contains("path=\".\""))
        assertTrue(legacy.contains("datastore/devicebridge_settings.preferences_pb"))
        assertTrue(extraction.split("domain=\"database\"").size - 1 >= 2)
        assertTrue(
            extraction.split("datastore/devicebridge_settings.preferences_pb").size - 1 >= 2,
        )
        assertTrue(preparer.contains("context.noBackupFilesDir"))
        assertTrue(preparer.contains("file-sources"))
    }

    private fun read(path: String): String = Files.readString(Path.of(path))
}
