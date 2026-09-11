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

internal enum class SourceType {
    FDROID_V1,
    LUMA_API
}

data class AppSource(
    val name: String,
    val indexUrl: String,
    internal val type: SourceType = SourceType.FDROID_V1
)

class AppRepository {
    val sources = listOf(
        AppSource(
            name = "Freetime F-Droid Repository",
            indexUrl = "https://fdroid.free-time.me/repo/index-v1.json"
        ),
        AppSource(
            name = "F-Droid Repository",
            indexUrl = "https://f-droid.org/repo/index-v1.json"
        ),
        AppSource(
            name = "Luma Store API",
            indexUrl = "https://api.free-time.me/v2/lumastore/apps",
            type = SourceType.LUMA_API
        )
    )

    fun loadApps(): Result<List<StoreApp>> = runCatching {
        val variants = mutableListOf<StoreApp>()
        var successfulSources = 0

        sources.forEach { source ->
            runCatching { loadSource(source) }
                .onSuccess { apps ->
                    successfulSources++
                    variants += apps
                }
        }

        check(successfulSources > 0) { "Keine App-Quelle konnte geladen werden." }

        variants
            .distinctBy { "${it.id}\u0000${it.sourceName}" }
            .sortedWith(compareBy<StoreApp> { it.name.lowercase() }.thenBy { it.sourceName.lowercase() })
    }

    private fun loadSource(source: AppSource): List<StoreApp> {
        val text = downloadText(source.indexUrl)
        return when (source.type) {
            SourceType.FDROID_V1 -> parseFdroidV1(source, text)
            SourceType.LUMA_API -> parseLumaApi(source, text)
        }
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Luma-Store/1.0")

            val responseCode = connection.responseCode
            check(responseCode in 200..299) { "HTTP $responseCode für $url" }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLumaApi(source: AppSource, text: String): List<StoreApp> {
        val apps = JSONArray(text)
        val result = mutableListOf<StoreApp>()

        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            val platforms = app.optJSONArray("platforms") ?: JSONArray()

            var androidPlatform: JSONObject? = null
            for (platformIndex in 0 until platforms.length()) {
                val platform = platforms.optJSONObject(platformIndex) ?: continue
                if (platform.optString("platform").equals("Android", ignoreCase = true)) {
                    androidPlatform = platform
                    break
                }
            }

            val platform = androidPlatform ?: continue
            val downloadUrl = platform.optString("download_url").trim()
            if (downloadUrl.isBlank()) continue

            val rawId = firstNonBlank(
                app.optString("packageName"),
                app.optString("package_name"),
                app.optString("application_id"),
                app.optString("luma_submission_id"),
                app.optString("id")
            ) ?: continue

            val packageName = firstNonBlank(
                app.optString("packageName"),
                app.optString("package_name"),
                app.optString("application_id")
            )
            val id = packageName ?: "luma:$rawId"

            val name = app.optString("name").ifBlank { "Unbenannte App" }
            val description = app.optString("description")
            val version = app.optString("version").ifBlank { "1.0" }
            val versionCode = app.optLong("version_code", Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
                ?: versionToCode(version)

            val categoryName = app.optJSONObject("category")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }

            val iconUrl = firstNonBlank(
                app.optString("icon_url"),
                app.optString("iconUrl")
            )

            result += StoreApp(
                id = id,
                name = name,
                summary = description.lineSequence().firstOrNull().orEmpty().take(180),
                description = description,
                version = version,
                versionCode = versionCode,
                iconUrl = iconUrl,
                screenshotUrls = jsonStrings(app.optJSONArray("screenshots")),
                categories = listOfNotNull(categoryName),
                apkUrl = downloadUrl,
                sourceName = source.name
            )
        }

        return result
    }

    private fun parseFdroidV1(source: AppSource, text: String): List<StoreApp> {
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

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun versionToCode(version: String): Long {
        val parts = Regex("\\d+").findAll(version)
            .mapNotNull { it.value.toLongOrNull() }
            .take(4)
            .toList()
        if (parts.isEmpty()) return 0

        var code = 0L
        parts.forEach { part ->
            code = (code * 1_000L) + part.coerceAtMost(999L)
        }
        repeat(4 - parts.size) {
            code *= 1_000L
        }
        return code
    }

    private fun resolveUrl(indexUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = indexUrl.substringBeforeLast('/') + "/"
        return base + path.removePrefix("/")
    }
}
