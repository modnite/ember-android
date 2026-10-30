package org.hearth.ember.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.hearth.ember.data.TwoStepStatus
import org.hearth.ember.ember

/** The secret in groups of four, as authenticator apps show it. */
internal fun groupedSecret(secret: String) = secret.chunked(4).joinToString(" ")

/**
 * Optional two-step sign-in. An authenticator app makes a six digit code that changes every 30 seconds; signing in then needs it as well as the
 * password. Turning it on shows the key to add to the app, asks for a code from the app to prove it works, then shows eight one-time recovery
 * codes (once). Turning it off needs the password and a code.
 */
@Composable
fun TwoStepRows() {
    val context = LocalContext.current
    val app = context.ember
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TwoStepStatus?>(null) }
    var setup by remember { mutableStateOf<Pair<String, String>?>(null) } // secret and the otpauth address
    var recovery by remember { mutableStateOf<List<String>?>(null) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var turningOff by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() { runCatching { app.api.twoStep() }.onSuccess { status = it }.onFailure { error = it.message } }
    LaunchedEffect(Unit) { refresh() }
    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            runCatching { block() }.onFailure { error = it.message ?: "Something went wrong." }
            busy = false
        }
    }
    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied.", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val s = status
        when {
            s == null -> Text(error ?: "Loading…", style = MaterialTheme.typography.bodyMedium)
            s.enabled -> {
                Text("Two-step sign-in is on. ${s.recoveryLeft} recovery ${if (s.recoveryLeft == 1) "code is" else "codes are"} left.", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { turningOff = true }) { Text("Turn off…") }
            }
            setup == null -> {
                Text("Two-step sign-in is off. Turn it on and signing in needs a code from an authenticator app as well as your password.", style = MaterialTheme.typography.bodyLarge)
                Button(enabled = !busy, onClick = { run { val r = app.api.twoStepSetup(); setup = r.secret to r.uri } }) { Text("Turn on…") }
            }
            else -> {
                val (secret, uri) = setup!!
                Text("1. In your authenticator app (Google Authenticator, Aegis, 2FAS, Authy and others) add an account and type this key in:", style = MaterialTheme.typography.bodyMedium)
                Text(groupedSecret(secret), style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy("Two-step key", secret) }) { Text("Copy key") }
                    OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Toast.makeText(context, "No authenticator app found. Type the key in by hand.", Toast.LENGTH_LONG).show() } }) { Text("Open in app") }
                }
                Text("2. Type the six digit code it shows now:", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(code, { code = it }, singleLine = true, label = { Text("Code") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = code.isNotBlank() && !busy, onClick = { run { recovery = app.api.twoStepEnable(code.trim()).recovery; setup = null; code = ""; refresh() } }) { Text("Turn on") }
                    TextButton(onClick = { setup = null; code = "" }) { Text("Cancel") }
                }
            }
        }
        error?.let { if (s != null) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }

    recovery?.let { codes ->
        AlertDialog(
            onDismissRequest = {},
            icon = { androidx.compose.material3.Icon(Icons.Filled.Lock, null) },
            title = { Text("Your recovery codes") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keep these somewhere safe. Each works once if you lose your phone. They are not shown again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(codes.joinToString("\n"), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                }
            },
            confirmButton = { TextButton(onClick = { recovery = null }) { Text("I have saved them") } },
            dismissButton = { TextButton(onClick = { copy("Recovery codes", codes.joinToString("\n")) }) { Text("Copy") } },
        )
    }

    if (turningOff) AlertDialog(
        onDismissRequest = { turningOff = false },
        title = { Text("Turn off two-step sign-in") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(code, { code = it }, singleLine = true, label = { Text("Code from the app, or a recovery code") })
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && code.isNotBlank() && !busy, onClick = {
                run { app.api.twoStepDisable(password, code.trim()); turningOff = false; password = ""; code = ""; refresh() }
            }) { Text("Turn off") }
        },
        dismissButton = { TextButton(onClick = { turningOff = false }) { Text("Cancel") } },
    )
}
