package org.hearth.ember.call

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import org.hearth.ember.ember

/** A camera the person can pick: the front one, or one of the rear lenses (ultrawide, main, telephoto). */
data class Lens(val id: String, val label: String, val cameraId: String, val zoom: Float, val front: Boolean)

object Lenses {
    /**
     * What this phone offers. Phones expose their rear lenses as one "logical" camera whose zoom ratio switches between the ultrawide, main and
     * telephoto lenses; Android's own camera app does the same. The lowest-numbered front camera is the real one: the cropped second front
     * camera that some phones list is never offered.
     */
    fun list(context: Context): List<Lens> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = ArrayList<Lens>()
        var front: String? = null
        var back: String? = null
        var backLow = 1f
        var backHigh = 1f
        var bestHigh = -1f
        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> if (front == null) front = id
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        var low = 1f
                        var high = 1f
                        if (Build.VERSION.SDK_INT >= 30) {
                            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { low = it.lower; high = it.upper }
                        } else {
                            high = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                        }
                        if (high > bestHigh) { bestHigh = high; back = id; backLow = low; backHigh = high }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Lenses", "Cannot list cameras", e)
        }
        front?.let { out += Lens("front", "Front", it, 1f, true) }
        back?.let {
            if (backLow <= 0.75f) out += Lens("uw", "Ultrawide ${trim(backLow)}x", it, backLow, false)
            out += Lens("main", "Main 1x", it, 1f, false)
            if (backHigh >= 2.9f) out += Lens("tele", "Telephoto 3x", it, 3f, false)
        }
        return out
    }

    private fun trim(z: Float) = if (z == Math.floor(z.toDouble()).toFloat()) z.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", z)
}

/**
 * A WebRTC video source that is the phone's own camera, run by Camera2 directly. LiveKit's built-in camera cannot choose a lens or set the zoom
 * ratio; this one can, and changing lens on the same camera (ultrawide, main, telephoto) is only a change to the repeating request, so the picture
 * does not even blink.
 */
class LensCapturer(private val context: Context, initial: Lens) : VideoCapturer {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var builder: CaptureRequest.Builder? = null
    private var characteristics: CameraCharacteristics? = null
    @Volatile var lens: Lens = initial
        private set
    private var width = 1280
    private var height = 720
    private var fps = 30
    @Volatile private var running = false

    override fun initialize(helper: SurfaceTextureHelper, context: Context?, observer: CapturerObserver) {
        this.helper = helper
        this.observer = observer
    }

    @SuppressLint("MissingPermission")
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        this.width = width
        this.height = height
        this.fps = framerate
        running = true
        thread = HandlerThread("ember-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        open(lens)
    }

