package org.hearth.ember.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.hearth.ember.ember

/** Ember's own orange plus a handful of others; a custom colour can be typed in as well. */
private val Accents = listOf(
    "Ember" to null,
    "Blue" to 0xFF2F6FED.toInt(),
    "Teal" to 0xFF0F9D8A.toInt(),
    "Green" to 0xFF2E9E4F.toInt(),
    "Yellow" to 0xFFE0A100.toInt(),
    "Red" to 0xFFD93025.toInt(),
    "Pink" to 0xFFD6336C.toInt(),
    "Purple" to 0xFF7048E8.toInt(),
    "Graphite" to 0xFF607D8B.toInt(),
)

private fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6) return null
    return t.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
}

/** The accent colour row and its picker: the colour of buttons, your bubbles and highlights, everywhere in Ember. */
@Composable
fun AccentRow() {
    val app = LocalContext.current.ember
    val accent by app.prefs.accent.collectAsState()
    var open by remember { mutableStateOf(false) }
    var hex by remember { mutableStateOf("") }
    val name = Accents.firstOrNull { it.second == accent }?.first ?: "Custom"
    ActionRow(Icons.Filled.Palette, "Accent colour", name) { hex = ""; open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Accent colour") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in Accents.chunked(5)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((label, argb) in row) {
                            val shown = argb?.let { Color(it) } ?: Color(0xFFE8590C)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(
                                    Modifier.size(44.dp).clip(CircleShape).background(shown)
                                        .border(if (argb == accent) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { app.prefs.setAccent(argb); open = false },
                                )
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = hex, onValueChange = { hex = it }, singleLine = true, label = { Text("Your own (hex, like #2F6FED)") },
                    isError = hex.isNotBlank() && parseHex(hex) == null,
                )
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(enabled = parseHex(hex) != null, onClick = { app.prefs.setAccent(parseHex(hex)); open = false }) { Text("Use this colour") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
