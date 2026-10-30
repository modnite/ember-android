package org.hearth.ember.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.hearth.ember.data.BackupActivity
import org.hearth.ember.data.BackupScheduler
import org.hearth.ember.ember

/** What is going on, as one thing every screen can show. Null when nothing is. */
@Composable
fun rememberBackupActivity(): State<BackupActivity?> = LocalContext.current.ember.backup.activity.collectAsState()

/** One line and a bar for whatever backup or restore is going on, to put inside a dialog or a page. Nothing when none is. */
@Composable
fun ActivityLine(modifier: Modifier = Modifier) {
    val a by LocalContext.current.ember.backup.activity.collectAsState()
    a?.let {
        Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/**
 * A strip across the top of every screen while a backup or restore runs. It says what the step is, opens the backup page when tapped, and, for
 * a backup, has the Cancel button: a backup only ever stops when it is cancelled here, on the backup page or from its notification.
 */
@Composable
fun BackupActivityBar(activity: BackupActivity, padStatusBar: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().then(if (padStatusBar) Modifier.statusBarsPadding() else Modifier)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (activity.restore) Icons.Filled.Restore else Icons.Filled.Backup, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(if (activity.restore) "Restoring" else "Backing up", style = MaterialTheme.typography.labelLarge)
                    Text(activity.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (activity.canCancel) TextButton(onClick = { BackupScheduler.cancel(context) }) { Text("Cancel") }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
