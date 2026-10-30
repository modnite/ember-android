package org.hearth.ember.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import org.hearth.ember.MainActivity
import org.hearth.ember.R
import org.hearth.ember.data.PushPayload
import org.hearth.ember.ember

/**
 * What Ember says out loud. Kept deliberately quiet: one notification per chat that updates in place (it does not buzz again for each line),
 * nothing while you are looking at that chat, and a separate loud channel only for calls.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** The channels in use now (they change with the chosen sounds). */
    var messagesChannel = CHANNEL_MESSAGES
        private set
    var callsChannel = CHANNEL_CALLS
        private set

    private fun soundKey(choice: String?) = when (choice) { null -> "ember1"; "silent" -> "silent"; else -> Integer.toHexString(choice.hashCode()) }
    private fun soundUri(choice: String?, type: Int): android.net.Uri? = when (choice) {
        // Ember's own chime and ringtone unless another sound was chosen.
        null -> android.net.Uri.parse("android.resource://" + context.packageName + "/" + (if (type == android.media.RingtoneManager.TYPE_RINGTONE) R.raw.ember_ring else R.raw.ember_notify))
        "silent" -> null
        else -> android.net.Uri.parse(choice)
    }

    /** A test message, so the sound and look can be checked from the settings. */
    fun test() {
        if (!allowed()) return
        val n = NotificationCompat.Builder(context, messagesChannel)
            .setSmallIcon(R.drawable.ic_stat_ember).setContentTitle("Ember").setContentText("This is a test notification")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFE8590C.toInt()).setContentIntent(openChat(null)).build()
        manager.notify(TEST_ID, n)
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val system = context.getSystemService(NotificationManager::class.java)
        // A channel's sound cannot be changed once it exists, so each choice of sound gets its own channel and the old ones are removed.
        val prefs = context.ember.prefs
        val messageChoice = prefs.messageSound.value
        messagesChannel = "messages_" + soundKey(messageChoice)
        callsChannel = "calls_" + soundKey(prefs.callSound.value)
        for (c in system.notificationChannels) if ((c.id.startsWith("messages") && c.id != messagesChannel) || (c.id.startsWith("calls") && c.id != callsChannel)) system.deleteNotificationChannel(c.id)
        system.createNotificationChannel(
            NotificationChannel(messagesChannel, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your chats"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 220, 110, 220)
                setSound(soundUri(messageChoice, android.media.RingtoneManager.TYPE_NOTIFICATION), android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            },
        )
        system.createNotificationChannel(
            NotificationChannel(callsChannel, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming calls"
                enableVibration(true)
                setBypassDnd(false)
                setSound(soundUri(prefs.callSound.value, android.media.RingtoneManager.TYPE_RINGTONE), android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            },
        )
        // The older "service" channel was a minimised one that still showed an icon. This one is created switched off: Android keeps the
        // connection running but shows nothing for it, not even a silent notification.
        system.deleteNotificationChannel("service")
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Connection (hidden)", NotificationManager.IMPORTANCE_NONE).apply {
                description = "Keeps Ember connected so messages arrive on time. Android shows it anyway, as quietly as it can; Settings, Notifications can turn it off for checks every 15 minutes."
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGES, "Nudges", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone nudges you"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 90, 60, 90, 60, 160)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a newer version of Ember is ready to install"
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_BATTERY, "Low battery in a call", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns you while you are on a call and your battery is low"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you are on a call"
                setShowBadge(false)
            },
        )
    }

    private fun allowed() =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openChat(roomId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .putExtra(EXTRA_ROOM, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, (roomId ?: "").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Your own battery is low during a call. It replaces itself at each new level and goes away when you plug in. */
    fun lowBattery(level: Int) {
        if (!allowed()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_BATTERY)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setContentTitle("Battery at $level%")
            .setContentText("Plug in now and use a fast charger.")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(context, 7311, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFE8590C.toInt())
            .build()
        manager.notify(BATTERY_ID, notification)
    }

    fun clearLowBattery() = manager.cancel(BATTERY_ID)

    /** A nudge for a chat: a short strong buzz and a card that says who, with no sound. */
    fun nudge(payload: PushPayload) {
        if (!allowed()) return
        val roomId = payload.roomId ?: return
        val who = payload.title.removeSuffix(" nudged you").ifBlank { "Someone" }
        val notification = NotificationCompat.Builder(context, CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setContentTitle("$who nudged you")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(roomId))
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFFE8590C.toInt())
            .build()
        manager.notify("nudge:$roomId", NUDGE_ID, notification)
    }

    /** A message for a chat. [showText] false hides the words ("New message") for lock-screen privacy. */
    fun message(payload: PushPayload, showText: Boolean) {
        if (!allowed()) return
        val roomId = payload.roomId ?: return
        val id = roomId.hashCode()
        val who = Person.Builder().setName(payload.title.ifBlank { "Ember" }).setKey(roomId).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
        val previous = lines[roomId].orEmpty()
        val text = if (showText) payload.body else "New message"
        val all = (previous + text).takeLast(6)
        lines[roomId] = all
        for (line in all) style.addMessage(line, System.currentTimeMillis(), who)

        val reply = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ROOM, roomId)
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_ember, "Reply",
            PendingIntent.getBroadcast(context, id, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
        ).addRemoteInput(reply).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()
        val readIntent = Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_READ).putExtra(EXTRA_ROOM, roomId)
        val readAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_ember, "Mark as read",
            PendingIntent.getBroadcast(context, id + 1, readIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()

        val notification = NotificationCompat.Builder(context, messagesChannel)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setStyle(style)
            .setContentTitle(payload.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(roomId))
            .setAutoCancel(true)
            .setGroup(GROUP)
            // Every new message makes its sound and buzz, even when that chat's notification is still showing.
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .addAction(readAction)
            .setColor(0xFFE8590C.toInt())
            .build()
        manager.notify(roomId, id, notification)
        val summary = NotificationCompat.Builder(context, messagesChannel)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFFE8590C.toInt())
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    fun cancelRoom(roomId: String) {
        lines.remove(roomId)
        manager.cancel(roomId, roomId.hashCode())
        if (lines.isEmpty()) manager.cancel(SUMMARY_ID)
    }

    fun cancelAll() {
        lines.clear()
        manager.cancelAll()
    }

    /** The persistent notification the receiver service needs. Silent and minimal. */
    fun serviceNotification() = NotificationCompat.Builder(context, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_quiet)
        .setContentTitle("Ember")
        .setContentText("Waiting for messages")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setShowWhen(false)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openChat(null))
        .build()

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_SERVICE = "link"
        const val CHANNEL_NUDGES = "nudges"
        const val CHANNEL_BATTERY = "battery"
        const val BATTERY_ID = 7311
        const val CHANNEL_ONGOING = "ongoing"
        const val CHANNEL_UPDATES = "updates"
        const val GROUP = "ember_chats"
        const val SUMMARY_ID = 1
        const val NUDGE_ID = 5
        const val TEST_ID = 6
        const val ACTION_OPEN_CHAT = "org.hearth.ember.OPEN_CHAT"
        const val ACTION_REPLY = "org.hearth.ember.REPLY"
        const val ACTION_READ = "org.hearth.ember.READ"
        const val EXTRA_ROOM = "room"
        const val KEY_REPLY = "reply"
        private val lines = HashMap<String, List<String>>()
    }
}
