package org.hearth.ember.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.hearth.ember.data.SignalFormat
import org.hearth.ember.data.SignalManifest
import org.hearth.ember.data.SignalPack
import org.hearth.ember.data.SignalStickers

private sealed interface Packs {
    data object Loading : Packs
    data class Failed(val message: String) : Packs
    data class Found(val packs: List<SignalPack>) : Packs
}

@Composable
private fun Picture(bytes: ByteArray?, modifier: Modifier) {
    val context = LocalContext.current
    if (bytes == null) Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
    else AsyncImage(
        model = remember(bytes) { coil.request.ImageRequest.Builder(context).data(ByteBuffer.wrap(bytes)).build() },
        contentDescription = "Sticker", contentScale = ContentScale.Fit, modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/**
 * Signal's public sticker packs: search by what they are about (cat, meme, hello…), open a pack, tap a sticker to send it, press and hold to keep it.
 * The packs are community made; nothing about you is sent to find or fetch them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SignalPicker(signal: SignalStickers, query: String, tile: Dp, gridMax: Dp, onSend: (ByteArray, String, String) -> Unit, onKeep: (ByteArray) -> Unit) {
    var open by remember { mutableStateOf<SignalPack?>(null) }
    var packs by remember { mutableStateOf<Packs>(Packs.Loading) }
    LaunchedEffect(query) {
        packs = Packs.Loading
        if (query.isNotBlank()) delay(400)
        packs = try {
            Packs.Found(signal.search(query))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Packs.Failed(e.message ?: "The search failed.")
        }
    }
    val pack = open
    if (pack != null) {
        val manifest by produceState<SignalManifest?>(null, pack.id) { value = runCatching { signal.manifest(pack) }.getOrNull() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the packs") }
            Column(Modifier.weight(1f)) {
                Text(manifest?.title?.ifBlank { null } ?: "Sticker pack", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                manifest?.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
        val stickers = manifest?.stickers
        if (stickers == null) Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(tile), modifier = Modifier.fillMaxWidth().heightIn(max = gridMax).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(stickers.size, key = { stickers[it].id }) { i ->
                val s = stickers[i]
                val bytes by produceState<ByteArray?>(null, pack.id, s.id) { value = runCatching { signal.sticker(pack, s.id) }.getOrNull() }
                val b = bytes
                Picture(b, Modifier.size(tile).clip(RoundedCornerShape(14.dp)).combinedClickable(
                    enabled = b != null,
                    onClick = { if (b != null) onSend(b, "sticker-signal-${pack.id.take(8)}-${s.id}.${SignalFormat.extension(b)}", if (SignalFormat.extension(b) == "png") "image/png" else "image/webp") },
                    onLongClick = { if (b != null) onKeep(b) },
                ))
            }
        }
        return
    }
    when (val r = packs) {
        Packs.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Packs.Failed -> Text(r.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
        is Packs.Found -> if (r.packs.isEmpty()) Text(
            "No packs for that. Try cat, meme, hello, love, work…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
        ) else LazyVerticalGrid(
            columns = GridCells.Adaptive(tile), modifier = Modifier.fillMaxWidth().heightIn(max = gridMax).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(r.packs.size, key = { r.packs[it].id }) { i ->
                val p = r.packs[i]
                val manifest by produceState<SignalManifest?>(null, p.id) { value = runCatching { signal.manifest(p) }.getOrNull() }
                val cover by produceState<ByteArray?>(null, p.id, manifest) { value = manifest?.let { m -> runCatching { signal.sticker(p, m.cover) }.getOrNull() } }
                Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { open = p }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Picture(cover, Modifier.size(tile).clip(RoundedCornerShape(14.dp)))
                    Text(
                        manifest?.title.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(tile).padding(top = 2.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}