    @SuppressLint("MissingPermission")
    private fun open(target: Lens) {
        val h = handler ?: return
        val chars = manager.getCameraCharacteristics(target.cameraId)
        characteristics = chars
        val size = pickSize(chars, width, height)
        val tex = helper ?: return
        tex.setTextureSize(size.width, size.height)
        tex.startListening { frame -> deliver(frame) }
        surface = Surface(tex.surfaceTexture)
        manager.openCamera(target.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!running) { camera.close(); return }
                device = camera
                try {
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (!running) { s.close(); return }
                            session = s
                            try {
                                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                b.addTarget(surface!!)
                                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                frameRate(chars, b)
                                builder = b
                                zoom(b, chars, target.zoom)
                                s.setRepeatingRequest(b.build(), null, h)
                                observer?.onCapturerStarted(true)
                            } catch (e: Exception) { failed(e) }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) { failed(null) }
                    }, h)
                } catch (e: Exception) { failed(e) }
            }

            override fun onDisconnected(camera: CameraDevice) { camera.close(); failed(null) }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); failed(null) }
        }, h)
    }

    private fun failed(e: Exception?) {
        Log.w("LensCapturer", "camera failed", e)
        observer?.onCapturerStarted(false)
    }

    /** Move to another lens. Same camera: just the zoom ratio. Another camera (front/back): reopen. */
    fun switchTo(target: Lens) {
        val old = lens
        lens = target
        val h = handler ?: return
        if (!running) return
        if (old.cameraId == target.cameraId) {
            h.post {
                val b = builder ?: return@post
                val chars = characteristics ?: return@post
                zoom(b, chars, target.zoom)
                runCatching { session?.setRepeatingRequest(b.build(), null, h) }
            }
        } else {
            h.post {
                closeCamera()
                runCatching { open(target) }.onFailure { failed(it as? Exception) }
            }
        }
    }

    private fun zoom(b: CaptureRequest.Builder, chars: CameraCharacteristics, ratio: Float) {
        if (Build.VERSION.SDK_INT >= 30 && chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
        } else if (ratio > 1f) {
            val a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val w = (a.width() / ratio).toInt()
            val hgt = (a.height() / ratio).toInt()
            b.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(a.left + (a.width() - w) / 2, a.top + (a.height() - hgt) / 2, a.left + (a.width() + w) / 2, a.top + (a.height() + hgt) / 2))
        }
    }

    private fun frameRate(chars: CameraCharacteristics, b: CaptureRequest.Builder) {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return
        var pick: Range<Int>? = null
        for (r in ranges) if (r.upper == fps && (pick == null || r.lower > pick.lower)) pick = r
        if (pick != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick)
    }

    private fun pickSize(chars: CameraCharacteristics, w: Int, h: Int): Size {
        val map: StreamConfigurationMap? = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(w, h)
        val aspect = w.toDouble() / h
        return sizes.filter { it.width <= 1920 && it.height <= 1080 }.minByOrNull {
            Math.abs(it.width.toDouble() * it.height - w.toDouble() * h) / (w.toDouble() * h) + Math.abs(it.width.toDouble() / it.height - aspect) * 2
        } ?: Size(w, h)
    }

    @Suppress("DEPRECATION")
    private fun deliver(frame: VideoFrame) {
        val chars = characteristics ?: return
        val display = when ((context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // The camera surface's transform already turns the picture by the sensor's mounting angle, which leaves a portrait picture squeezed
        // into a landscape buffer. Undo that turn so the buffer holds the real sensor image again, then describe the phone's hold the standard
        // way (rotation metadata), which is what the encoder, the other side and the adapter all expect.
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val undo = -sensor.toFloat()
        // Front frames are not mirrored here (the view mirrors your own picture), so both lenses turn the same way with the phone.
        val rotation = (sensor - display + 360) % 360
        val source = frame.buffer
        // The upright picture's left and right run along the buffer's own left and right when the picture is turned by 0 or 180, and along its
        // top and bottom when it is turned by 90 or 270.
        val flip = lens.front && context.ember.prefs.flipFrontCamera.value
        val buffer = turnTexture(source, undo, flip, rotation % 180 != 0) ?: source.also { it.retain() }
        val turned = VideoFrame(buffer, rotation, frame.timestampNs)
        observer?.onFrameCaptured(turned)
        turned.release()
    }

    private val applyTransform by lazy {
        runCatching {
            livekit.org.webrtc.TextureBufferImpl::class.java.getDeclaredMethod("applyTransformMatrix", android.graphics.Matrix::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.getOrNull()
    }

    /** The same texture, sampled turned by [degrees] around its centre, under the swapped width and height. Null if the buffer cannot do it. */
    private fun turnTexture(source: VideoFrame.Buffer, degrees: Float, flip: Boolean = false, flipVertical: Boolean = false): VideoFrame.Buffer? {
        if (source !is livekit.org.webrtc.TextureBufferImpl) return null
        val method = applyTransform ?: return null
        val m = android.graphics.Matrix()
        m.preTranslate(0.5f, 0.5f)
        m.preRotate(degrees)
        if (flip) m.preScale(if (flipVertical) 1f else -1f, if (flipVertical) -1f else 1f)
        m.preTranslate(-0.5f, -0.5f)
        return runCatching { method.invoke(source, m, source.width, source.height) as VideoFrame.Buffer }.getOrNull()
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { surface?.release() }
        helper?.stopListening()
        session = null
        device = null
        surface = null
        builder = null
    }

    override fun stopCapture() {
        running = false
        val t = thread
        val h = handler
        if (h != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            h.post { closeCamera(); latch.countDown() }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } else closeCamera()
        t?.quitSafely()
        thread = null
        handler = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        this.width = width
        this.height = height
        this.fps = framerate
    }

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = false
}
