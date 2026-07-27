@file:OptIn(ExperimentalMaterial3Api::class)

package dev.metzook.teleprompter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.metzook.teleprompter.asr.AsrModel
import dev.metzook.teleprompter.asr.AsrModels
import dev.metzook.teleprompter.asr.ModelManager
import dev.metzook.teleprompter.settings.GestureSlot
import dev.metzook.teleprompter.settings.PrompterAction
import dev.metzook.teleprompter.settings.PrompterSettings
import dev.metzook.teleprompter.settings.SettingsRepository
import kotlinx.coroutines.launch

private val COUNTDOWN_CHOICES = listOf(0, 1, 2, 3, 5)

@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    modelManager: ModelManager,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsState(initial = PrompterSettings())

    var installedTick by remember { mutableIntStateOf(0) }
    val installedIds = remember(installedTick) { modelManager.installedIds() }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadStatus by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionHeader("Gestures")
            GestureSlot.entries.forEach { slot ->
                GestureRow(
                    label = slot.label,
                    selected = settings.gestures[slot] ?: slot.default,
                    onSelect = { action -> scope.launch { settingsRepo.setGesture(slot, action) } },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pinch to resize text", Modifier.weight(1f))
                Switch(
                    checked = settings.pinchResize,
                    onCheckedChange = { scope.launch { settingsRepo.setPinchResize(it) } },
                )
            }

            SectionHeader("Prompter")
            Text("Countdown before start", style = MaterialTheme.typography.bodyLarge)
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                COUNTDOWN_CHOICES.forEach { sec ->
                    FilterChip(
                        selected = settings.countdownSec == sec,
                        onClick = { scope.launch { settingsRepo.setCountdownSec(sec) } },
                        label = { Text(if (sec == 0) "Off" else "$sec s") },
                    )
                }
            }

            SectionHeader("Voice — preview")
            Text(
                "Download a speech model to see live on-device transcription in the prompter. " +
                    "Audio never leaves the phone. This becomes voice-following in the next milestone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AsrModels.ALL.forEach { model ->
                ModelRow(
                    model = model,
                    installed = model.id in installedIds,
                    selected = settings.asrModelId == model.id,
                    downloading = downloadingId == model.id,
                    downloadStatus = downloadStatus,
                    enabled = downloadingId == null,
                    onSelect = { scope.launch { settingsRepo.setAsrModelId(model.id) } },
                    onDownload = {
                        downloadingId = model.id
                        downloadStatus = "Starting…"
                        scope.launch {
                            runCatching {
                                modelManager.download(model) { file, of, pct ->
                                    downloadStatus =
                                        if (pct >= 0) "File $file/$of · $pct%" else "File $file/$of…"
                                }
                            }.onFailure {
                                downloadStatus = "Download failed — check connection and retry"
                            }.onSuccess {
                                downloadStatus = ""
                                scope.launch { settingsRepo.setAsrModelId(model.id) }
                            }
                            downloadingId = null
                            installedTick++
                        }
                    },
                    onDelete = {
                        modelManager.delete(model)
                        installedTick++
                    },
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Live transcript overlay", Modifier.weight(1f))
                Switch(
                    checked = settings.asrOverlay,
                    onCheckedChange = { scope.launch { settingsRepo.setAsrOverlay(it) } },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelRow(
    model: AsrModel,
    installed: Boolean,
    selected: Boolean,
    downloading: Boolean,
    downloadStatus: String,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = installed,
            )
            Text(model.label, Modifier.weight(1f))
            if (installed) {
                TextButton(onClick = onDelete, enabled = enabled) { Text("Delete") }
            } else {
                TextButton(onClick = onDownload, enabled = enabled) {
                    Text(if (downloading) "Downloading…" else "Download")
                }
            }
        }
        if (downloading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp))
            Text(
                downloadStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, top = 2.dp),
            )
        } else if (downloadStatus.startsWith("Download failed") && !installed) {
            Text(
                downloadStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun GestureRow(
    label: String,
    selected: PrompterAction,
    onSelect: (PrompterAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(selected.label)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                PrompterAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            expanded = false
                            onSelect(action)
                        },
                    )
                }
            }
        }
    }
}
