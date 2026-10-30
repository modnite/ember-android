package org.hearth.ember.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the others in a call are told, and what a phone says about itself. Pure, so it can be tested without a phone. */
object LowBattery {
    /** Battery percentages that raise a warning, highest first. */
    val STEPS = listOf(20, 15, 10, 5)
    const val TOPIC = "ember.battery"

    /**
     * Whether [level] has crossed a step that has not been announced yet. Several steps passed at once (21 to 14) give one warning for the lowest;
     * all of them are then spent. Charging, or climbing back above the first step, spends nothing and forgets what was announced.
     */
    fun crossed(level: Int, charging: Boolean, announced: MutableSet<Int>): Boolean {
        if (charging || level > STEPS.first()) {
            announced.clear()
            return false
        }
        val passed = STEPS.filter { level <= it && it !in announced }
        if (passed.isEmpty()) return false
        announced += passed
        return true
    }

    fun encode(level: Int, charging: Boolean): ByteArray =
        buildJsonObject { put("level", level); put("charging", charging) }.toString().toByteArray()

    /** (level, charging) from a message another phone sent, or null if it is not one. */
    fun decode(data: ByteArray): Pair<Int, Boolean>? = runCatching {
        val o: JsonObject = Json.parseToJsonElement(String(data)).jsonObject
        val level = o["level"]!!.jsonPrimitive.int
        if (level !in 0..100) return null
        level to (o["charging"]?.jsonPrimitive?.boolean ?: false)
    }.getOrNull()
}

/** Reports this phone's battery while it is registered: every change, as (percent, charging). */
class BatteryWatch(private val context: Context, private val onChange: (level: Int, charging: Boolean) -> Unit) {
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = report(intent)
        }
        receiver = r
        // The sticky broadcast answers at once with the current state.
        val sticky = context.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) report(sticky)
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun report(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        onChange(raw * 100 / scale, charging)
    }
}
