package org.hearth.ember.ui.chat

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hearth.ember.data.MediaKind
import org.hearth.ember.data.OnlineItem
import org.hearth.ember.data.OnlineMedia
import org.hearth.ember.ember

private sealed interface Results {
    data object Loading : Results
    data class Failed(val message: String) : Results
    data class Found(val items: List<OnlineItem>) : Results
}

/**
 * Stickers or GIFs: the ones you keep, and a search of the places that have them. A tap sends, a long press keeps a result among your
 * favourites. Giphy and Imgur need a free key of your own (Settings → GIFs and stickers); the other places work as they are.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPicker(
    kind: MediaKind, onSendFavourite: (File) -> Unit, onSendOnline: (OnlineItem) -> Unit, onSendBytes: (ByteArray, String, String) -> Unit, onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.ember
    val scope = rememberCoroutineScope()
    val sources = remember(kind) { app.online.sourcesFor(kind) }
    val favourites by (if (kind == MediaKind.Sticker) app.stickers else app.gifs).items.collectAsState()
    // Start on what you keep, or on the first place that can be searched when nothing is kept yet.
    var selected by remember(kind) { mutableStateOf(if (favourites.isNotEmpty()) "saved" else sources.firstOrNull { it.ready() }?.id ?: "saved") }
    var query by remember(kind) { mutableStateOf("") }
    var results by remember(kind) { mutableStateOf<Results>(Results.Found(emptyList())) }
    val source = sources.firstOrNull { it.id == selected }

    LaunchedEffect(selected, query, source) {
        if (source == null || !source.ready() || source is org.hearth.ember.data.SignalSource) return@LaunchedEffect
        results = Results.Loading
        if (query.isNotBlank()) delay(400)
        results = try {
            Results.Found(source.search(kind, query.trim()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Results.Failed(e.message ?: "The search failed.")
        }
    }

    fun keepBytes(bytes: ByteArray) {
        if (kind == MediaKind.Sticker) app.stickers.add(bytes) else app.gifs.add(bytes)
        Toast.makeText(context, "Added to your favourites", Toast.LENGTH_SHORT).show()
    }

    fun keep(item: OnlineItem) {
        scope.launch {
            val saved = runCatching {
                val bytes = app.online.download(item)
                if (kind == MediaKind.Sticker) app.stickers.add(bytes) else app.gifs.add(bytes)
            }
            Toast.makeText(context, if (saved.isSuccess) "Added to your favourites" else saved.exceptionOrNull()?.message ?: "Could not save that.", Toast.LENGTH_SHORT).show()
        }
    }

    // The grid takes what the screen can spare, so the sheet still fits sideways.
    val gridMax = (LocalConfiguration.current.screenHeightDp * 0.42f).dp.coerceIn(150.dp, 360.dp)
    val tile = if (LocalConfiguration.current.screenHeightDp < 500) 76.dp else 92.dp

    Column(Modifier.fillMaxWidth().heightIn(min = gridMax + 150.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == "saved", onClick = { selected = "saved" }, label = { Text("Saved") })
            for (s in sources) FilterChip(selected = selected == s.id, onClick = { selected = s.id }, label = { Text(s.label) })
        }
        if (source == null) {
            FavouritesGrid(sticker = kind == MediaKind.Sticker, onSend = onSendFavourite, gridMax = gridMax)
            return@Column
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(if (source is org.hearth.ember.data.SignalSource) "Search packs (cat, meme, hello…)" else "Search ${source.label}") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (!source.ready()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    "${source.label} needs a free key of your own before it can be searched. It takes a minute to get one.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings) { Text("Add your key") }
            }
            return@Column
        }
        if (source is org.hearth.ember.data.SignalSource) {
            SignalPicker(source.signal, query, tile, gridMax, onSendBytes, ::keepBytes)
            return@Column
        }
        when (val r = results) {
            Results.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Results.Failed -> Text(r.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            is Results.Found -> if (r.items.isEmpty()) Text(
                "Nothing found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
            ) else LazyVerticalGrid(
                columns = GridCells.Adaptive(tile),
                modifier = Modifier.fillMaxWidth().heightIn(max = gridMax).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 16.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(r.items.size, key = { r.items[it].id }) { i ->
                    val item = r.items[i]
                    val modifier = Modifier.size(tile).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { onSendOnline(item) }, onLongClick = { keep(item) })
                    if (item.glyph != null) Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Text(item.glyph, fontSize = 40.sp) }
                    else AsyncImage(
                        // Some hosts (Wikimedia) turn away a request that does not say who it is from.
                        model = coil.request.ImageRequest.Builder(context).data(item.previewUrl).addHeader("User-Agent", "Ember/0.2 (Hearth messenger)").crossfade(true).build(), contentDescription = item.title.ifBlank { if (kind == MediaKind.Sticker) "Sticker" else "GIF" },
                        contentScale = if (kind == MediaKind.Sticker) ContentScale.Fit else ContentScale.Crop,
                        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
            }
        }
    }
}

/** What an online result is sent as: the downloaded bytes under a name every client reads right (stickers keep the sticker- prefix). */
suspend fun OnlineMedia.fetchForSending(item: OnlineItem, kind: MediaKind): Triple<ByteArray, String, String> =
    Triple(download(item), OnlineMedia.fileName(item, kind), OnlineMedia.mime(item))
