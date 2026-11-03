package org.hearth.ember.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Same continuous fill as the update download; no Material end marker or gap. */
@Composable
fun BackupProgress(modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    if (progress == null) {
        LinearProgressIndicator(modifier)
    } else {
        val value = progress().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(value).background(MaterialTheme.colorScheme.primary))
        }
    }
}
