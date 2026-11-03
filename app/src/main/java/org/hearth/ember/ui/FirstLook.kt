package org.hearth.ember.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.hearth.ember.ember
import org.hearth.ember.ui.settings.Accents

@Composable
fun FirstLook() {
    val prefs = LocalContext.current.ember.prefs
    val theme by prefs.theme.collectAsState()
    val accent by prefs.accent.collectAsState()
    val dynamic by prefs.dynamicColor.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Make Ember yours", style = MaterialTheme.typography.headlineMedium)
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            for (row in ThemeMode.entries.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in row) OutlinedButton(onClick = { prefs.setTheme(mode) }, modifier = Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(if (theme == mode) 2.dp else 1.dp, if (theme == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Text(if (mode == ThemeMode.System) "Follow system" else mode.name)
                }
            }
            Text("Accent colour", style = MaterialTheme.typography.titleMedium)
            for (row in Accents.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                for ((name, value) in row) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp).clickable { prefs.setDynamicColor(false); prefs.setAccent(value) }.padding(4.dp)) {
                    Spacer(Modifier.size(44.dp).clip(CircleShape).background(value?.let { Color(it) } ?: Color(0xFFE8590C)).border(if (value == accent && !dynamic) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).semantics { contentDescription = name })
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (Build.VERSION.SDK_INT >= 31) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Phone colours", modifier = Modifier.weight(1f)); Switch(checked = dynamic, onCheckedChange = prefs::setDynamicColor)
            }
            Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ember", style = MaterialTheme.typography.titleMedium)
                Text("Hi there!", modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp))
                Text("Hello!", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary).padding(12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { prefs.setTheme(ThemeMode.System); prefs.setAccent(null); prefs.setDynamicColor(false); prefs.finishAppearanceSetup() }) { Text("Skip") }
                Button(onClick = prefs::finishAppearanceSetup) { Text("Continue") }
            }
        }
    }
}
