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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antiscam.app.capture.CaptureState
import com.antiscam.app.capture.CaptureStore
import com.antiscam.app.capture.RunMonitor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        CaptureStore.initialize(applicationContext)
        RunMonitor.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF006B5E),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD0F3E8),
                    background = Color(0xFFF5F8F6),
                    surface = Color.White,
                ),
            ) {
                val state by CaptureStore.state.collectAsStateWithLifecycle()
                var settingsError by remember { mutableStateOf<String?>(null) }

                SetupScreen(
                    state = state,
                    settingsError = settingsError,
                    onOpenSettings = {
                        settingsError = null
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                            settingsError = "Open Android Settings, then Accessibility, and enable Antiscam."
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    state: CaptureState,
    settingsError: String?,
    onOpenSettings: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Antiscam", style = MaterialTheme.typography.titleLarge)
            Text(
                if (state.serviceConnected) "Accessibility: connected" else "Accessibility: not connected",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Accessibility settings")
            }
            settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (!state.consentGranted) {
                ConsentCard()
            } else {
                TextButton(onClick = { CaptureStore.setConsent(false) }) {
                    Text("Withdraw capture consent")
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
            Text("Allow screen capture", style = MaterialTheme.typography.titleMedium)
            Text(
                "Antiscam uses Android Accessibility to observe app changes, typing, taps, and scrolling. " +
                    "During a dashboard run, it captures the visible screen, including overlapping windows, " +
                    "and extracts text with ML Kit on this device.",
            )
            Text(
                "Screens can contain private messages and typed text. Screenshot images stay in device memory. " +
                    "Extracted text and event records are saved on your connected computer. They are not sent to a cloud service.",
            )
            Text("You can withdraw consent here at any time. Previously saved runs remain on your computer.")
            Row {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                Text(
                    "I allow local screen capture and text extraction across apps during dashboard runs.",
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
