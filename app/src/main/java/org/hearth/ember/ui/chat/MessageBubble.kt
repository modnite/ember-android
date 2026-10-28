package org.hearth.ember.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

private val UrlPattern = Regex("""https?://[^\s<>"']+[^\s<>"'.,;:!?)]""")

/** What a picture or video looks like at rest: a column this wide, as tall as its shape asks for (within reason). */
private val MediaWidth = 260.dp

/** The words and parts of a message that decide how it is drawn. */
private class Shape(val sticker: Boolean, val mediaOnly: Boolean, val hasMedia: Boolean)

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
    selecting: Boolean,
    onSelect: (androidx.compose.ui.geometry.Rect) -> Unit,
    onReply: () -> Unit,
    onLongPress: (androidx.compose.ui.geometry.Rect) -> Unit,
    onOpenImage: (Attachment) -> Unit,
    onOpenVideo: (Attachment) -> Unit,
    onOpenSticker: (Attachment) -> Unit,
    onOpenFile: (Attachment) -> Unit,
    onForward: () -> Unit,
    onJumpTo: (String) -> Unit,
    onReact: (String) -> Unit,
) {
    val mine = message.userId == meId
    val colors = EmberTheme.colors
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val haptic = LocalHapticFeedback.current
    var drag by remember { mutableFloatStateOf(0f) }
    val showName = !mine && !room.isDm && firstOfGroup
    // A tail on the first bubble of a run (the sharp corner at the top, towards the sender); every other corner is soft.
    val soft = 14.dp
    val tail = 3.dp
    val shape = RoundedCornerShape(
        topStart = if (!mine && firstOfGroup) tail else soft, topEnd = if (mine && firstOfGroup) tail else soft,
        bottomStart = soft, bottomEnd = soft,
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
    val live = message.deletedAt == null
    val sealedKinds = message.attachments.map { a -> if (a.encrypted != 0 && a.envelope.isNotBlank()) app.e2ee.mimeOf(a.envelope) else null }
    val visual = message.attachments.withIndex().count { (i, a) -> a.isImage || a.isVideo || sealedKinds[i]?.startsWith("image/") == true || sealedKinds[i]?.startsWith("video/") == true }
    val hasText = encrypted || !plain.isNullOrBlank()
    val kind = Shape(
        sticker = live && message.attachments.size == 1 && message.attachments[0].isSticker && !hasText && message.replyTo == null && !message.forwarded,
        mediaOnly = live && visual > 0 && visual == message.attachments.size && !hasText && message.replyTo == null && !showName && !message.forwarded,
        hasMedia = visual > 0,
    )

    // The same touch handling whatever the message looks like (a bubble, or a sticker on its own).
    val touch = Modifier
        .onGloballyPositioned { bounds = it.boundsInWindow() }
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
        }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMediaTouch provides MediaTouch(
            selecting = selecting,
            select = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(bounds) },
            hold = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(bounds) },
        ),
    ) {
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
        // Pictures, videos and stickers have a round forward button beside them, on the side facing the middle of the screen.
        if (mine && kind.hasMedia || mine && kind.sticker) if (!selecting) QuickForward(onForward, Modifier.align(Alignment.CenterVertically))
        // Room under the bubble for a reaction pill that overlaps its lower edge.
        Box {
            Box(Modifier.padding(bottom = if (message.reactions.isNotEmpty()) 14.dp else 0.dp)) {
            if (kind.sticker) {
                Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).then(touch)) {
                        StickerView(message.attachments[0], room.id, selected, onOpenSticker)
                    }
                    TimePill(message, room, meId, Modifier.padding(top = 2.dp))
                }
            } else {
                Surface(
                    shape = shape, color = bubbleColor, contentColor = textColor, shadowElevation = 0.5.dp,
                    border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, accent) else null,
                    modifier = Modifier.widthIn(max = if (kind.hasMedia) MediaWidth + 6.dp else 320.dp).clip(shape).then(touch),
                ) {
                    // A bubble that holds a picture or video hugs it (a thin frame), the way a photo bubble does in WhatsApp.
                    Column(Modifier.padding(horizontal = if (kind.hasMedia) 3.dp else 10.dp, vertical = if (kind.hasMedia) 3.dp else 6.dp)) {
                        val inset = if (kind.hasMedia) Modifier.padding(horizontal = 7.dp) else Modifier
                        if (showName) {
                            Text(
                                message.user?.displayName ?: "Someone", modifier = inset.padding(top = if (kind.hasMedia) 3.dp else 0.dp),
                                color = parseColor(message.user?.avatarColor ?: "#7c6cff").let { if (isSystemDark()) lighten(it) else it },
                                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        if (message.forwarded) {
                            Row(inset, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(14.dp), tint = textColor.copy(alpha = 0.6f))
                                Spacer(Modifier.width(4.dp))
                                Text("Forwarded", style = MaterialTheme.typography.labelSmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = textColor.copy(alpha = 0.6f))
                            }
                        }
                        message.replyTo?.let { quote -> QuoteBlock(quote, mine, room, inset, onClick = { onJumpTo(quote.id) }) }

                        if (!live) {
                            Text("This message was deleted", modifier = inset, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = textColor.copy(alpha = 0.6f))
                        } else {
                            for ((i, attachment) in message.attachments.withIndex()) {
                                val overlay: (@Composable BoxScope.() -> Unit)? =
                                    if (kind.mediaOnly && i == message.attachments.lastIndex) { { TimePill(message, room, meId, Modifier.align(Alignment.BottomEnd).padding(6.dp)) } } else null
                                AttachmentView(attachment, sealedKinds[i], room.id, mine, onOpenImage, onOpenVideo, onOpenFile, overlay)
                            }
                            if (encrypted) {
                                Row(inset, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = textColor.copy(alpha = 0.6f))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (app.e2ee.ready) "Encrypted message (older format)" else "Encrypted message", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = textColor.copy(alpha = 0.7f))
                                }
                            } else if (!plain.isNullOrBlank()) {
                                Box(inset.padding(top = if (kind.hasMedia) 2.dp else 0.dp)) { LinkedText(plain, textColor) }
                                if (!room.encrypted) firstUrl(plain)?.let { LinkPreviewCard(it) }
                            }
                        }
                        if (!kind.mediaOnly) {
                            Row(inset.align(Alignment.End).padding(top = 2.dp, bottom = if (kind.hasMedia) 3.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (message.editedAt != null && live) Text("edited  ", fontSize = 11.sp, color = textColor.copy(alpha = 0.55f))
                                Text(clockTime(message.createdAt), fontSize = 11.sp, color = textColor.copy(alpha = 0.6f))
                                if (mine && live) {
                                    Spacer(Modifier.width(3.dp))
                                    Ticks(message, room, meId)
                                }
                            }
                        }
                    }
                }
            }
            }
            // The reaction pill sits across the bottom edge of the bubble, towards its outer side.
            if (message.reactions.isNotEmpty()) ReactionRow(
                message, meId, onReact,
                Modifier.align(if (mine) Alignment.BottomEnd else Alignment.BottomStart).padding(horizontal = 8.dp),
            )
        }
        if (!mine && kind.hasMedia || !mine && kind.sticker) if (!selecting) QuickForward(onForward, Modifier.align(Alignment.CenterVertically))
    }
    }
}

