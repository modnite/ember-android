package org.hearth.ember.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.hearth.ember.data.Attachment
import org.hearth.ember.data.LinkPreview
import org.hearth.ember.data.Message
import org.hearth.ember.data.Room
import org.hearth.ember.ember
import org.hearth.ember.ui.clockTime
import org.hearth.ember.ui.components.UserAvatar
import org.hearth.ember.ui.components.parseColor
import org.hearth.ember.ui.fileSize
import org.hearth.ember.ui.theme.EmberTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val UrlPattern = Regex("""https?://[^\s<>"']+[^\s<>"'.,;:!?)]""")

/** One message in the chat: who, what, when, and how far it has got. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    room: Room,
    meId: String,
    firstOfGroup: Boolean,
    lastOfGroup: Boolean,
    highlighted: Boolean,
    selected: Boolean,
    onSelect: (androidx.compose.ui.geometry.Rect) -> Unit,
    onReply: () -> Unit,
    onLongPress: (androidx.compose.ui.geometry.Rect) -> Unit,
    onOpenImage: (Attachment) -> Unit,
    onOpenFile: (Attachment) -> Unit,
    onJumpTo: (String) -> Unit,
    onReact: (String) -> Unit,
) {
    val mine = message.userId == meId
    val colors = EmberTheme.colors
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val haptic = LocalHapticFeedback.current
    var drag by remember { mutableFloatStateOf(0f) }
    val showName = !mine && !room.isDm && firstOfGroup
    val shape = RoundedCornerShape(
        topStart = if (!mine && !firstOfGroup) 4.dp else 18.dp,
        topEnd = if (mine && !firstOfGroup) 4.dp else 18.dp,
        bottomStart = if (!mine && !lastOfGroup) 4.dp else if (!mine) 4.dp else 18.dp,
        bottomEnd = if (mine && !lastOfGroup) 4.dp else if (mine) 4.dp else 18.dp,
    )
    val base = if (mine) colors.bubbleMine else colors.bubbleTheirs
    // A picked message is plainly marked: a tint over the bubble, a ring round it, and a faint wash across its row.
    val accent = MaterialTheme.colorScheme.primary
    val bubbleColor = if (selected || highlighted) accent.copy(alpha = if (selected) 0.32f else 0.22f).compositeOver(base) else base
    val textColor = if (mine) colors.onBubbleMine else colors.onBubbleTheirs
    val app = LocalContext.current.ember
    val revision by app.e2ee.revision.collectAsState()
    // In an encrypted chat the body is ciphertext until this phone has the key; then it reads as normal text.
    val plain = remember(message.body, revision, room.encrypted) { if (message.body.isBlank()) "" else app.repo.textOf(room.id, message.body) }
    val encrypted = room.encrypted && message.body.isNotBlank() && plain == null

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(accent.copy(alpha = 0.10f)) else Modifier)
            .padding(horizontal = 8.dp, vertical = if (lastOfGroup) 2.dp else 0.5.dp)
            .offset { IntOffset(drag.roundToInt(), 0) }
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag > 90f) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onReply() }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, delta -> drag = (drag + delta).coerceIn(0f, 140f) }
            },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine && !room.isDm) {
            if (lastOfGroup) UserAvatar(message.user, 30.dp) else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            shape = shape, color = bubbleColor, contentColor = textColor, shadowElevation = 0.5.dp,
            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, accent) else null,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                // A tap or a long press picks the message (anything inside it that does something, like a picture or a link, takes its own taps first).
                // A tap picks the message (and shows what you can do with it); a long press starts picking several. Links, pictures and the voice
                // player inside take their own taps first.
                .combinedClickable(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(bounds) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(bounds) },
                )
                // With a mouse (DeX, desktop windows) a right click picks it too.
                .pointerInput(message.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.buttons.isSecondaryPressed) onLongPress(bounds)
                        }
                    }
                },
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (showName) {
                    Text(
                        message.user?.displayName ?: "Someone",
                        color = parseColor(message.user?.avatarColor ?: "#7c6cff").let { if (isSystemDark()) lighten(it) else it },
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (message.forwarded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(14.dp), tint = textColor.copy(alpha = 0.6f))
                        Spacer(Modifier.width(4.dp))
                        Text("Forwarded", style = MaterialTheme.typography.labelSmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = textColor.copy(alpha = 0.6f))
                    }
                }
                message.replyTo?.let { quote -> QuoteBlock(quote, mine, room, onClick = { onJumpTo(quote.id) }) }

                if (message.deletedAt != null) {
                    Text("This message was deleted", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = textColor.copy(alpha = 0.6f))
                } else {
                    for (attachment in message.attachments) AttachmentView(attachment, room.id, mine, onOpenImage, onOpenFile)
                    if (encrypted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = textColor.copy(alpha = 0.6f))
                            Spacer(Modifier.width(6.dp))
                            Text(if (app.e2ee.ready) "Encrypted message (older format)" else "Encrypted message", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = textColor.copy(alpha = 0.7f))
                        }
                    } else if (!plain.isNullOrBlank()) {
                        LinkedText(plain, textColor)
                        if (!room.encrypted) firstUrl(plain)?.let { LinkPreviewCard(it) }
                    }
                }
                Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (message.editedAt != null && message.deletedAt == null) {
                        Text("edited  ", fontSize = 11.sp, color = textColor.copy(alpha = 0.55f))
                    }
                    Text(clockTime(message.createdAt), fontSize = 11.sp, color = textColor.copy(alpha = 0.6f))
                    if (mine && message.deletedAt == null) {
                        Spacer(Modifier.width(3.dp))
                        Ticks(message, room, meId)
                    }
                }
                if (message.reactions.isNotEmpty()) ReactionRow(message, meId, onReact)
            }
        }
    }
}

@Composable
private fun isSystemDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

private fun lighten(c: Color): Color = Color(
    (c.red + (1f - c.red) * 0.35f), (c.green + (1f - c.green) * 0.35f), (c.blue + (1f - c.blue) * 0.35f), 1f,
)

private fun firstUrl(text: String): String? = UrlPattern.find(text)?.value

@Composable
private fun Ticks(message: Message, room: Room, meId: String) {
    val others = room.members.filter { it.id != meId }
    val readBy = room.reads.filter { r -> r.userId != meId && r.lastReadAt >= message.createdAt }
    val read = others.isNotEmpty() && readBy.size >= others.size
    Icon(
        if (readBy.isNotEmpty()) Icons.Filled.DoneAll else Icons.Filled.Done,
        contentDescription = if (read) "Read" else "Sent",
        modifier = Modifier.size(15.dp),
        tint = if (read) EmberTheme.colors.tickRead else EmberTheme.colors.tick,
    )
}

@Composable
private fun LinkedText(text: String, color: Color) {
    val linkColor = MaterialTheme.colorScheme.primary
    // Real links: only a touch on the link itself is taken (and opens it); a tap anywhere else on the words goes on to the message.
    val annotated = remember(text, linkColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            var last = 0
            for (match in UrlPattern.findAll(text)) {
                append(text.substring(last, match.range.first))
                val url = if (match.value.contains("://")) match.value else "https://" + match.value
                withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(match.value) }
                last = match.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(text = annotated, style = MaterialTheme.typography.bodyLarge.copy(color = color))
}

@Composable
private fun QuoteBlock(quote: org.hearth.ember.data.MessageQuote, mine: Boolean, room: Room, onClick: () -> Unit) {
    val app = LocalContext.current.ember
    val name = room.members.firstOrNull { it.id == quote.userId }?.displayName ?: app.repo.userName(quote.userId)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .background(Color.Black.copy(alpha = if (mine) 0.06f else 0.05f)).heightIn(min = 36.dp),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(name, color = accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            val quoted = remember(quote.body, room.encrypted) { if (quote.body.isBlank()) "" else app.repo.textOf(room.id, quote.body) ?: "Encrypted message" }
            Text(
                if (quote.deleted) "Message deleted" else quoted.ifBlank { "Attachment" },
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AttachmentView(a: Attachment, roomId: String, mine: Boolean, onOpenImage: (Attachment) -> Unit, onOpenFile: (Attachment) -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    val sealedMime = if (a.encrypted != 0 && a.envelope.isNotBlank()) app.e2ee.mimeOf(a.envelope) else null
    if (a.isAudio || sealedMime?.startsWith("audio/") == true) {
        VoiceBubble(a, roomId, mine)
    } else if (sealedMime?.startsWith("image/") == true) {
        EncryptedImage(a, roomId, onOpenImage)
    } else if (a.isImage) {
        val ratio = if ((a.width ?: 0) > 0 && (a.height ?: 0) > 0) (a.width!!.toFloat() / a.height!!).coerceIn(0.6f, 1.8f) else 1.33f
        val request = remember(a.id) { ImageRequest.Builder(context).data(app.store.server + "/api/attachments/" + a.id).crossfade(true).build() }
        AsyncImage(
            model = request, contentDescription = a.filename, contentScale = ContentScale.Crop,
            modifier = Modifier.padding(bottom = 4.dp).width(240.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).clickable { onOpenImage(a) },
        )
    } else {
        Row(
            Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.06f)).clickable { onOpenFile(a) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(if (a.isAudio) Icons.Filled.PlayArrow else Icons.Filled.AttachFile, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(a.filename.ifBlank { "File" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(max = 200.dp))
                Text(if (a.encrypted != 0) "Encrypted file" else fileSize(a.size), style = MaterialTheme.typography.bodySmall, color = LocalTextMuted())
            }
        }
    }
}

@Composable
private fun LocalTextMuted(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun LinkPreviewCard(url: String) {
    val app = LocalContext.current.ember
    var preview by remember(url) { mutableStateOf<LinkPreview?>(null) }
    LaunchedEffect(url) { preview = runCatching { app.api.preview(url) }.getOrNull() }
    val p = preview ?: return
    if (p.title == null && p.description == null) return
    val context = LocalContext.current
    Column(
        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.06f))
            .clickable { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
    ) {
        if (p.image != null) {
            AsyncImage(model = p.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(120.dp))
        }
        Column(Modifier.padding(8.dp)) {
            p.siteName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            p.title?.let { Text(it, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
            p.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ReactionRow(message: Message, meId: String, onReact: (String) -> Unit) {
    val grouped = message.reactions.groupBy { it.emoji }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((emoji, list) in grouped) {
            val mine = list.any { it.userId == meId }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onReact(emoji) },
            ) {
                Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 14.sp)
                    if (list.size > 1) Text(" ${list.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** A picture from an encrypted chat: fetched as ciphertext, opened here with the group key, never stored decrypted on the server. */
@Composable
private fun EncryptedImage(a: Attachment, roomId: String, onOpen: (Attachment) -> Unit) {
    val app = LocalContext.current.ember
    val revision by app.e2ee.revision.collectAsState()
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, a.id, revision) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val sealed = app.api.downloadAttachment(a.id)
                val clear = app.e2ee.openFile(roomId, a.envelope, sealed) ?: return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(clear, 0, clear.size)
            }.getOrNull()
        }
    }
    val ratio = if ((a.width ?: 0) > 0 && (a.height ?: 0) > 0) (a.width!!.toFloat() / a.height!!).coerceIn(0.6f, 1.8f) else 1.33f
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.padding(bottom = 4.dp).width(240.dp).aspectRatio(ratio).clip(shape).background(Color.Black.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            androidx.compose.foundation.Image(b.asImageBitmap(), a.filename, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Lock, null, tint = Color.Gray, modifier = Modifier.size(28.dp))
        }
    }
}
