@file:OptIn(ExperimentalMaterial3Api::class)

package dev.metzook.teleprompter.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.metzook.teleprompter.settings.PrompterSettings
import dev.metzook.teleprompter.settings.SettingsRepository
import dev.metzook.teleprompter.vault.ScriptMeta
import dev.metzook.teleprompter.vault.ScriptRepository
import dev.metzook.teleprompter.vault.VaultStore
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    repo: ScriptRepository,
    settingsRepo: SettingsRepository,
    onOpen: (String) -> Unit,
    onEditLocal: (String?) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsState(initial = PrompterSettings())

    var locals by remember { mutableStateOf<List<ScriptMeta>?>(null) }
    var vaultScripts by remember { mutableStateOf<List<VaultStore.VaultScript>>(emptyList()) }
    var vaultError by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScriptMeta?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val pickVault = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            scope.launch { settingsRepo.setVaultUri(uri.toString()) }
        }
    }

    LaunchedEffect(settings.vaultUri, refreshTick) {
        locals = withContext(Dispatchers.IO) { repo.local.list() }
        val uriString = settings.vaultUri
        if (uriString == null) {
            vaultScripts = emptyList()
            vaultError = false
        } else {
            val uri = Uri.parse(uriString)
            // Show the last known listing instantly; the slow SAF walk runs
            // behind it and only replaces the list once it lands. The walk is
            // skipped entirely when a recent one already ran (menu Refresh
            // forces it via refreshTick).
            val cached = withContext(Dispatchers.IO) { repo.vault.cached(uri) }
            if (cached != null) {
                vaultScripts = cached
                vaultError = false
            }
            if (refreshTick > 0 || !repo.vault.isFresh(uri, maxAgeMs = 60_000)) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { repo.vault.list(uri) }
                }
                val fresh = result.getOrNull()
                if (!fresh.isNullOrEmpty()) {
                    vaultScripts = fresh
                    vaultError = false
                } else if (vaultScripts.isEmpty()) {
                    vaultError = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scripts") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = {
                                    menuOpen = false
                                    refreshTick++
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (settings.vaultUri == null) "Connect Obsidian vault"
                                        else "Change Obsidian vault",
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    pickVault.launch(null)
                                },
                            )
                            if (settings.vaultUri != null) {
                                DropdownMenuItem(
                                    text = { Text("Disconnect vault") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { settingsRepo.setVaultUri(null) }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditLocal(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New script")
            }
        },
    ) { padding ->
        val localList = locals
        if (localList == null) {
            Box(Modifier.fillMaxSize().padding(padding))
        } else if (localList.isEmpty() && vaultScripts.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No scripts yet.\nTap + to add one, or connect your\nObsidian vault from the ⋮ menu.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (settings.vaultUri != null) {
                    item(key = "header-vault") { SectionHeader("Obsidian vault") }
                    if (vaultScripts.isEmpty()) {
                        item(key = "vault-empty") {
                            Text(
                                if (vaultError) "Couldn't read the vault — try reconnecting it from the ⋮ menu."
                                else "No .md files found in the selected folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    items(vaultScripts, key = { it.documentUri.toString() }) { v ->
                        VaultRow(v, onClick = { onOpen(repo.vaultId(v.documentUri)) })
                    }
                    item(key = "header-local") { SectionHeader("My scripts") }
                }
                items(localList, key = { it.id }) { meta ->
                    LocalRow(
                        meta = meta,
                        minutes = estimatedMinutes(meta.wordCount, settings.wpm),
                        onClick = { onOpen(repo.localId(meta.id)) },
                        onEdit = { onEditLocal(meta.id) },
                        onDelete = { pendingDelete = meta },
                    )
                }
            }
        }
    }

    pendingDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete “${meta.title}”?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        // Reload locals only — no reason to re-walk the vault.
                        locals = withContext(Dispatchers.IO) {
                            repo.local.delete(meta.id)
                            repo.local.list()
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

private fun estimatedMinutes(words: Int, wpm: Int): Int =
    ceil(words / wpm.coerceAtLeast(1).toDouble()).toInt().coerceAtLeast(1)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun VaultRow(script: VaultStore.VaultScript, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                script.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Obsidian · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(script.modifiedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalRow(
    meta: ScriptMeta,
    minutes: Int,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    meta.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${meta.wordCount} words · ≈$minutes min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}
