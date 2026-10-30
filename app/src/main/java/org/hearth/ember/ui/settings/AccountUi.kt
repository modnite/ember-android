package org.hearth.ember.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.hearth.ember.ember

internal const val USERNAME_RULE = "3 to 24 letters, numbers, dots, dashes or underscores"
internal val USERNAME_PATTERN = Regex("^[A-Za-z0-9._-]{3,24}$")

/** What is wrong with a username before it is sent, or null when it is fine. */
internal fun usernameProblem(name: String, current: String): String? = when {
    !USERNAME_PATTERN.matches(name) -> "A username is $USERNAME_RULE."
    name == current -> "That is already your username."
    else -> null
}

/** What is wrong with a new password before it is sent, or null when it is fine. */
internal fun passwordProblem(next: String, again: String): String? = when {
    next.length < 8 -> "A password needs 8 or more characters."
    next != again -> "The two new passwords are not the same."
    else -> null
}

/** A field that scrolls itself into view when it gets focus, once the keyboard has come up, so it is never left behind it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.revealWhenFocused(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this.bringIntoViewRequester(requester).onFocusEvent { if (it.isFocused) scope.launch { delay(300); requester.bringIntoView() } }
}

/**
 * Change the username or the password. Both ask for the current password. A username given up is free for anyone, including its old owner, but
 * two people never hold one at once. A password used before cannot come back (the server decides), and the other devices are signed out.
 */
@Composable
fun AccountRows() {
    val app = LocalContext.current.ember
    val scope = rememberCoroutineScope()
    val me by app.repo.me.collectAsState()
    var name by remember { mutableStateOf("") }
    var namePassword by remember { mutableStateOf("") }
    var nameNote by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var passNote by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your username is ${me?.username.orEmpty()}. It is $USERNAME_RULE.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("New username") }, modifier = Modifier.fillMaxWidth().revealWhenFocused())
        OutlinedTextField(
            namePassword, { namePassword = it }, singleLine = true, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().revealWhenFocused(),
        )
        Button(
            enabled = !busy && name.isNotBlank() && namePassword.isNotEmpty(),
            onClick = {
                val problem = usernameProblem(name.trim(), me?.username.orEmpty())
                if (problem != null) { nameNote = false to problem; return@Button }
                busy = true
                scope.launch {
                    runCatching { app.repo.changeUsername(name, namePassword) }
                        .onSuccess { nameNote = true to "You are now ${name.trim()}."; name = ""; namePassword = "" }
                        .onFailure { nameNote = false to (it.message ?: "Something went wrong.") }
                    busy = false
                }
            },
        ) { Text("Change username") }
        nameNote?.let { (ok, text) -> Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Text("Change your password. One you have used before cannot be used again, and your other devices are signed out.", style = MaterialTheme.typography.bodyMedium)
        for ((label, value, set) in listOf(Triple("Current password", current, { v: String -> current = v }), Triple("New password", next, { v: String -> next = v }), Triple("New password again", again, { v: String -> again = v }))) {
            OutlinedTextField(
                value, set, singleLine = true, label = { Text(label) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().revealWhenFocused(),
            )
        }
        Button(
            enabled = !busy && current.isNotEmpty() && next.isNotEmpty() && again.isNotEmpty(),
            onClick = {
                val problem = passwordProblem(next, again)
                if (problem != null) { passNote = false to problem; return@Button }
                busy = true
                val newPassword = next
                scope.launch {
                    runCatching { app.repo.changePassword(current, newPassword) }
                        .onSuccess {
                            // Backups carry the key under the account password, so the key is put under the new one.
                            app.scope.launch(Dispatchers.Default) { runCatching { app.backup.adoptPassword(newPassword, false) } }
                            passNote = true to "Password changed."
                            current = ""; next = ""; again = ""
                        }
                        .onFailure { passNote = false to (it.message ?: "Something went wrong.") }
                    busy = false
                }
            },
        ) { Text("Change password") }
        passNote?.let { (ok, text) -> Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    }
}
