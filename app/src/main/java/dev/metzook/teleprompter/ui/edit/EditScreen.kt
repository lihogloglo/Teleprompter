@file:OptIn(ExperimentalMaterial3Api::class)

package dev.metzook.teleprompter.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import dev.metzook.teleprompter.vault.ScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditScreen(
    store: ScriptStore,
    scriptId: String?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(scriptId == null) }

    LaunchedEffect(scriptId) {
        if (scriptId != null) {
            val s = withContext(Dispatchers.IO) { store.load(scriptId) }
            if (s != null) {
                title = s.title
                body = s.body
            }
            loaded = true
        }
    }

    fun saveAndClose() {
        if (title.isBlank() && body.isBlank()) {
            onDone()
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { store.save(scriptId, title, body) }
            onDone()
        }
    }

    BackHandler { saveAndClose() }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("Title") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { t ->
                            body = if (body.isBlank()) t else body.trimEnd() + "\n\n" + t
                        }
                    }) { Text("Paste") }
                    IconButton(onClick = { saveAndClose() }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        if (loaded) {
            TextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Type or paste your script…") },
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}
