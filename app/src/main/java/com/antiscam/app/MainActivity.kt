package com.antiscam.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antiscam.app.capture.CaptureState
import com.antiscam.app.capture.CaptureStore
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        CaptureStore.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF006B5E),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD0F3E8),
                    background = Color(0xFFF5F8F6),
                    surface = Color(0xFFFFFFFF),
                ),
            ) {
                val state by CaptureStore.state.collectAsStateWithLifecycle()
                var launchError by remember { mutableStateOf<String?>(null) }

                DevelopmentScreen(
                    state = state,
                    launchError = launchError,
                    onOpenSettings = {
                        launchError = null
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                            launchError = "Open Android Settings, then Accessibility, and enable AntiScam."
                        }
                    },
                    onOpenTarget = {
                        launchError = null
                        val intent = packageManager.getLaunchIntentForPackage(state.selectedPackage)
                        if (intent == null) {
                            launchError = "The selected app is not installed. Install it, or select the Test conversation app."
                        } else {
                            try {
                                startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                launchError = "Android could not open the selected app. Open it from the home screen."
                            }
                        }
                    },
                )
            }
        }
    }
}

private data class TargetApp(val label: String, val packageName: String)

private val targetApps = listOf(
    TargetApp("Test conversation", "com.antiscam.fixture"),
    TargetApp("Google Messages", "com.google.android.apps.messaging"),
    TargetApp("Gmail", "com.google.android.gm"),
    TargetApp("WhatsApp", "com.whatsapp"),
    TargetApp("Messenger", "com.facebook.orca"),
    TargetApp("Facebook", "com.facebook.katana"),
)

@Composable
private fun DevelopmentScreen(
    state: CaptureState,
    launchError: String?,
    onOpenSettings: () -> Unit,
    onOpenTarget: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("AntiScam", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Capture foundation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Development build. This app extracts screen text. It does not detect scams or provide protection.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!state.consentGranted) {
                ConsentCard()
            } else {
                val hasResult = state.lastCapturedAtMillis != null || state.lastError != null
                if (hasResult) ResultCard(state)
                SetupCard(state, onOpenSettings, onOpenTarget)
                if (launchError != null) {
                    Text(launchError, color = MaterialTheme.colorScheme.error)
                }
                if (!hasResult) ResultCard(state)
                TextButton(onClick = {
                    CaptureStore.setEnabled(false)
                    CaptureStore.setConsent(false)
                }) {
                    Text("Withdraw consent and clear text")
                }
            }
        }
    }
}

@Composable
private fun ConsentCard() {
    var accepted by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Before you enable capture", style = MaterialTheme.typography.titleLarge)
            Text(
                "AntiScam uses Android Accessibility to detect text changes while you type in the app you select. " +
                    "It then captures visible screen content and extracts text with ML Kit on this device.",
            )
            Text(
                "Screens can include private messages and reply text. Screenshots and extracted text stay on this device. " +
                    "The latest result stays in memory until you clear it or Android stops this app.",
            )
            Text("You can pause capture or withdraw consent at any time. Use the Test conversation app for your first test.")
            Row {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                Text(
                    "I agree to local screen capture and text extraction in my selected app.",
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
            }
            Button(
                onClick = {
                    CaptureStore.setConsent(true)
                    CaptureStore.setEnabled(true)
                },
                enabled = accepted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow local capture")
            }
        }
    }
}

@Composable
private fun SetupCard(state: CaptureState, onOpenSettings: () -> Unit, onOpenTarget: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedApp = targetApps.firstOrNull { it.packageName == state.selectedPackage }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1. Select one app", style = MaterialTheme.typography.titleMedium)
            Column {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedApp?.label ?: state.selectedPackage)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    targetApps.forEach { app ->
                        DropdownMenuItem(
                            text = { Text(app.label) },
                            onClick = {
                                CaptureStore.setTargetPackage(app.packageName)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Text("Start with Test conversation. Other apps require separate tests.", style = MaterialTheme.typography.bodySmall)
            Text("2. Enable Accessibility", style = MaterialTheme.typography.titleMedium)
            Text(if (state.serviceConnected) "Accessibility service: connected" else "Accessibility service: not connected")
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Accessibility settings")
            }
            Text("In Android Settings, select AntiScam and enable its service.", style = MaterialTheme.typography.bodySmall)
            Text("3. Type, then return here", style = MaterialTheme.typography.titleMedium)
            Text("Type a short reply. Stop typing briefly. Return to AntiScam to read the extracted text.")
            Button(
                onClick = onOpenTarget,
                enabled = state.monitoringEnabled && state.serviceConnected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open selected app")
            }
            OutlinedButton(
                onClick = { CaptureStore.setEnabled(!state.monitoringEnabled) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.monitoringEnabled) "Pause capture" else "Resume capture")
            }
        }
    }
}

@Composable
private fun ResultCard(state: CaptureState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Latest extracted text", style = MaterialTheme.typography.titleLarge)
            Text(state.status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            state.lastError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) }
            state.lastCapturedAtMillis?.let { timestamp ->
                Text(
                    "Captured ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))} · Results: ${state.captureCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
            ) {
                SelectionContainer {
                    Text(
                        text = state.latestText.ifEmpty { "No text yet. Complete the steps above, then type in your selected app." },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Text("Text recognition can make mistakes. Compare the result with the original screen.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = CaptureStore::clearResult) {
                Text("Clear result")
            }
        }
    }
}
