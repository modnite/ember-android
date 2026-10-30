package org.hearth.ember.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hearth.ember.data.Attachment
import org.hearth.ember.data.Message
import org.hearth.ember.ember

/**
 * The reactions that float beside the one message you picked: your most used six, and a plus for any emoji. It sits above the message, or
 * below when there is no room above, lined up with the bubble's edge. It does not take the screen's touches, so another tap can still pick more.
 */
@Composable
fun ReactionBar(mine: Boolean, quick: List<String>, anchor: androidx.compose.ui.geometry.Rect, onReact: (String) -> Unit, onMore: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(anchor, mine) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val above = anchor.top - popupContentSize.height - margin
                val below = anchor.bottom + margin
                val top = if (above >= with(density) { 80.dp.roundToPx() }) above else if (below + popupContentSize.height <= windowSize.height - margin) below else above.coerceAtLeast(margin.toFloat())
                val left = (if (mine) anchor.right - popupContentSize.width else anchor.left).toInt().coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                return androidx.compose.ui.unit.IntOffset(left, top.toInt())
            }
        }
    }
    androidx.compose.ui.window.Popup(popupPositionProvider = position, properties = androidx.compose.ui.window.PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 10.dp, tonalElevation = 2.dp) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (e in quick) Text(e, fontSize = 24.sp, modifier = Modifier.clip(CircleShape).clickable { onReact(e) }.padding(horizontal = 5.dp, vertical = 3.dp))
                Box(Modifier.padding(start = 2.dp).size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onMore), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "More reactions", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The top bar while messages are picked: how many, and what can be done with them. One message offers reply, copy, forward and delete, with
 * pin and edit under the three dots; several offer copy, forward and delete (only your own messages go).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(
    messages: List<Message>, meId: String, onClose: () -> Unit, onReply: () -> Unit, onCopy: () -> Unit, onForward: () -> Unit,
    onDelete: () -> Unit, onPin: () -> Unit, onEdit: () -> Unit,
) {
    val single = messages.singleOrNull()
    val live = messages.all { it.deletedAt == null }
    val anyMine = messages.any { it.userId == meId }
    val hasText = messages.any { it.body.isNotBlank() }
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel selection") } },
        title = { Text("${messages.size}", fontWeight = FontWeight.SemiBold) },
        actions = {
            if (single != null && live) IconButton(onClick = onReply) { Icon(Icons.AutoMirrored.Filled.Reply, "Reply") }
            if (live && anyMine) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            if (live && hasText) IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy") }
            if (live) IconButton(onClick = onForward) { Icon(Icons.Filled.Forward, "Forward") }
            if (single != null && live) Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("Pin") }, leadingIcon = { Icon(Icons.Filled.PushPin, null) }, onClick = { more = false; onPin() })
                    if (single.userId == meId && single.body.isNotBlank()) DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { more = false; onEdit() })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** A sticker from the chat, large, with what can be done with it: keep it in your favourites to send it yourself, or take it out again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerSheet(a: Attachment, roomId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    val bytes by produceState<ByteArray?>(null, a.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val raw = app.api.downloadAttachment(a.id)
                if (a.encrypted != 0) app.e2ee.openFile(roomId, a.envelope, raw) else raw
            }.getOrNull()
        }
    }
    val favourites by app.stickers.items.collectAsState()
    val b = bytes
    val saved = remember(b, favourites) { b != null && app.stickers.has(b) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (b != null) AsyncImage(model = coil.request.ImageRequest.Builder(context).data(b).build(), contentDescription = "Sticker", contentScale = ContentScale.Fit, modifier = Modifier.size(220.dp))
            else Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable(enabled = b != null) {
                    if (saved) app.stickers.remove(b!!) else app.stickers.add(b!!)
                    Toast.makeText(context, if (saved) "Removed from your favourites" else "Added to your favourites", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(20.dp))
                Text(if (saved) "Remove from favourites" else "Add to favourites", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * Before pictures or videos go out (picked from the gallery or just taken): a look at them, a caption, and Send. The caption goes with the
 * first one.
 */
@Composable
fun MediaPreview(uris: List<Uri>, onSend: (caption: String, quality: org.hearth.ember.data.Quality) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    var caption by remember(uris) { mutableStateOf("") }
    val hdByDefault by app.prefs.sendHd.collectAsState()
    var quality by remember(uris) { mutableStateOf(if (hdByDefault) org.hearth.ember.data.Quality.HD else org.hearth.ember.data.Quality.Standard) }
    val first = uris.first()
    val mime = remember(first) { context.contentResolver.getType(first).orEmpty() }
    val isVideo = mime.startsWith("video/")
    val isPicture = mime.startsWith("image/") && mime != "image/gif"
    val isOther = !isVideo && !mime.startsWith("image/")
    // What is about to be sent: its name and size, so the choice below means something.
    val info by produceState<Pair<String, Long>?>(null, first) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                var name = "file"
                var size = -1L
                context.contentResolver.query(first, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                    }
                }
                name to size
            }.getOrNull()
        }
    }
    val still by produceState<Bitmap?>(null, first) {
        if (isVideo) value = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(context, first); r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } finally { r.release() }
            }.getOrNull()
        }
    }
    fun megabytes(b: Long) = if (b >= 1_048_576) "%.1f MB".format(b / 1_048_576.0) else "${maxOf(1, b / 1024)} KB"
    FullScreenDialog(onCancel) {
        Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel", tint = Color.White) }
                if (uris.size > 1) Text("1 of ${uris.size}", color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 8.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    isVideo -> {
                        still?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(40.dp), tint = Color.White)
                        }
                    }
                    isOther -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Filled.AttachFile, null, tint = Color.White, modifier = Modifier.size(72.dp))
                        Text(info?.first.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp), maxLines = 3)
                        info?.second?.takeIf { it >= 0 }?.let { Text(megabytes(it), color = Color.White.copy(alpha = 0.7f)) }
                    }
                    else -> AsyncImage(model = first, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            // Standard or HD, as in the messengers: a smaller copy that arrives fast, or one that keeps its detail. Files and GIFs go as they are.
            if (isPicture || isVideo) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (q in org.hearth.ember.data.Quality.values()) {
                    androidx.compose.material3.FilterChip(
                        selected = quality == q, onClick = { quality = q }, label = { Text(if (q == org.hearth.ember.data.Quality.HD) "HD" else "Standard") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                val size = info?.second?.takeIf { it >= 0 }
                Text(
                    (if (quality == org.hearth.ember.data.Quality.HD) "Sharper, larger" else "Smaller, faster") + (size?.let { " · original ${megabytes(it)}" } ?: ""),
                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF2A2A2C), modifier = Modifier.weight(1f)) {
                    TextField(
                        value = caption, onValueChange = { caption = it }, placeholder = { org.hearth.ember.ui.components.PlaceholderText("Add a caption…", color = Color.White.copy(alpha = 0.5f)) }, maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { onSend(caption.trim(), quality) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/**
 * The stickers (or GIFs) you keep: a tile to add one (a sticker is made from any picture you choose; a GIF file is kept as it is), then the ones
 * you have, newest first. A tap sends one; a long press removes it from the favourites.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FavouritesGrid(sticker: Boolean, onSend: (java.io.File) -> Unit, gridMax: androidx.compose.ui.unit.Dp = 340.dp) {
    val context = LocalContext.current
    val app = context.ember
    val store = if (sticker) app.stickers else app.gifs
    val items by store.items.collectAsState()
    val scope = rememberCoroutineScope()
    fun added(file: java.io.File?) {
        Toast.makeText(context, if (file != null) (if (sticker) "Sticker added" else "GIF added") else if (sticker) "Could not make a sticker from that picture." else "That is not a GIF.", Toast.LENGTH_SHORT).show()
    }
    val pickSticker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { added(store.addStickerFrom(uri)) }
    }
    val pickGif = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { added(store.addFrom(uri)) }
    }
    Column(Modifier.fillMaxWidth()) {
        if (items.isEmpty()) Text(
            if (sticker) "No stickers yet. Make one from any picture with the plus, or tap a sticker someone sent you and add it to your favourites."
            else "No GIFs yet. Add a GIF file with the plus, or open a GIF in a chat and save it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(92.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = gridMax).padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Box(
                    Modifier.size(92.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { if (sticker) pickSticker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) else pickGif.launch("image/gif") },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, if (sticker) "Make a sticker" else "Add a GIF", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(items.size, key = { items[it].name }) { i ->
                val file = items[i]
                AsyncImage(
                    model = file, contentDescription = if (sticker) "Sticker" else "GIF", contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.size(92.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(
                        onClick = { onSend(file) },
                        onLongClick = { store.remove(file); Toast.makeText(context, "Removed from your favourites", Toast.LENGTH_SHORT).show() },
                    ),
                )
            }
        }
    }
}
