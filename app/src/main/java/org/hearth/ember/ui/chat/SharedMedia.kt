package org.hearth.ember.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.hearth.ember.data.Attachment
import org.hearth.ember.data.RoomMediaItem
import org.hearth.ember.ember
import org.hearth.ember.ui.fileSize

private fun month(t: Long) = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(t))
private fun day(t: Long) = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))

/** What a sealed file really is: the real type is kept inside its envelope. */
private fun realMime(app: org.hearth.ember.EmberApp, a: RoomMediaItem): String =
    (if (a.encrypted != 0 && a.envelope.isNotBlank()) app.e2ee.mimeOf(a.envelope) else null) ?: a.mime

/** Everything shared in a chat, newest first: pictures and videos in a grid by month, and files and voice messages in a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMediaScreen(nav: NavHostController, roomId: String) {
    val context = LocalContext.current
    val app = context.ember
    val rooms by app.repo.rooms.collectAsState()
    val room = rooms.firstOrNull { it.id == roomId }
    val meId = app.repo.me.collectAsState().value?.id ?: ""
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RoomMediaItem>>(emptyList()) }
    var more by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var imageViewer by remember { mutableStateOf<Attachment?>(null) }
    var videoViewer by remember { mutableStateOf<Attachment?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        if (busy || !more) return
        busy = true
        try {
            val page = app.api.roomMedia(roomId, items.lastOrNull()?.createdAt)
            items = items + page.filter { p -> items.none { it.id == p.id } }
            more = page.size >= 60
            failed = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            more = false
        } finally {
            busy = false
        }
    }
    LaunchedEffect(roomId) { load() }

    val revision by app.e2ee.revision.collectAsState()
    val kinds = remember(items, revision) { items.associate { it.id to realMime(app, it) } }
    val usable = items.filter { !it.attachment.isSticker }
    val pictures = usable.filter { kinds[it.id].orEmpty().let { m -> m.startsWith("image/") || m.startsWith("video/") } }
    val files = usable.filter { it !in pictures }
    val shown = if (tab == 0) pictures else files
    // A tab that is short because the other kind filled the page keeps asking for the next page, up to a few.
    // Started from the screen's own scope, so the effect restarting (busy changes) does not cancel the request it started.
    LaunchedEffect(shown.size, busy, more, items.size) { if (!busy && more && shown.size < 18 && items.size < 300) scope.launch { load() } }

    fun nameOf(id: String) = if (id == meId) "You" else room?.members?.firstOrNull { it.id == id }?.let { it.displayName.ifBlank { it.username } } ?: app.repo.userName(id)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Shared in this chat", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pictures and videos") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Files and voice") })
        }
        val groups = shown.groupBy { month(it.createdAt) }
        if (tab == 0) {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 110.dp), modifier = Modifier.fillMaxSize()) {
                groups.forEach { (label, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                    }
                    items(list, key = { it.id }) { a ->
                        Cell(a, kinds[a.id].orEmpty(), roomId, onImage = { imageViewer = it }, onVideo = { videoViewer = it })
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Footer(shown.isEmpty(), busy, more, failed, "No pictures or videos shared yet.") { scope.launch { load() } } }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (label, list) ->
                    item {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                    }
                    columnItems(list, key = { it.id }) { a ->
                        FileRow(a, kinds[a.id].orEmpty(), roomId, nameOf(a.userId)) { scope.launch { openFile(context, a.attachment) { notice = it } } }
                    }
                }
                item { Footer(shown.isEmpty(), busy, more, failed, "No files or voice messages shared yet.") { scope.launch { load() } } }
            }
        }
    }
    notice?.let { n ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { notice = null }, text = { Text(n) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } })
    }
    imageViewer?.let { ImageViewer(it) { imageViewer = null } }
    videoViewer?.let { VideoViewer(it, roomId) { videoViewer = null } }
}

@Composable
private fun Footer(empty: Boolean, busy: Boolean, more: Boolean, failed: Boolean, emptyText: String, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (failed) Text("Could not load what was shared.", color = MaterialTheme.colorScheme.error)
        else if (busy) Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (empty && !more) Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (more) TextButton(onClick = onMore) { Text("Show older") }
    }
}

/** One picture or video in the grid: a square, with a play mark on a video and a lock on a sealed one. */
@Composable
private fun Cell(a: RoomMediaItem, mime: String, roomId: String, onImage: (Attachment) -> Unit, onVideo: (Attachment) -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    val att = a.attachment
    val video = mime.startsWith("video/")
    Box(Modifier.padding(1.dp).aspectRatio(1f).background(Color.Black.copy(alpha = 0.08f)).clickable { if (video) onVideo(att) else onImage(att) }, contentAlignment = Alignment.Center) {
        if (video) {
            val thumb by produceState<VideoThumbs.Thumb?>(null, a.id) { if (a.encrypted == 0) value = VideoThumbs.of(app, att) }
            val bitmap = thumb?.bitmap
            if (bitmap != null) androidx.compose.foundation.Image(bitmap.asImageBitmap(), a.filename, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (a.encrypted != 0) Icon(Icons.Filled.Lock, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Video", Modifier.size(18.dp), tint = Color.White)
            }
        } else if (a.encrypted != 0) {
            Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(0.dp))) { EncryptedImage(att, roomId, onImage, null) }
        } else {
            val request = remember(a.id) { ImageRequest.Builder(context).data(app.store.server + "/api/attachments/" + a.id).crossfade(true).build() }
            AsyncImage(model = request, contentDescription = a.filename, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** One file or voice message in the list. */
@Composable
private fun FileRow(a: RoomMediaItem, mime: String, roomId: String, who: String, onOpen: () -> Unit) {
    if (mime.startsWith("audio/")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            VoiceBubble(a.attachment, roomId, mine = false)
            Text("Voice message from $who · ${day(a.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (a.encrypted != 0 && mime.isBlank()) Icons.Filled.Lock else Icons.Filled.AttachFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(a.filename.ifBlank { "File" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("${fileSize(a.size)} · $who · ${day(a.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
