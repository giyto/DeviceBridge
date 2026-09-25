package ru.hznik.devicebridge.core.text

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.feature.home.formatIdleMinutes
import ru.hznik.devicebridge.feature.settings.partialUploadCountLabel
import ru.hznik.devicebridge.server.ServerTilePolicy
import ru.hznik.devicebridge.server.countOfFiles

class RussianPluralTest {
    private val forms = mapOf(
        0 to "many",
        1 to "one",
        2 to "few",
        4 to "few",
        5 to "many",
        11 to "many",
        12 to "many",
        14 to "many",
        21 to "one",
        22 to "few",
        25 to "many",
        101 to "one",
        104 to "few",
        111 to "many",
    )

    @Test
    fun picksTheRussianFormForEveryCount() {
        forms.forEach { (count, form) ->
            assertEquals("count $count", form, ruPlural(count, "one", "few", "many"))
        }
    }

    @Test
    fun labelsUseTheSharedForms() {
        val files = mapOf("one" to "файл", "few" to "файла", "many" to "файлов")
        val browsers = mapOf("one" to "браузер", "few" to "браузера", "many" to "браузеров")
        val minutes = mapOf("one" to "минута", "few" to "минуты", "many" to "минут")
        forms.forEach { (count, form) ->
            assertEquals("$count ${files.getValue(form)}", countOfFiles(count))
            assertEquals("$count ${files.getValue(form)}", partialUploadCountLabel(count))
            assertEquals("$count ${browsers.getValue(form)}", ServerTilePolicy.formatBrowserCount(count))
            assertEquals("$count ${minutes.getValue(form)}", formatIdleMinutes(count))
        }
    }
}
