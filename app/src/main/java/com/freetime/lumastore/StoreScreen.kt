package com.freetime.lumastore

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.freetime.lumastore.data.AppRepository
import com.freetime.lumastore.data.StoreApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppAction {
    INSTALL,
    UPDATE,
    OPEN
}

private enum class StoreView {
    APPS,
    UPDATES
}

private enum class SourceCodeFilter {
    ALL,
    OPEN_SOURCE,
    CLOSED_SOURCE
}

private const val SOURCE_PREFERENCES = "luma_store_source_preferences"
private const val SOURCE_KEY_PREFIX = "source_"

@Composable
fun StoreScreen(
    repository: AppRepository,
    installedAppsRevision: Int,
    installedVersionCode: (String) -> Long?,
    installedVersionName: (String) -> String?,
    openInstalledApp: (String) -> Boolean,
    canInstallPackages: () -> Boolean,
    requestInstallPermission: () -> Unit,
    install: (StoreApp, (Int) -> Unit, () -> Unit, (Throwable) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sourcePreferences = remember(context) {
        context.applicationContext.getSharedPreferences(SOURCE_PREFERENCES, Context.MODE_PRIVATE)
    }

    val initialCachedApps = remember(repository) { repository.loadCachedApps() }
    var apps by remember { mutableStateOf(initialCachedApps) }
    var loading by remember { mutableStateOf(initialCachedApps.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedSourceFilter by remember { mutableStateOf<String?>(null) }
    var sourceCodeFilter by remember { mutableStateOf(SourceCodeFilter.ALL) }
    var selectedAppId by remember { mutableStateOf<String?>(null) }
    var selectedScreenshotUrl by remember { mutableStateOf<String?>(null) }
    var installingKey by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var storeView by remember { mutableStateOf(StoreView.APPS) }
    val selectedSources = remember { mutableStateMapOf<String, String>() }
    val categoryListState = rememberLazyListState()
    val categoryScrollScope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        if (apps.isEmpty()) {
            loading = true
        } else {
            refreshing = true
        }
        error = null

        val result = withContext(Dispatchers.IO) { repository.loadApps() }
        result.onSuccess {
            apps = it
        }.onFailure {
            if (apps.isEmpty()) {
                error = it.message ?: "Die App-Quellen konnten nicht geladen werden."
            }
        }

        loading = false
        refreshing = false
    }

    val variantsById = apps.groupBy { it.id }
    val availableSources = apps
        .map { it.sourceName }
        .distinct()
        .sortedBy { it.lowercase() }

    fun matchesSourceCodeFilter(app: StoreApp): Boolean = when (sourceCodeFilter) {
        SourceCodeFilter.ALL -> true
        SourceCodeFilter.OPEN_SOURCE -> !app.closedSource
        SourceCodeFilter.CLOSED_SOURCE -> app.closedSource
    }

    LaunchedEffect(apps) {
        variantsById.forEach { (id, variants) ->
            val savedSource = sourcePreferences.getString(sourcePreferenceKey(id), null)
            if (savedSource != null && variants.any { it.sourceName == savedSource }) {
                selectedSources[id] = savedSource
            } else {
                selectedSources.remove(id)
                if (savedSource != null) {
                    sourcePreferences.edit().remove(sourcePreferenceKey(id)).apply()
                }
            }
        }

        if (selectedSourceFilter != null && selectedSourceFilter !in availableSources) {
            selectedSourceFilter = null
        }
    }

    fun selectSource(appId: String, sourceName: String) {
        selectedSources[appId] = sourceName
        sourcePreferences.edit()
            .putString(sourcePreferenceKey(appId), sourceName)
            .apply()
    }

    val selectedApps = variantsById.mapNotNull { (id, variants) ->
        val matchingVariants = variants.filter { variant ->
            val matchesSource = selectedSourceFilter == null || variant.sourceName == selectedSourceFilter
            matchesSource && matchesSourceCodeFilter(variant)
        }
        if (matchingVariants.isEmpty()) {
            null
        } else {
            val selectedSource = selectedSources[id]
            matchingVariants.firstOrNull { it.sourceName == selectedSource }
                ?: matchingVariants.maxByOrNull { it.versionCode }
        }
    }.sortedBy { it.name.lowercase() }

    val updateCount = selectedApps.count { app ->
        val installedCode = installedVersionCode(app.id)
        installedCode != null && app.versionCode > installedCode
    }

    val categories = selectedApps
        .flatMap { it.categories }
        .distinct()
        .sortedBy { it.lowercase() }

    val filtered = selectedApps.filter { app ->
        val matchesQuery = query.isBlank() ||
            app.name.contains(query, true) ||
            app.id.contains(query, true) ||
            app.summary.contains(query, true) ||
            app.categories.any { it.contains(query, true) }
        val matchesCategory = selectedCategory == null || selectedCategory in app.categories
        val matchesView = when (storeView) {
            StoreView.APPS -> true
            StoreView.UPDATES -> {
                val installedCode = installedVersionCode(app.id)
                installedCode != null && app.versionCode > installedCode
            }
        }
        matchesQuery && matchesCategory && matchesView
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text("Luma Store", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Apps aus ${repository.sources.size} Quellen entdecken, installieren und aktualisieren",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (refreshing) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = storeView == StoreView.APPS,
                        onClick = { storeView = StoreView.APPS },
                        label = { Text("Apps") }
                    )
                }
                item {
                    FilterChip(
                        selected = storeView == StoreView.UPDATES,
                        onClick = { storeView = StoreView.UPDATES },
                        label = { Text("Updates ($updateCount)") }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (storeView == StoreView.UPDATES) "Updates suchen" else "Apps suchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "Quellcode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = sourceCodeFilter == SourceCodeFilter.ALL,
                        onClick = { sourceCodeFilter = SourceCodeFilter.ALL },
                        label = { Text("Alle Apps") }
                    )
                }
                item {
                    FilterChip(
                        selected = sourceCodeFilter == SourceCodeFilter.OPEN_SOURCE,
                        onClick = { sourceCodeFilter = SourceCodeFilter.OPEN_SOURCE },
                        label = { Text("Open Source") }
                    )
                }
                item {
                    FilterChip(
                        selected = sourceCodeFilter == SourceCodeFilter.CLOSED_SOURCE,
                        onClick = { sourceCodeFilter = SourceCodeFilter.CLOSED_SOURCE },
                        label = { Text("Closed Source") }
                    )
                }
            }

            if (availableSources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Quelle",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedSourceFilter == null,
                            onClick = { selectedSourceFilter = null },
                            label = { Text("Alle Quellen") }
                        )
                    }
                    items(availableSources, key = { it }) { sourceName ->
                        FilterChip(
                            selected = selectedSourceFilter == sourceName,
                            onClick = {
                                selectedSourceFilter = if (selectedSourceFilter == sourceName) null else sourceName
                            },
                            label = { Text(sourceName) }
                        )
                    }
                }
            }

            if (categories.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            categoryScrollScope.launch {
                                val target = (categoryListState.firstVisibleItemIndex - 3).coerceAtLeast(0)
                                categoryListState.animateScrollToItem(target)
                            }
                        },
                        enabled = categoryListState.canScrollBackward
                    ) {
                        Text("‹")
                    }

                    LazyRow(
                        state = categoryListState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("Alle Kategorien") }
                            )
                        }
                        items(categories, key = { it }) { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = {
                                    selectedCategory = if (selectedCategory == category) null else category
                                },
                                label = { Text(category) }
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            categoryScrollScope.launch {
                                val target = (categoryListState.firstVisibleItemIndex + 3)
                                    .coerceAtMost(categories.size)
                                categoryListState.animateScrollToItem(target)
                            }
                        },
                        enabled = categoryListState.canScrollForward
                    ) {
                        Text("›")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                loading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Quellen werden geladen …")
                }

                error != null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "Unbekannter Fehler")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { refreshKey++ }) { Text("Erneut versuchen") }
                }

                storeView == StoreView.UPDATES && filtered.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Keine Updates verfügbar", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Deine installierten Apps sind für die gewählten Filter aktuell.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                filtered.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Keine Apps gefunden", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Für die gewählten Filter sind keine Apps verfügbar.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            buildString {
                                append(filtered.size)
                                append(" Apps • ")
                                append(selectedSourceFilter ?: "Alle Quellen")
                                append(" • ")
                                append(
                                    when (sourceCodeFilter) {
                                        SourceCodeFilter.ALL -> "Open & Closed Source"
                                        SourceCodeFilter.OPEN_SOURCE -> "Open Source"
                                        SourceCodeFilter.CLOSED_SOURCE -> "Closed Source"
                                    }
                                )
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(filtered, key = { it.id }) { app ->
                        val variants = variantsById[app.id]
                            .orEmpty()
                            .filter(::matchesSourceCodeFilter)
                            .filter { selectedSourceFilter == null || it.sourceName == selectedSourceFilter }
                            .sortedBy { it.sourceName.lowercase() }
                        val installedCode = installedVersionCode(app.id)
                        val installedName = installedVersionName(app.id)
                        val action = when {
                            installedCode == null -> AppAction.INSTALL
                            app.versionCode > installedCode -> AppAction.UPDATE
                            else -> AppAction.OPEN
                        }
                        val currentInstallKey = variantKey(app)

                        AppCard(
                            app = app,
                            sourceVariants = variants,
                            action = action,
                            installedVersionName = installedName,
                            installing = installingKey == currentInstallKey,
                            progress = installProgress,
                            onOpenDetails = { selectedAppId = app.id },
                            onSourceSelected = { source -> selectSource(app.id, source.sourceName) },
                            onAction = {
                                if (action == AppAction.OPEN) {
                                    if (!openInstalledApp(app.id)) {
                                        error = "${app.name} ist installiert, hat aber keine startbare Activity."
                                    }
                                } else if (!canInstallPackages()) {
                                    requestInstallPermission()
                                } else {
                                    installingKey = currentInstallKey
                                    installProgress = 0
                                    install(
                                        app,
                                        { installProgress = it },
                                        {
                                            installProgress = 100
                                            installingKey = null
                                        },
                                        {
                                            installingKey = null
                                            error = "Download fehlgeschlagen: ${it.message ?: "Unbekannter Fehler"}"
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    selectedAppId?.let { appId ->
        val variants = variantsById[appId]
            .orEmpty()
            .filter(::matchesSourceCodeFilter)
            .filter { selectedSourceFilter == null || it.sourceName == selectedSourceFilter }
            .sortedBy { it.sourceName.lowercase() }
        val selectedSource = selectedSources[appId]
        val app = variants.firstOrNull { it.sourceName == selectedSource }
            ?: variants.maxByOrNull { it.versionCode }

        if (app != null) {
            AlertDialog(
                onDismissRequest = { selectedAppId = null },
                title = { Text(app.name) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 600.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        AppIcon(app = app, size = 80)

                        Text(
                            if (app.closedSource) "Closed Source" else "Open Source",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (app.closedSource) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (variants.size > 1 && selectedSourceFilter == null) {
                            Text("Quelle wählen", fontWeight = FontWeight.SemiBold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(variants, key = { it.sourceName }) { variant ->
                                    FilterChip(
                                        selected = variant.sourceName == app.sourceName,
                                        onClick = { selectSource(appId, variant.sourceName) },
                                        label = { Text("${variant.sourceName} • ${variant.version}") }
                                    )
                                }
                            }
                        }

                        Text(app.description.ifBlank { app.summary.ifBlank { "Keine Beschreibung verfügbar." } })

                        if (app.categories.isNotEmpty()) {
                            Text(
                                "Kategorien: ${app.categories.joinToString()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (app.screenshotUrls.isNotEmpty()) {
                            Text("Screenshots", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Zum Vergrößern antippen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(app.screenshotUrls) { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = "Screenshot von ${app.name}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .width(140.dp)
                                            .height(250.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { selectedScreenshotUrl = url }
                                    )
                                }
                            }
                        }

                        val hasRichMetadata = listOf(
                            app.authorName,
                            app.authorEmail,
                            app.authorWebsite,
                            app.websiteUrl,
                            app.sourceCodeUrl,
                            app.issueTrackerUrl,
                            app.translationUrl,
                            app.changelogUrl,
                            app.liberapay,
                            app.openCollective,
                            app.bitcoin,
                            app.litecoin,
                            app.license
                        ).any { !it.isNullOrBlank() } || app.donationUrls.isNotEmpty() || app.antiFeatures.isNotEmpty()

                        if (hasRichMetadata) {
                            HorizontalDivider()
                            Text("App-Informationen", fontWeight = FontWeight.SemiBold)

                            app.authorName?.let { MetadataValue("Autor", it) }
                            app.authorEmail?.let { MetadataValue("E-Mail", it) }
                            app.authorWebsite?.let { MetadataLink("Autor-Webseite", it) { uriHandler.openUri(it) } }
                            app.websiteUrl?.let { MetadataLink("Webseite", it) { uriHandler.openUri(it) } }
                            app.sourceCodeUrl?.let { MetadataLink("Quellcode", it) { uriHandler.openUri(it) } }
                            app.issueTrackerUrl?.let { MetadataLink("Issue Tracker", it) { uriHandler.openUri(it) } }
                            app.translationUrl?.let { MetadataLink("Übersetzung", it) { uriHandler.openUri(it) } }
                            app.changelogUrl?.let { MetadataLink("Changelog", it) { uriHandler.openUri(it) } }
                            app.license?.let { MetadataValue("Lizenz", it) }

                            if (app.donationUrls.isNotEmpty() || !app.liberapay.isNullOrBlank() || !app.openCollective.isNullOrBlank() || !app.bitcoin.isNullOrBlank() || !app.litecoin.isNullOrBlank()) {
                                Text("Spenden", fontWeight = FontWeight.SemiBold)
                                app.donationUrls.forEach { donationUrl ->
                                    MetadataLink("Spenden-Link", donationUrl) { uriHandler.openUri(donationUrl) }
                                }
                                app.liberapay?.let { MetadataValue("Liberapay", it) }
                                app.openCollective?.let { MetadataValue("OpenCollective", it) }
                                app.bitcoin?.let { MetadataValue("Bitcoin", it) }
                                app.litecoin?.let { MetadataValue("Litecoin", it) }
                            }

                            if (app.antiFeatures.isNotEmpty()) {
                                Text("Anti-Features", fontWeight = FontWeight.SemiBold)
                                Text(
                                    app.antiFeatures.joinToString(", "),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        HorizontalDivider()
                        Text("Paket: ${app.id}")
                        Text("Version: ${app.version} (${app.versionCode})")
                        Text("Quelle: ${app.sourceName}")
                        Spacer(Modifier.height(4.dp))
                    }
                },
                confirmButton = { TextButton(onClick = { selectedAppId = null }) { Text("Schließen") } }
            )
        }
    }

    selectedScreenshotUrl?.let { screenshotUrl ->
        AlertDialog(
            onDismissRequest = { selectedScreenshotUrl = null },
            title = { Text("Screenshot") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 650.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = screenshotUrl,
                        contentDescription = "Vergrößerter Screenshot",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 650.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedScreenshotUrl = null }) {
                    Text("Schließen")
                }
            }
        )
    }
}

@Composable
private fun MetadataValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MetadataLink(label: String, value: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
private fun AppCard(
    app: StoreApp,
    sourceVariants: List<StoreApp>,
    action: AppAction,
    installedVersionName: String?,
    installing: Boolean,
    progress: Int,
    onOpenDetails: () -> Unit,
    onSourceSelected: (StoreApp) -> Unit,
    onAction: () -> Unit
) {
    Card(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(app = app, size = 64)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        app.summary.ifBlank { app.id },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (app.closedSource) "Closed Source" else "Open Source",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (app.closedSource) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        buildString {
                            append(app.version)
                            append(" • ")
                            append(app.sourceName)
                            if (installedVersionName != null) {
                                append(" • installiert: ")
                                append(installedVersionName)
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (app.categories.isNotEmpty()) {
                        Text(
                            app.categories.take(2).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Button(onClick = onAction, enabled = !installing) {
                    Text(
                        if (installing) "$progress%" else when (action) {
                            AppAction.INSTALL -> "Installieren"
                            AppAction.UPDATE -> "Aktualisieren"
                            AppAction.OPEN -> "Öffnen"
                        }
                    )
                }
            }

            if (sourceVariants.size > 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Quelle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sourceVariants, key = { it.sourceName }) { variant ->
                        FilterChip(
                            selected = variant.sourceName == app.sourceName,
                            onClick = { onSourceSelected(variant) },
                            label = { Text("${variant.sourceName} • ${variant.version}") }
                        )
                    }
                }
            }

            if (installing) {
                Spacer(Modifier.height(10.dp))
                if (progress > 0) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: StoreApp, size: Int) {
    if (app.iconUrl != null) {
        AsyncImage(
            model = app.iconUrl,
            contentDescription = "Icon von ${app.name}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(14.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun variantKey(app: StoreApp): String = "${app.id}\u0000${app.sourceName}"

private fun sourcePreferenceKey(packageName: String): String =
    SOURCE_KEY_PREFIX + packageName
