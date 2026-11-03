package org.hearth.ember

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.hearth.ember.ui.settings.BackupProgress
import org.hearth.ember.ui.theme.EmberTheme

/** Emulator visual check of the actual backup widget. Excluded from release APKs. */
class ProgressReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EmberTheme(dark = intent.getBooleanExtra("dark", false)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Backup progress", style = MaterialTheme.typography.titleLarge)
                        for (value in listOf(0f, 0.5f, 1f)) {
                            Text("${(value * 100).toInt()}%")
                            BackupProgress(progress = { value })
                        }
                    }
                }
            }
        }
    }
}
