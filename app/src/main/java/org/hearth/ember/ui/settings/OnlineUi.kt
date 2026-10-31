package org.hearth.ember.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.hearth.ember.ember

/** Where GIFs and stickers can be searched from, and the free keys Giphy and Imgur ask for. The keys stay on this phone. */
@Composable
fun OnlineRows() {
    val context = LocalContext.current
    val settings = context.ember.online.settings
    var giphy by remember { mutableStateOf(settings.giphyKey) }
    var imgur by remember { mutableStateOf(settings.imgurId) }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Text(
        "Open the face button in a chat, then Stickers or GIFs. Tap a result to send it. Press and hold to keep it in your favourites.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
    Heading("Included, no sign-up")
    Text(
        "Stickers from Signal's public packs and Google's animated emoji. GIFs from nekos.best and Wikimedia Commons.",
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Heading("Your own keys (optional)")
    Text(
        "Giphy and Imgur only work with a free key. Searches go from this phone straight to them, never through your Hearth server.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Heading("Giphy")
    KeyField(giphy, "Giphy API key", "Paste the key here") { giphy = it; settings.giphyKey = it }
    TextButton(onClick = { open("https://developers.giphy.com/dashboard/") }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Get a free Giphy key") }
    Heading("Imgur")
    KeyField(imgur, "Imgur client ID", "Paste the client ID here") { imgur = it; settings.imgurId = it }
    TextButton(onClick = { open("https://api.imgur.com/oauth2/addclient") }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Register for a free Imgur client ID") }
}

@Composable
private fun Heading(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun KeyField(value: String, label: String, hint: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, label = { Text(label) }, placeholder = { org.hearth.ember.ui.components.PlaceholderText(hint) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
