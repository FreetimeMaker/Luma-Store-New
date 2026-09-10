package com.freetime.lumastore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.freetime.lumastore.data.AppRepository
import com.freetime.lumastore.data.StoreApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StoreScreen(
    repository: AppRepository,
    canInstallPackages: () -> Boolean,
    requestInstallPermission: () -> Unit,
    install: (StoreApp, (Int) -> Unit, () -> Unit, (Throwable) -> Unit) -> Unit
) {
    var apps by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<StoreApp?>(null) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) { repository.loadApps() }
        result.onSuccess {
            apps = it
            loading = false
        }.onFailure {
            error = it.message ?: "Die App-Quellen konnten nicht geladen werden."
            loading = false
        }
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.name.contains(query, true) || it.id.contains(query, true) || it.summary.contains(query, true)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Text("Luma Store", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Apps aus ${repository.sources.size} Quellen entdecken und installieren",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Apps suchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            "${filtered.size} Apps • ${repository.sources.joinToString { it.name }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(filtered, key = { it.id }) { app ->
                        AppCard(
                            app = app,
                            installing = installingId == app.id,
                            progress = installProgress,
                            onOpen = { selectedApp = app },
                            onInstall = {
                                if (!canInstallPackages()) requestInstallPermission()
                                else {
                                    installingId = app.id
                                    installProgress = 0
                                    install(
                                        app,
                                        { installProgress = it },
                                        { installProgress = 100; installingId = null },
                                        {
                                            installingId = null
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

    selectedApp?.let { app ->
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text(app.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(app.description.ifBlank { app.summary.ifBlank { "Keine Beschreibung verfügbar." } })
                    HorizontalDivider()
                    Text("Paket: ${app.id}")
                    Text("Version: ${app.version} (${app.versionCode})")
                    Text("Quelle: ${app.sourceName}")
                }
            },
            confirmButton = { TextButton(onClick = { selectedApp = null }) { Text("Schließen") } }
        )
    }
}

@Composable
private fun AppCard(
    app: StoreApp,
    installing: Boolean,
    progress: Int,
    onOpen: () -> Unit,
    onInstall: () -> Unit
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        app.summary.ifBlank { app.id },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${app.version} • ${app.sourceName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Button(onClick = onInstall, enabled = !installing) {
                    Text(if (installing) "$progress%" else "Installieren")
                }
            }
            if (installing) {
                Spacer(Modifier.height(10.dp))
                if (progress > 0) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
