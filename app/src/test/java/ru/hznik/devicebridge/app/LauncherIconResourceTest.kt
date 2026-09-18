package ru.hznik.devicebridge.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {

    @Test
    fun launcherUsesDeviceBridgeAdaptiveAndMonochromeResources() {
        val standard = read("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
        val round = read("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml")
        val foreground = read("src/main/res/drawable/ic_launcher_foreground.xml")
        val monochrome = read("src/main/res/drawable/ic_launcher_monochrome.xml")
        val manifest = read("src/main/AndroidManifest.xml")

        assertTrue(standard.contains("@drawable/ic_launcher_foreground"))
        assertTrue(standard.contains("@drawable/ic_launcher_monochrome"))
        assertTrue(round.contains("@drawable/ic_launcher_monochrome"))
        assertTrue(foreground.contains("devicebridge-link"))
        assertFalse(foreground.contains("3DDC84"))
        assertFalse(foreground.contains("65.3,45.828"))
        assertTrue(monochrome.contains("devicebridge-monochrome"))
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
    }

    @Test
    fun templateRasterLaunchersAreNotKeptAsFallbacks() {
        val res = Path.of("src/main/res")
        val templateRasters = Files.walk(res).use { paths ->
            paths.filter { path ->
                path.fileName.toString() == "ic_launcher.webp" ||
                    path.fileName.toString() == "ic_launcher_round.webp"
            }.toList()
        }

        assertTrue(templateRasters.isEmpty())
    }

    private fun read(path: String): String = Files.readString(Path.of(path))
}
