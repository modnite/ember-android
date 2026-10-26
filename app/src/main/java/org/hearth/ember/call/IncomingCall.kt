package org.hearth.ember.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.hearth.ember.ember
import org.hearth.ember.ui.components.Avatar
import org.hearth.ember.ui.components.parseColor
import org.hearth.ember.ui.theme.EmberTheme

/** Someone is calling while Ember is open: a full screen, the way a phone call looks, with Answer and Decline. */
@Composable
fun IncomingCallScreen(onAnswer: (String) -> Unit) {
    val app = LocalContext.current.ember
    val ring by app.repo.ring.collectAsState()
    val users by app.repo.users.collectAsState()
    val call = ring ?: return
    // A call nobody picks up stops ringing by itself.
    LaunchedEffect(call.startedAt) {
        delay(45_000)
        if (app.repo.ring.value?.startedAt == call.startedAt) app.repo.dismissRing()
    }
    val user = users[call.fromUserId]
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize().background(EmberTheme.colors.callBackground).clickable(enabled = false) {}) {
        if (landscape) Row(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(call.fromName, user?.avatar, user?.let { parseColor(it.avatarColor) } ?: Color.Gray, 110.dp)
                Spacer(Modifier.height(16.dp))
                Text(call.fromName, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Ember call", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Action(Icons.Filled.CallEnd, "Decline", EmberTheme.colors.callDanger) {
                    app.realtime.declineCall(call.roomId); app.repo.dismissRing(); app.callNotifier.stop(call.roomId)
                }
                Action(Icons.Filled.Call, "Answer", EmberTheme.colors.online) { app.repo.dismissRing(); onAnswer(call.roomId) }
            }
        } else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(80.dp))
            Avatar(call.fromName, user?.avatar, user?.let { parseColor(it.avatarColor) } ?: Color.Gray, 140.dp)
            Spacer(Modifier.height(24.dp))
            Text(call.fromName, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Ember call", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action(Icons.Filled.CallEnd, "Decline", EmberTheme.colors.callDanger) {
                    app.realtime.declineCall(call.roomId)
                    app.repo.dismissRing()
                    app.callNotifier.stop(call.roomId)
                }
                Action(Icons.Filled.Call, "Answer", EmberTheme.colors.online) {
                    app.repo.dismissRing()
                    onAnswer(call.roomId)
                }
            }
        }
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text(label, color = Color.White, modifier = Modifier.padding(top = 8.dp))
    }
}
