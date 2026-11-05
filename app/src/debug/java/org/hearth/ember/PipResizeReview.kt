package org.hearth.ember

/** Shell-invoked multi-touch resize for the emulator review. Excluded from releases. */
class PipResizeReview : android.app.Instrumentation() {
    override fun onCreate(arguments: android.os.Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val down = android.os.SystemClock.uptimeMillis()
        val properties = Array(2) { android.view.MotionEvent.PointerProperties().apply { id = it; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
        fun send(action: Int, count: Int, distance: Float) {
            val coords = Array(count) { android.view.MotionEvent.PointerCoords().apply {
                x = 838f + (if (it == 0) -distance else distance)
                y = 1564f + (if (it == 0) -distance else distance)
                pressure = 1f; size = 1f
            } }
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            check(uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        try {
            send(android.view.MotionEvent.ACTION_DOWN, 1, 45f)
            send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, 45f)
            for (i in 1..15) { send(android.view.MotionEvent.ACTION_MOVE, 2, 45f + i * 8f); android.os.SystemClock.sleep(30) }
            send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, 165f)
            send(android.view.MotionEvent.ACTION_UP, 1, 165f)
            finish(android.app.Activity.RESULT_OK, android.os.Bundle())
        } catch (e: Exception) { finish(android.app.Activity.RESULT_CANCELED, android.os.Bundle().apply { putString("error", e.toString()) }) }
    }
}
