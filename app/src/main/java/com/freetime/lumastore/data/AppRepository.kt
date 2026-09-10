package com.freetime.lumastore.data

import org.json.JSONArray
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
    val screenshotUrls: List<String>,
    val categories: List<String>,
    val apkUrl: String,
    val sourceName: String
)

data class AppSource(val name: String, val indexUrl: String)

class AppRepository {
    val sources = listOf(
        AppSource("Freetime F-Droid Repository", "https://fdroid.free-time.me/repo/index-v1.json")
    )

    fun loadApps(): Result<List<StoreApp>> = runCatching {
        val merged = LinkedHashMap<String, StoreApp>()
        var successfulSources = 0

        sources.forEach { source ->
            runCatching { loadSource(source) }
                .onSuccess { apps ->
                    successfulSources++
                    apps.forEach { app ->
                        val current = merged[app.id]
                        if (current == null || app.versionCode > current.versionCode) {
                            merged[app.id] = app
                        }
                    }
                }
        }

        check(successfulSources > 0) { "Keine App-Quelle konnte geladen werden." }
        merged.values.sortedBy { it.name.lowercase() }
    }

    private fun loadSource(source: AppSource): List<StoreApp> {
        val connection = URL(source.indexUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("User-Agent", "Luma-Store/1.0")
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val metadataByPackage = mutableMapOf<String, JSONObject>()
            val apps = root.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until apps.length()) {
                val metadata = apps.optJSONObject(i) ?: continue
                val packageName = metadata.optString("packageName")
                if (packageName.isNotBlank()) metadataByPackage[packageName] = metadata
            }

            val packages = root.optJSONObject("packages") ?: return emptyList()
            val result = mutableListOf<StoreApp>()

            packages.keys().forEach { packageName ->
                val versions = packages.optJSONArray(packageName) ?: return@forEach
                var best: JSONObject? = null
                var bestCode = Long.MIN_VALUE
                for (i in 0 until versions.length()) {
                    val candidate = versions.optJSONObject(i) ?: continue
                    val code = candidate.optLong("versionCode", Long.MIN_VALUE)
                    if (code > bestCode) {
                        bestCode = code
                        best = candidate
                    }
                }

                val version = best ?: return@forEach
                val apkName = version.optString("apkName")
                if (apkName.isBlank()) return@forEach

                val metadata = metadataByPackage[packageName] ?: JSONObject()
                val localizedPair = preferredLocalized(metadata)
                val locale = localizedPair?.first
                val localized = localizedPair?.second

                val name = localized?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: metadata.optString("name").ifBlank { packageName.substringAfterLast('.') }
                val summary = localized?.optString("summary")
                    ?.takeIf { it.isNotBlank() }
                    ?: metadata.optString("summary")
                val description = localized?.optString("description")
                    ?.takeIf { it.isNotBlank() }
                    ?: metadata.optString("description").ifBlank { summary }
                val categories = jsonStrings(metadata.optJSONArray("categories"))

                val localizedIcon = localized?.optString("icon")?.takeIf { it.isNotBlank() }
                val legacyIcon = metadata.optString("icon").takeIf { it.isNotBlank() }
                val iconUrl = when {
                    localizedIcon != null && locale != null ->
                        resolveUrl(source.indexUrl, "$packageName/$locale/$localizedIcon")
                    legacyIcon != null -> resolveUrl(source.indexUrl, "icons-640/$legacyIcon")
                    else -> null
                }

                val screenshotUrls = if (localized != null && locale != null) {
                    jsonStrings(localized.optJSONArray("phoneScreenshots")).map { file ->
                        resolveUrl(source.indexUrl, "$packageName/$locale/phoneScreenshots/$file")
                    }
                } else {
                    emptyList()
                }

                result += StoreApp(
                    id = packageName,
                    name = name,
                    summary = summary,
                    description = description,
                    version = version.optString("versionName").ifBlank { bestCode.toString() },
                    versionCode = bestCode.coerceAtLeast(0),
                    iconUrl = iconUrl,
                    screenshotUrls = screenshotUrls,
                    categories = categories,
                    apkUrl = resolveUrl(source.indexUrl, apkName),
                    sourceName = source.name
                )
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun preferredLocalized(metadata: JSONObject): Pair<String, JSONObject>? {
        val localized = metadata.optJSONObject("localized") ?: return null
        val locales = listOf("de-DE", "de", "en-US", "en")
        locales.forEach { locale ->
            localized.optJSONObject(locale)?.let { return locale to it }
        }
        val keys = localized.keys()
        if (keys.hasNext()) {
            val locale = keys.next()
            localized.optJSONObject(locale)?.let { return locale to it }
        }
        return null
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun resolveUrl(indexUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = indexUrl.substringBeforeLast('/') + "/"
        return base + path.removePrefix("/")
    }
}
