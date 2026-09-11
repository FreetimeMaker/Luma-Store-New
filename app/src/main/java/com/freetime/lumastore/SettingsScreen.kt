package com.freetime.lumastore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.freetime.lumastore.data.AppRepository
import com.freetime.lumastore.data.AppSource

@Composable
fun SettingsScreen(
    repository: AppRepository,
    onBack: () -> Unit,
    onSourcesChanged: () -> Unit
) {
    var sourceList by remember { mutableStateOf(repository.sources) }
    var sourceName by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var addSourceError by remember { mutableStateOf<String?>(null) }

    val enabledStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            repository.sources.forEach { source ->
                this[source.name] = repository.isSourceEnabled(source)
            }
        }
    }

    fun refreshSources() {
        sourceList = repository.sources
        sourceList.forEach { source ->
            enabledStates[source.name] = repository.isSourceEnabled(source)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Einstellungen",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Luma Store konfigurieren",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onBack) {
                    Text("Zurück")
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Quellen verwalten",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Aktiviere nur die Repositories, aus denen Apps geladen werden sollen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            sourceList.forEach { source ->
                SourceCard(
                    source = source,
                    enabled = enabledStates[source.name] ?: true,
                    removable = repository.isCustomSource(source),
                    onEnabledChange = { checked ->
                        enabledStates[source.name] = checked
                        repository.setSourceEnabled(source, checked)
                        onSourcesChanged()
                    },
                    onRemove = {
                        if (repository.removeCustomSource(source)) {
                            enabledStates.remove(source.name)
                            refreshSources()
                            onSourcesChanged()
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            if (enabledStates.values.none { it }) {
                Text(
                    "Keine Quelle ist aktiviert. Der Store zeigt dann keine Apps an, bis du mindestens eine Quelle wieder aktivierst.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Weitere Quelle hinzufügen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Unterstützt werden F-Droid-Repositories mit index-v1.json. Du kannst die Repository-URL oder direkt die index-v1.json-URL angeben.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = sourceName,
                onValueChange = {
                    sourceName = it
                    addSourceError = null
                },
                label = { Text("Name") },
                placeholder = { Text("Mein F-Droid Repository") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sourceUrl,
                onValueChange = {
                    sourceUrl = it
                    addSourceError = null
                },
                label = { Text("Repository-URL") },
                placeholder = { Text("https://example.org/repo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            addSourceError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    repository.addCustomSource(sourceName, sourceUrl)
                        .onSuccess { source ->
                            sourceName = ""
                            sourceUrl = ""
                            addSourceError = null
                            enabledStates[source.name] = true
                            refreshSources()
                            onSourcesChanged()
                        }
                        .onFailure { error ->
                            addSourceError = error.message ?: "Die Quelle konnte nicht hinzugefügt werden."
                        }
                },
                enabled = sourceName.isNotBlank() && sourceUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Quelle hinzufügen")
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SourceCard(
    source: AppSource,
    enabled: Boolean,
    removable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (removable) {
                        Text(
                            "Benutzerdefinierte Quelle",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        source.indexUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            if (removable) {
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Entfernen")
                }
            }
        }
    }
}
