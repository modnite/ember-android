package org.hearth.ember

import android.app.PictureInPictureParams
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.hearth.ember.call.*
import org.hearth.ember.ui.theme.EmberTheme

/** Visual review only; no server, real call or microphone is opened. Not shipped. */
class CallReviewActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ember.callController!!.pipRatio.value = intent.getFloatExtra("ratio", 0.75f)
        setContent {
            EmberTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    if (inPip) Box(Modifier.fillMaxSize().background(Color(0xFF245866)), contentAlignment = Alignment.Center) { Text("Fit · full picture", color = Color.White) }
                    else Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Call layout review")
                        var muted by remember { mutableStateOf(false) }
                        var status by remember { mutableStateOf("") }
                        MiniCallCard(CallState(title = "Participant", micOn = !muted), 224.dp, Modifier.width(168.dp), "2:19",
                            onOpen = { status = "Return pressed" }, onMute = { muted = !muted }, onEnd = { status = "End pressed" }) {
                            Text("Camera off", color = Color.White)
                        }
                        Text(status)
                        Button(onClick = { enterPictureInPictureMode(params()) }) { Text("External PiP") }
                    }
                }
            }
        }
    }
    private fun params(): PictureInPictureParams = ember.callController!!.pipParams(this)
    override fun onPictureInPictureModeChanged(active: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(active, config); inPip = active
    }
}