/** What a touch on a picture, video, sticker or file inside a bubble does besides opening it: while picking messages it picks this one, and a long press picks it too (so it can be reacted to). */
private class MediaTouch(val selecting: Boolean, val select: () -> Unit, val hold: () -> Unit)

private val LocalMediaTouch = androidx.compose.runtime.compositionLocalOf { MediaTouch(false, {}, {}) }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.mediaTouch(open: () -> Unit): Modifier {
    val touch = LocalMediaTouch.current
    return this.combinedClickable(onClick = { if (touch.selecting) touch.select() else open() }, onLongClick = touch.hold)
}

@Composable
private fun isSystemDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

private fun lighten(c: Color): Color = Color(
    (c.red + (1f - c.red) * 0.35f), (c.green + (1f - c.green) * 0.35f), (c.blue + (1f - c.blue) * 0.35f), 1f,
)

private fun firstUrl(text: String): String? = UrlPattern.find(text)?.value

/** The round forward button beside a picture, video or sticker: one tap to send it on. */
@Composable
private fun QuickForward(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(horizontal = 6.dp, vertical = 4.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Forward, "Forward", Modifier.size(18.dp), tint = Color.White) }
}

/** The time (and the ticks, for your own) on a small dark pill: over a picture, or under a sticker, where there is no bubble to hold it. */
@Composable
private fun TimePill(message: Message, room: Room, meId: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clockTime(message.createdAt), fontSize = 11.sp, color = Color.White)
        if (message.userId == meId && message.deletedAt == null) {
            Spacer(Modifier.width(3.dp))
            Ticks(message, room, meId, onDark = true)
        }
    }
}

