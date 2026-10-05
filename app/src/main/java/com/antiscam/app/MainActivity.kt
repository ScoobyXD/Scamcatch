package com.antiscam.app

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antiscam.app.capture.CaptureState
import com.antiscam.app.capture.CaptureStore
import com.antiscam.app.capture.JevAnalysis
import com.antiscam.app.capture.JevWarning
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
            if (JevAnalysis.available) JevStatusCard(state)
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
                    "and extracts text with ML Kit on this device. " +
                    "Antiscam masks keyboard windows before OCR. It does not mask sensitive fields. " +
                    "Android capture restrictions still apply.",
            )
            Text(
                "Captured text can include private messages and visible passwords. " +
                    "Screenshot images stay in device memory. " +
                    "Extracted text, Jev questions, answers, and event records are saved on your connected computer. " +
                    "With a development key configured, the app sends extracted text to TypeSafe's Jev service over the Internet. " +
                    "Jev checks the text for the configured topics.",
            )
            Text("You can withdraw consent here at any time. Previously saved runs remain on your computer.")
            Row {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                Text(
                    "I allow screen capture, text extraction, and sending extracted text to TypeSafe for Jev analysis during dashboard runs.",
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
                Text("Allow capture and Jev analysis")
            }
        }
    }
}

@Composable
private fun JevStatusCard(state: CaptureState) {
    val context = LocalContext.current
    var notificationMessage by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationMessage = if (granted) "Scam flag notifications are allowed." else "Notifications are off. Flags remain in the console."
        if (granted) JevWarning.update(context, CaptureStore.state.value.let { if (it.jevFlagged == true) it.jevCaptureId else null })
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Jev analysis", style = MaterialTheme.typography.titleMedium)
            Text(if (state.jevReady) "Development key is set for this app session." else "Set the TypeSafe API key with the Jev key button in the desktop console.")
            Text("The key stays in app memory until cleared or the app process ends. It is not included in console records.")
            Text(
                when {
                    state.jevFlagged == true -> "Probably scam"
                    state.jevFlagged == false -> "No check reached 0.7. This is not a safety verdict."
                    else -> "Jev: ${state.jevStatus}"
                },
                color = if (state.jevFlagged == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Any of the six checks at 0.7 or higher flags the capture. Read the questions and results in the console.")
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }) { Text("Scam flag notifications") }
            notificationMessage?.let { Text(it) }
        }
    }
}
