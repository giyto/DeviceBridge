package ru.hznik.devicebridge.web

import android.content.res.AssetManager
import java.io.IOException
import org.json.JSONObject

class AssetManagerWebAssetProvider(
    assetManager: AssetManager,
) : WebAssetProvider {

    private val delegate = AllowlistedWebAssetProvider(
        allowedPaths = readAllowedPaths(assetManager),
        source = AssetManagerWebAssetSource(assetManager),
    )

    override fun find(requestPath: String): WebAssetResource? = delegate.find(requestPath)

    private companion object {
        const val WEB_ROOT = "web"

        fun readAllowedPaths(assetManager: AssetManager): Set<String> {
            val paths = mutableSetOf(
                "index.html",
                "setup.html",
                "asset-manifest.json",
                "web-manifest.json",
            )
            val manifest = JSONObject(
                assetManager.open("$WEB_ROOT/asset-manifest.json", AssetManager.ACCESS_STREAMING)
                    .bufferedReader()
                    .use { it.readText() },
            )
            val entryNames = manifest.keys()
            while (entryNames.hasNext()) {
                val entry = manifest.getJSONObject(entryNames.next())
                paths += entry.getString("file")
                for (arrayName in listOf("css", "assets")) {
                    val values = entry.optJSONArray(arrayName) ?: continue
                    for (index in 0 until values.length()) {
                        paths += values.getString(index)
                    }
                }
            }
            return paths
        }
    }
}

private class AssetManagerWebAssetSource(
    private val assetManager: AssetManager,
) : WebAssetSource {

    override fun describe(path: String): WebAssetDescriptor? {
        val assetPath = "web/$path"
        val length = try {
            assetManager.open(assetPath, AssetManager.ACCESS_STREAMING).use(::countBytes)
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

        return WebAssetDescriptor(
            length = length,
            openStream = {
                assetManager.open(assetPath, AssetManager.ACCESS_STREAMING)
            },
        )
    }

    private fun countBytes(input: java.io.InputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
        }
    }
}
