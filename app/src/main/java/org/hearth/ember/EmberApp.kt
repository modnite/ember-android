package org.hearth.ember

import kotlinx.coroutines.launch
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.hearth.ember.data.Api
import org.hearth.ember.data.PersistentCookieJar
import org.hearth.ember.data.Realtime
import org.hearth.ember.data.Repository
import org.hearth.ember.data.SessionStore
import org.hearth.ember.notify.CallNotifier
import org.hearth.ember.notify.Notifier
import org.hearth.ember.ui.Prefs
import java.util.concurrent.TimeUnit

/** Builds the app's long-lived parts once. There is no dependency-injection framework: there are only a handful of them. */
class EmberApp : Application(), ImageLoaderFactory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: SessionStore
    lateinit var client: OkHttpClient
    lateinit var api: Api
    lateinit var realtime: Realtime
    lateinit var repo: Repository
    lateinit var prefs: Prefs
    lateinit var notifier: Notifier
    lateinit var callNotifier: CallNotifier
    lateinit var e2ee: org.hearth.ember.data.E2ee
    /** On a wide screen (a phone on its side, a tablet) the chat opens beside the list instead of replacing it. */
    val selectedRoom = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Something another app asked to send through Ember; the "Send to" screen picks it up and clears it. */
    val share = kotlinx.coroutines.flow.MutableStateFlow<org.hearth.ember.ui.chat.SharePayload?>(null)
    val ringer by lazy { org.hearth.ember.notify.HeadsetRinger(this) }
    val stickers by lazy { org.hearth.ember.data.Favourites(this, org.hearth.ember.data.Favourites.Kind.Sticker) }
    val gifs by lazy { org.hearth.ember.data.Favourites(this, org.hearth.ember.data.Favourites.Kind.Gif) }
    @Volatile private var lastNudgeBuzz = 0L
    val online by lazy { org.hearth.ember.data.OnlineMedia(this, client) }
    val sounds by lazy { org.hearth.ember.notify.SoundPlayer(this) }
    val updater by lazy { org.hearth.ember.data.Updater(this, client, BuildConfig.UPDATE_REPO, BuildConfig.VERSION_NAME) }
    val backup by lazy { org.hearth.ember.data.BackupService(this, this) }
    var callController: org.hearth.ember.call.CallController? = null

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        client = OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar(store))
            // "localhost" always means this phone, even when a VPN's DNS cannot answer for it (a server reached through adb or a tunnel).
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    if (hostname == "localhost") listOf(java.net.InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1))) else okhttp3.Dns.SYSTEM.lookup(hostname)
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        api = Api(store, client)
        realtime = Realtime(store, client, scope)
        e2ee = org.hearth.ember.data.E2ee(store, api)
        repo = Repository(this, store, api, realtime, client, scope, e2ee)
        prefs = Prefs(this)
        notifier = Notifier(this)
        callNotifier = CallNotifier(this)
        callController = org.hearth.ember.call.CallController(this)
        notifier.createChannels()
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { org.hearth.ember.data.BackupScheduler.apply(this@EmberApp, backup.settings) }
        scope.launch { repo.ring.collect { if (it != null) ringer.start() else ringer.stop() } }
        // A nudge reaches you wherever you are in the app, not only in the chat it came from: the phone buzzes and plays the sound (louder, and on the
        // loudspeaker, during a call: someone whose phone is put down in the house can then be found). In the open chat itself the chat does it.
        scope.launch {
            repo.nudges.collect { (room, _) ->
                if (repo.viewingRoom == room && repo.appVisible) return@collect
                // However many arrive, the phone buzzes at most once every 1.5 seconds.
                val tick = System.currentTimeMillis()
                if (tick - lastNudgeBuzz < 1500) return@collect
                lastNudgeBuzz = tick
                val inCall = callController?.state?.value?.phase.let { it == org.hearth.ember.call.Phase.Connected || it == org.hearth.ember.call.Phase.Connecting }
                runCatching {
                    val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    val pattern = if (inCall) longArrayOf(0, 400, 150, 400, 150, 700) else longArrayOf(0, 90, 60, 90, 60, 160)
                    if (android.os.Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                    else @Suppress("DEPRECATION") vibrator?.vibrate(pattern, -1)
                }
                sounds.nudge(loud = inCall)
            }
        }
    }

    /** Pictures come from the Hearth server, which wants the session cookie: the same client serves them. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(client)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .components {
            // Animated pictures: GIFs everywhere, and animated WebP stickers where the phone can decode them.
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}

val android.content.Context.ember: EmberApp get() = applicationContext as EmberApp
