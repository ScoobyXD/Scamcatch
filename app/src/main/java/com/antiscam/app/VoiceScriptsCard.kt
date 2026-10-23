package com.antiscam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antiscam.app.capture.CaptureState
import com.antiscam.app.capture.VoiceScripts

@Composable
fun VoiceScriptsCard(state: CaptureState) {
    val scriptState by VoiceScripts.state.collectAsStateWithLifecycle()
    var showSelector by remember { mutableStateOf(false) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val selected = scriptState.scripts.firstOrNull { it.id == scriptState.selectedId }
    val canChange = state.runId == null && !state.standaloneRunRequested && scriptState.ready && !scriptState.working

    LaunchedEffect(canChange) {
        if (!canChange) showSelector = false
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voice script", style = MaterialTheme.typography.titleMedium)
            if (selected == null) {
                Text("No voice script selected.")
            } else {
                Text(selected.name, style = MaterialTheme.typography.titleSmall)
                Text("ID: ${selected.id} · Revision: ${selected.revision.take(12)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = {
                    draftId = scriptState.selectedId
                    selectionError = null
                    showSelector = true
                },
                enabled = canChange && scriptState.scripts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose script") }
            Text("Each run keeps the script chosen when that run starts. Stop the run before changing the selection.")
            Text("Scripts come from app/src/main/assets/voice-scripts.md. Use the desktop console to reload them onto this phone.",
                style = MaterialTheme.typography.bodySmall)
            scriptState.message?.let {
                Text(it, color = if (scriptState.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall)
            }
            selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showSelector && canChange) {
        AlertDialog(
            onDismissRequest = { showSelector = false },
            title = { Text("Choose voice script") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(scriptState.scripts, key = { it.id }) { script ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = draftId == script.id,
                                        onClick = { draftId = script.id },
                                        role = Role.RadioButton,
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = draftId == script.id, onClick = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(script.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(script.id, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draftId?.let { id ->
                            selectionError = VoiceScripts.select(id)
                            if (selectionError == null) showSelector = false
                        }
                    },
                    enabled = draftId != null,
                ) { Text("Save selection") }
            },
            dismissButton = {
                TextButton(onClick = { showSelector = false }) { Text("Cancel") }
            },
        )
    }
}