@Composable
private fun Ticks(message: Message, room: Room, meId: String, onDark: Boolean = false) {
    val others = room.members.filter { it.id != meId }
    val readBy = room.reads.filter { r -> r.userId != meId && r.lastReadAt >= message.createdAt }
    val read = others.isNotEmpty() && readBy.size >= others.size
    Icon(
        if (readBy.isNotEmpty()) Icons.Filled.DoneAll else Icons.Filled.Done,
        contentDescription = if (read) "Read" else "Sent",
        modifier = Modifier.size(15.dp),
        tint = if (read) EmberTheme.colors.tickRead else if (onDark) Color.White.copy(alpha = 0.85f) else EmberTheme.colors.tick,
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
private fun QuoteBlock(quote: org.hearth.ember.data.MessageQuote, mine: Boolean, room: Room, modifier: Modifier, onClick: () -> Unit) {
    val app = LocalContext.current.ember
    val name = room.members.firstOrNull { it.id == quote.userId }?.displayName ?: app.repo.userName(quote.userId)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
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

private fun ratioOf(a: Attachment): Float =
    if ((a.width ?: 0) > 0 && (a.height ?: 0) > 0) (a.width!!.toFloat() / a.height!!).coerceIn(0.6f, 1.8f) else 1.33f

/**
 * One attachment: a voice note, a picture (a GIF moves), a video with a play button, or a file. [overlay] is drawn over the corner of a
 * picture or video, which is where the time goes when the message has no words.
 */
@Composable
private fun AttachmentView(
    a: Attachment, sealedMime: String?, roomId: String, mine: Boolean,
    onOpenImage: (Attachment) -> Unit, onOpenVideo: (Attachment) -> Unit, onOpenFile: (Attachment) -> Unit,
    overlay: (@Composable BoxScope.() -> Unit)?,
) {
    val context = LocalContext.current
    val app = context.ember
    val shape = RoundedCornerShape(11.dp)
    if (a.isAudio || sealedMime?.startsWith("audio/") == true) {
        VoiceBubble(a, roomId, mine)
    } else if (a.isVideo || sealedMime?.startsWith("video/") == true) {
        VideoCard(a, roomId, overlay) { onOpenVideo(a) }
    } else if (sealedMime?.startsWith("image/") == true) {
        EncryptedImage(a, roomId, onOpenImage, overlay)
    } else if (a.isImage) {
        val request = remember(a.id) { ImageRequest.Builder(context).data(app.store.server + "/api/attachments/" + a.id).crossfade(true).build() }
        Box(Modifier.padding(bottom = 2.dp).width(MediaWidth).aspectRatio(ratioOf(a)).clip(shape).mediaTouch { onOpenImage(a) }) {
            AsyncImage(model = request, contentDescription = a.filename, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (a.mime == "image/gif") Badge("GIF", Modifier.align(Alignment.TopStart).padding(6.dp))
            overlay?.invoke(this)
        }
    } else {
        Row(
            Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.06f)).mediaTouch { onOpenFile(a) }.padding(10.dp),
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
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 1.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

/** A video in the chat: a still from its start, a play button, and how long it runs. A tap opens the player. */
@Composable
private fun VideoCard(a: Attachment, roomId: String, overlay: (@Composable BoxScope.() -> Unit)?, onOpen: () -> Unit) {
    val app = LocalContext.current.ember
    val thumb by produceState<VideoThumbs.Thumb?>(null, a.id) { if (a.encrypted == 0) value = VideoThumbs.of(app, a) }
    val bitmap = thumb?.bitmap
    val ratio = if (bitmap != null) (bitmap.width.toFloat() / bitmap.height).coerceIn(0.6f, 1.8f) else 1.78f
    Box(Modifier.padding(bottom = 2.dp).width(MediaWidth).aspectRatio(ratio).clip(RoundedCornerShape(11.dp)).background(Color(0xFF1C1C1E)).mediaTouch(onOpen)) {
        if (bitmap != null) androidx.compose.foundation.Image(bitmap.asImageBitmap(), a.filename, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else if (a.encrypted != 0) Icon(Icons.Filled.Lock, null, Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp), tint = Color.White.copy(alpha = 0.6f))
        Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, "Play video", Modifier.size(32.dp), tint = Color.White)
        }
        val ms = thumb?.durationMs ?: 0L
        Badge(if (ms > 0) org.hearth.ember.ui.callTimer(ms) else fileSize(a.size), Modifier.align(Alignment.BottomStart).padding(6.dp))
        overlay?.invoke(this)
    }
}

/** A sticker: the picture on its own, no bubble, a little larger than an emoji. A tap picks the message; the picture itself opens its sheet. */
@Composable
private fun StickerView(a: Attachment, roomId: String, selected: Boolean, onOpen: (Attachment) -> Unit) {
    val context = LocalContext.current
    val app = context.ember
    val modifier = Modifier.size(156.dp).then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) else Modifier)
    if (a.encrypted == 0) {
        val request = remember(a.id) { ImageRequest.Builder(context).data(app.store.server + "/api/attachments/" + a.id).build() }
        AsyncImage(model = request, contentDescription = "Sticker", contentScale = ContentScale.Fit, modifier = modifier.mediaTouch { onOpen(a) })
    } else {
        val revision by app.e2ee.revision.collectAsState()
        val bytes by produceState<ByteArray?>(null, a.id, revision) {
            value = withContext(Dispatchers.IO) { runCatching { app.e2ee.openFile(roomId, a.envelope, app.api.downloadAttachment(a.id)) }.getOrNull() }
        }
        val b = bytes
        if (b != null) AsyncImage(
            model = ImageRequest.Builder(context).data(b).build(), contentDescription = "Sticker", contentScale = ContentScale.Fit,
            modifier = modifier.mediaTouch { onOpen(a) },
        ) else Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Lock, null, tint = Color.Gray) }
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

/** What people reacted with, as one small rounded pill across the lower edge of the bubble (emoji side by side, a count when more than one person used it). */
@Composable
private fun ReactionRow(message: Message, meId: String, onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    val grouped = message.reactions.groupBy { it.emoji }
    val mineAny = message.reactions.any { it.userId == meId }
    Surface(
        shape = RoundedCornerShape(14.dp), shadowElevation = 2.dp, modifier = modifier.clip(RoundedCornerShape(14.dp)),
        color = if (mineAny) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, EmberTheme.colors.chatBackground),
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((emoji, list) in grouped) {
                Row(Modifier.clickable { onReact(emoji) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 15.sp)
                    if (list.size > 1) Text(" ${list.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** A picture from an encrypted chat: fetched as ciphertext, opened here with the group key, never stored decrypted on the server. */
@Composable
private fun EncryptedImage(a: Attachment, roomId: String, onOpen: (Attachment) -> Unit, overlay: (@Composable BoxScope.() -> Unit)?) {
    val app = LocalContext.current.ember
    val revision by app.e2ee.revision.collectAsState()
    val bitmap by produceState<android.graphics.Bitmap?>(null, a.id, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val sealed = app.api.downloadAttachment(a.id)
                val clear = app.e2ee.openFile(roomId, a.envelope, sealed) ?: return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(clear, 0, clear.size)
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(11.dp)
    Box(Modifier.padding(bottom = 2.dp).width(MediaWidth).aspectRatio(ratioOf(a)).clip(shape).background(Color.Black.copy(alpha = 0.08f)).mediaTouch { onOpen(a) }, contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            androidx.compose.foundation.Image(b.asImageBitmap(), a.filename, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Lock, null, tint = Color.Gray, modifier = Modifier.size(28.dp))
        }
        overlay?.invoke(this)
    }
}
