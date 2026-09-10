package com.freetime.lumastore.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class StoreApp(
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val version: String,
    val versionCode: Long,
    val iconUrl: String?,
    val apkUrl: String,
    val sourceName: String
)

data class AppSource(val name: String, val indexUrl: String)

class AppRepository {
    val sources = listOf(
        AppSource("Freetime F-Droid", "https://fdroid.free-time.me/repo/index-v2.json"),
        AppSource("F-Droid", "https://f-droid.org/repo/index-v2.json")
    )

    fun loadApps(): Result<List<StoreApp>> = runCatching {
        val merged = LinkedHashMap<String, StoreApp>()
        sources.forEach { source ->
            runCatching { loadSource(source) }.getOrDefault(emptyList()).forEach { app ->
                val current = merged[app.id]
                if (current == null || app.versionCode > current.versionCode) merged[app.id] = app
            }
        }
        merged.values.sortedBy { it.name.lowercase() }
    }

    private fun loadSource(source: AppSource): List<StoreApp> {
        val connection = URL(source.indexUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Luma-Store/1.0")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val packages = root.optJSONObject("packages") ?: return emptyList()
        val result = mutableListOf<StoreApp>()

        packages.keys().forEach { packageName ->
            val pkg = packages.optJSONObject(packageName) ?: return@forEach
            val metadata = pkg.optJSONObject("metadata") ?: JSONObject()
            val versions = pkg.optJSONObject("versions") ?: JSONObject()
            var bestVersion: JSONObject? = null
            var bestCode = Long.MIN_VALUE

            versions.keys().forEach { key ->
                val version = versions.optJSONObject(key) ?: return@forEach
                val code = version.optLong("manifest.versionCode", Long.MIN_VALUE)
                if (code > bestCode) {
                    bestCode = code
                    bestVersion = version
                }
            }

            val version = bestVersion ?: return@forEach
            val file = version.optJSONObject("file") ?: return@forEach
            val apkName = file.optString("name")
            if (apkName.isBlank()) return@forEach

            fun localized(obj: JSONObject?, fallback: String = ""): String {
                if (obj == null) return fallback
                return obj.optString("en-US").ifBlank { obj.optString("en").ifBlank {
                    obj.keys().asSequence().mapNotNull { obj.optString(it).takeIf(String::isNotBlank) }.firstOrNull() ?: fallback
                }}
            }

            val name = localized(metadata.optJSONObject("name"), packageName.substringAfterLast('.'))
            val summary = localized(metadata.optJSONObject("summary"))
            val description = localized(metadata.optJSONObject("description"), summary)
            val icon = metadata.optJSONObject("icon")?.let { iconObj ->
                localized(iconObj).takeIf { it.isNotBlank() }?.let { resolveUrl(source.indexUrl, it) }
            }
            val versionName = version.optString("manifest.versionName", bestCode.toString())
            result += StoreApp(
                id = packageName,
                name = name,
                summary = summary,
                description = description,
                version = versionName,
                versionCode = bestCode.coerceAtLeast(0),
                iconUrl = icon,
                apkUrl = resolveUrl(source.indexUrl, apkName),
                sourceName = source.name
            )
        }
        return result
    }

    private fun resolveUrl(indexUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = indexUrl.substringBeforeLast('/') + "/"
        return base + path.removePrefix("/")
    }
}
