package org.hearth.ember.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/** The hint inside a text box: always one line, cut with an ellipsis when it does not fit (a big text size must not break "Message" in two). */
@Composable
fun PlaceholderText(text: String, color: Color = Color.Unspecified) {
    Text(text, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}
