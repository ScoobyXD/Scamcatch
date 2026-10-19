package com.antiscam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antiscam.app.capture.CaptureState
import com.antiscam.app.capture.PhoneCredentials

@Composable
fun PhoneKeysCard(state: CaptureState) {
    val statuses by PhoneCredentials.status.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Phone keys", style = MaterialTheme.typography.titleMedium)
            Text("Add keys here to run without your laptop. Jev and voice still need internet access.")
            Text("Remember on this phone is optional. Saved keys are encrypted on this device and excluded from backups.")
            Text("A loaded key has not yet been checked with its service. Access errors appear when a request runs.",
                style = MaterialTheme.typography.bodySmall)
            PhoneCredentials.Provider.entries.filter { it.available }.forEach { provider ->
                key(provider) {
                    HorizontalDivider()
                    PhoneKeyInput(provider, provider.ready(state), statuses.getValue(provider))
                }
            }
            Text("Desktop key buttons can also save encrypted keys on this phone. Clear here or desktop Clear key removes the saved phone key.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PhoneKeyInput(provider: PhoneCredentials.Provider, ready: Boolean, status: PhoneCredentials.Status) {
    // Never put an API key in saved instance state or display an existing provider key.
    var value by remember(provider) { mutableStateOf("") }
    var rememberKey by remember(provider) { mutableStateOf(false) }
    Text(provider.label, style = MaterialTheme.typography.titleSmall)
    Text(if (ready) "Key is loaded for this app session." else "No active key.")
    Text(when (status.saved) {
        true -> "A saved key is on this phone."
        false -> "No key is saved on this phone."
        null -> "Saved key status is not confirmed."
    }, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 2048) value = it },
        label = { Text("${provider.label} API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        Checkbox(checked = rememberKey, onCheckedChange = { rememberKey = it }, enabled = !status.working)
        Text("Remember on this phone", modifier = Modifier.padding(top = 12.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                if (PhoneCredentials.setKey(provider, value, rememberKey)) {
                    value = ""
                    rememberKey = false
                }
            },
            enabled = value.isNotBlank() && !status.working,
            modifier = Modifier.weight(1f),
        ) { Text("Set key") }
        OutlinedButton(
            onClick = {
                value = ""
                rememberKey = false
                PhoneCredentials.clearKey(provider)
            },
            modifier = Modifier.weight(1f),
        ) { Text("Clear") }
    }
    status.message?.let {
        Text(it, color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall)
    }
}
