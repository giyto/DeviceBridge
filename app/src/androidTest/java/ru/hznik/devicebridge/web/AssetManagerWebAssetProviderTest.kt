package ru.hznik.devicebridge.web

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetManagerWebAssetProviderTest {

    @Test
    fun readsIndexManifestsAndEveryDeclaredAssetFromApk() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = AssetManagerWebAssetProvider(context.assets)

        val index = requireNotNull(provider.find("/"))
        assertTrue(index.openStream().bufferedReader().use { it.readText() }.contains("DeviceBridge"))

        val viteManifestAsset = requireNotNull(provider.find("/asset-manifest.json"))
        val viteManifest = JSONObject(
            viteManifestAsset.openStream().bufferedReader().use { it.readText() },
        )
        val declaredPaths = mutableSetOf<String>()
        val entries = viteManifest.keys()
        while (entries.hasNext()) {
            val entry = viteManifest.getJSONObject(entries.next())
            declaredPaths += entry.getString("file")
            for (arrayName in listOf("css", "assets")) {
                val array = entry.optJSONArray(arrayName) ?: continue
                for (indexInArray in 0 until array.length()) {
                    declaredPaths += array.getString(indexInArray)
                }
            }
        }

        assertTrue(declaredPaths.isNotEmpty())
        declaredPaths.forEach { path ->
            val asset = provider.find("/$path")
            assertNotNull(path, asset)
            requireNotNull(asset)
            assertTrue(path, asset.length > 0L)
            assertEquals(path, asset.length, asset.openStream().use { it.readBytes() }.size.toLong())
        }

        val webManifest = JSONObject(
            requireNotNull(provider.find("/web-manifest.json"))
                .openStream()
                .bufferedReader()
                .use { it.readText() },
        )
        assertEquals(setOf("protocolVersion", "webAssetVersion"), webManifest.keys().asSequence().toSet())
        assertEquals(1, webManifest.getInt("protocolVersion"))
        assertTrue(webManifest.getString("webAssetVersion").matches(Regex("^sha256-[a-f0-9]{16}$")))
    }
}
