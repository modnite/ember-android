package org.hearth.ember.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.hearth.ember.data.Room
import org.hearth.ember.data.User
import org.hearth.ember.ember
import org.hearth.ember.ui.theme.EmberTheme

fun parseColor(hex: String, fallback: Color = Color(0xFF7C6CFF)): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

private fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** A round picture: the uploaded one if there is one, else their initials on their own colour. */
@Composable
fun Avatar(name: String, picture: String?, color: Color, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val server = context.ember.store.server
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (picture != null) {
            val url = if (picture.startsWith("http")) picture else server + picture
            val request = remember(url) { ImageRequest.Builder(context).data(url).crossfade(true).build() }
            AsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(initials(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
    }
}

@Composable
fun UserAvatar(user: User?, size: Dp = 48.dp, showStatus: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier) {
        Avatar(user?.displayName ?: "?", user?.avatar, user?.let { parseColor(it.avatarColor) } ?: Color.Gray, size)
        if (showStatus && user != null && user.isOnline) {
            val badge = (size.value * 0.28f).dp
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(badge)
                    .clip(CircleShape)
                    .background(EmberTheme.colors.online)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
fun RoomAvatar(room: Room, meId: String, size: Dp = 52.dp, showStatus: Boolean = true, modifier: Modifier = Modifier) {
    if (room.isDm) {
        UserAvatar(room.other(meId), size, showStatus, modifier)
    } else {
        val seed = room.id.fold(0) { a, c -> a * 31 + c.code }
        val palette = listOf(0xFF7C6CFF, 0xFF2E9E8F, 0xFFD2691E, 0xFF3F7FD0, 0xFFC2477C, 0xFF6C8E23, 0xFF9B59B6)
        Avatar(room.name.ifBlank { "Group" }, room.avatar, Color(palette[Math.floorMod(seed, palette.size)]), size, modifier)
    }
}
