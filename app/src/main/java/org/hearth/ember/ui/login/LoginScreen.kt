package org.hearth.ember.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.hearth.ember.data.ApiException
import org.hearth.ember.ember

/** Sign in to a Hearth server, or join one with an invite. The server address is remembered, so after the first time it is just the name and password. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val app = context.ember
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(app.store.server.removePrefix("https://").takeIf { it.isNotBlank() && !it.startsWith("http://localhost") } ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Two-step sign-in: set when the server says this account needs the code from its authenticator app.
    var code by remember { mutableStateOf("") }
    var needsCode by remember { mutableStateOf(false) }
    // The server lets anyone join without an invite code.
    var openJoin by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(server) {
        openJoin = false
        // Once the address looks finished (a pause in typing, with a dot or localhost in it), ask the server how joining works.
        if (server.contains('.') || server.startsWith("localhost") || server.startsWith("http://")) {
            runCatching { kotlinx.coroutines.delay(800); app.store.server = server; app.api.authConfig() }.onSuccess { openJoin = it.registration == "open" }
        }
    }

    fun submit() {
        if (busy) return
        error = null
        if (server.isBlank()) { error = "Enter your server address."; return }
        if (username.isBlank() || password.isEmpty()) { error = "Enter your username and password."; return }
        if (joining && invite.isBlank() && !openJoin) { error = "Enter the invite code you were given."; return }
        busy = true
        scope.launch {
            try {
                if (joining) app.repo.register(server, invite, username, displayName.ifBlank { username }, password)
                else app.repo.signIn(server, username, password, if (needsCode) code.trim() else null)
                // The password is in hand: put the backup key under it (a few seconds of work, in the background, for backups to use later).
                app.scope.launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { app.backup.adoptPassword(password, false) } }
            } catch (e: ApiException) {
                if (e.message?.contains("two-step", ignoreCase = true) == true) needsCode = true
                error = e.message
            } catch (e: Exception) {
                error = "Something went wrong. Try again."
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // With the keyboard up, or sideways, the logo makes way so the form and the button are all in view.
            val compact = WindowInsets.isImeVisible || LocalConfiguration.current.screenHeightDp < 520
            Spacer(Modifier.height(if (compact) 8.dp else 32.dp))
            if (!compact) {
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(androidx.compose.ui.res.painterResource(org.hearth.ember.R.drawable.ic_stat_ember), null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(20.dp))
            }
            Text("Ember", style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            if (!compact) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (joining) "Join your friends' server with the invite code they sent you." else "Private messaging on your own server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(if (compact) 12.dp else 28.dp))

            OutlinedTextField(
                value = server, onValueChange = { server = it }, label = { Text("Server") }, singleLine = true,
                placeholder = { Text("chat.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (joining) {
                OutlinedTextField(
                    value = invite, onValueChange = { invite = it }, label = { Text(if (openJoin) "Invite code (optional)" else "Invite code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it }, label = { Text("Your name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text(if (joining) "Choose a password (8+ characters)" else "Password") }, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide password" else "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (needsCode && !joining) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it }, label = { Text("Two-step code") }, singleLine = true,
                    supportingText = { Text("From your authenticator app, or a recovery code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (joining) "Create account" else "Sign in", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { joining = !joining; error = null }) {
                Text(if (joining) "I already have an account" else if (openJoin) "Create an account" else "I have an invite code")
            }
            TextButton(onClick = { restoring = true }) { Text("Restore from a backup") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (restoring) org.hearth.ember.ui.settings.RestoreOnSignIn(server, username, password) { restoring = false }
}
