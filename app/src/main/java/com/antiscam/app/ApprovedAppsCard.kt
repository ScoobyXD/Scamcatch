package com.antiscam.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.antiscam.app.capture.CaptureState
import com.antiscam.app.capture.CaptureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ApprovedAppsCard(state: CaptureState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LifecycleResumeEffect(context) {
        val refresh = scope.launch {
            loading = true
            apps = withContext(Dispatchers.IO) {
                val manager = context.packageManager
                @Suppress("DEPRECATION")
                manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .distinctBy { it.activityInfo.packageName }
                    .filter { it.activityInfo.packageName != context.packageName }
                    .map { it.activityInfo.packageName to it.loadLabel(manager).toString() }
                    .sortedBy { it.second.lowercase() }
            }
            loading = false
        }
        onPauseOrDispose { refresh.cancel() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Approved apps", style = MaterialTheme.typography.titleMedium)
            Text("Choose the apps ScamCatch can check. Capture runs only while an approved app is in the foreground.")
            Text("No apps are selected by default. Your choices stay on this phone.")
            if (state.approvedPackages.isEmpty()) Text("No apps approved. Screen capture is off.")
            if (loading) Text("Loading installed apps…")
            else if (apps.isEmpty()) Text("No launchable apps found.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(apps, key = { it.first }) { (packageName, label) ->
                    val approved = packageName in state.approvedPackages
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = approved,
                            role = Role.Checkbox,
                            onValueChange = { selected ->
                                val current = CaptureStore.state.value.approvedPackages
                                CaptureStore.setApprovedPackages(if (selected) current + packageName else current - packageName)
                            },
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = approved, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(label)
                            Text(packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.approvedPackages.isNotEmpty()) {
                TextButton(onClick = { CaptureStore.setApprovedPackages(emptySet()) }) { Text("Clear approved apps") }
            }
        }
    }
}
