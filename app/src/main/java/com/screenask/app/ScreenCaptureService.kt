package com.screenask.app

import android.app.Activity
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the MediaProjection.
 *
 * One-shot mode: capture a frame after the configured delay, ask, release.
 * Session mode: keep mirroring, store the latest frame continuously and answer
 * any number of "识屏" triggers without a new consent dialog.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_ONESHOT = "com.screenask.app.action.ONESHOT"
        const val ACTION_START_SESSION = "com.screenask.app.action.START_SESSION"
        const val ACTION_SNAP = "com.screenask.app.action.SNAP"
        const val ACTION_FOLLOW_UP = "com.screenask.app.action.FOLLOW_UP"
        const val ACTION_STOP = "com.screenask.app.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_QUESTION = "extra_question"
        const val EXTRA_SESSION = "extra_session"
        const val EXTRA_FOLLOW_UP = "extra_follow_up"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null

    private var sessionMode = false
    private var foregroundId = NotificationHelper.SERVICE_ID
    private var foregroundStarted = false
    private var stopping = false

    private val snapPending = AtomicBoolean(false)
    @Volatile private var snapAt = 0L
    @Volatile private var snapQuestion: String? = null
    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var latestAt = 0L
    @Volatile private var lastConvertAt = 0L
    @Volatile private var busy = false

    private var frameIntervalMs = 400
    private var imageMaxSide = 1600
    private var jpegQuality = 80
    private var fallback: Runnable? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSession()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_ONESHOT, ACTION_START_SESSION -> {
                if (projection != null) {
                    requestSnap(intent.getStringExtra(EXTRA_QUESTION))
                    return START_NOT_STICKY
                }
                val requestedSession = intent.action == ACTION_START_SESSION ||
                    intent.getBooleanExtra(EXTRA_SESSION, false)
                sessionMode = requestedSession
                foregroundId = if (sessionMode) NotificationHelper.SESSION_ID else NotificationHelper.SERVICE_ID
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = resultData(intent)
                if (code == Activity.RESULT_CANCELED || data == null) {
                    fail("未获得屏幕采集授权")
                } else {
                    begin(code, data, intent.getStringExtra(EXTRA_QUESTION))
                }
            }
            ACTION_SNAP -> {
                if (projection == null) {
                    fail("常驻录屏未启动，请先点击“立即识屏”完成授权")
                } else {
                    requestSnap(intent.getStringExtra(EXTRA_QUESTION))
                }
            }
            ACTION_FOLLOW_UP -> performFollowUp(intent.getStringExtra(EXTRA_FOLLOW_UP).orEmpty())
            ACTION_STOP -> stopSession()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun resultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun begin(resultCode: Int, data: Intent, question: String?) {
        try {
            val initialNotification = if (sessionMode) {
                NotificationHelper.buildSession(this, "常驻录屏启动中…")
            } else {
                NotificationHelper.buildService(this, "正在准备屏幕采集…")
            }
            val initialId = if (sessionMode) NotificationHelper.SESSION_ID else NotificationHelper.SERVICE_ID
            startForegroundSafe(initialId, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            val config = Prefs.load(this)
            frameIntervalMs = config.frameIntervalMs
            imageMaxSide = config.imageMaxSide
            jpegQuality = config.jpegQuality

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("系统未返回屏幕采集会话")
            projection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, mainHandler)

            val metrics = realMetrics()
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader

            val thread = HandlerThread("screenask-frames").also { it.start() }
            frameThread = thread
            val handler = Handler(thread.looper)
            frameHandler = handler
            reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

            val display = mediaProjection.createVirtualDisplay(
                "ScreenAsk",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            if (display == null) throw IllegalStateException("创建屏幕采集通道失败")
            virtualDisplay = display

            if (sessionMode) {
                NotificationHelper.showSession(this, "常驻录屏中，点“识屏”立即取帧")
            } else {
                updateServiceText("屏幕采集已启动")
            }
            requestSnap(question)
        } catch (e: Exception) {
            fail(e.message ?: "屏幕采集启动失败")
        }
    }

    private fun realMetrics(): DisplayMetrics {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    /** Trigger a capture. The frame is taken after the configured delay. */
    fun requestSnap(question: String? = null) {
        mainHandler.post {
            if (projection == null) {
                fail("常驻录屏未运行")
                return@post
            }
            val config = Prefs.load(this)
            val delayMs = config.delaySeconds * 1000L
            snapQuestion = question
            snapAt = SystemClock.elapsedRealtime() + delayMs
            snapPending.set(true)
            val text = if (delayMs > 0) "将在 " + config.delaySeconds + " 秒后取帧…" else "正在取帧…"
            if (sessionMode) {
                NotificationHelper.showSession(this, text)
            } else {
                updateServiceText(text)
            }
            scheduleFallback(delayMs + 1500L)
            if (delayMs == 0L && latestFrame != null) {
                mainHandler.postDelayed({
                    if (snapPending.compareAndSet(true, false)) deliverSnap()
                }, 30L)
            }
        }
    }

    fun requestFollowUp(text: String) {
        if (text.isBlank()) return
        mainHandler.post { performFollowUp(text) }
    }

    fun isCapturing(): Boolean = projection != null

    private fun onFrame(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val due = snapPending.get() && now >= snapAt
            if (!due && now - lastConvertAt < frameIntervalMs) return
            val jpeg = imageToJpeg(image) ?: return
            latestFrame = jpeg
            latestAt = now
            lastConvertAt = now
            if (due && snapPending.compareAndSet(true, false)) {
                mainHandler.post { deliverSnap() }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { image.close() }
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + if (pixelStride > 0) rowPadding / pixelStride else 0
        if (paddedWidth <= 0) return null
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            val scaled = scaleDown(cropped, imageMaxSide)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== padded) cropped.recycle()
            return out.toByteArray()
        } finally {
            padded.recycle()
        }
    }

    private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val ratio = maxSide.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun scheduleFallback(timeoutMs: Long) {
        fallback?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (snapPending.compareAndSet(true, false)) {
                if (latestFrame != null) {
                    deliverSnap()
                } else {
                    fail("没有取到屏幕画面，请重试")
                }
            }
        }
        fallback = runnable
        mainHandler.postDelayed(runnable, timeoutMs.coerceAtLeast(1000L))
    }

    private fun deliverSnap() {
        if (busy) {
            mainHandler.postDelayed({ deliverSnap() }, 700L)
            return
        }
        val bytes = latestFrame
        if (bytes == null) {
            fail("没有取到屏幕画面，请重试")
            return
        }
        busy = true
        fallback?.let { mainHandler.removeCallbacks(it) }
        fallback = null
        val config = Prefs.load(this)
        val question = snapQuestion?.takeIf { it.isNotBlank() } ?: config.userPrompt
        if (sessionMode) {
            NotificationHelper.showSession(this, "已取帧，正在请求 AI…")
        } else {
            updateServiceText("已取帧，正在请求 AI…")
        }
        FrameStore.save(this, bytes)
        scope.launch {
            runCatching {
                AskEngine.ask(this@ScreenCaptureService, "parse", question, bytes)
            }
            busy = false
            if (!sessionMode) {
                stopSession()
            } else {
                NotificationHelper.showSession(this@ScreenCaptureService, "常驻录屏中，点“识屏”立即取帧")
            }
        }
    }

    private fun performFollowUp(text: String) {
        if (text.isBlank()) return
        if (busy) {
            NotificationHelper.showError(this, "上一个请求还在处理中，请稍后再试")
            return
        }
        busy = true
        val image = latestFrame ?: FrameStore.read(this)
        if (sessionMode) {
            NotificationHelper.showSession(this, "正在回答追问…")
        } else {
            updateServiceText("正在回答追问…")
        }
        scope.launch {
            runCatching {
                AskEngine.ask(
                    this@ScreenCaptureService,
                    "followup",
                    text,
                    image,
                    previousAnswer = Prefs.lastAnswer(this@ScreenCaptureService),
                    useSystemPrompt = true,
                    includeAccessibilityText = true
                )
            }
            busy = false
            if (sessionMode) {
                NotificationHelper.showSession(this@ScreenCaptureService, "常驻录屏中，点“识屏”立即取帧")
            } else if (projection == null) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    fun stopSession() {
        mainHandler.post {
            if (stopping) return@post
            stopping = true
            snapPending.set(false)
            fallback?.let { mainHandler.removeCallbacks(it) }
            fallback = null
            cleanup()
            NotificationHelper.cancelSession(this)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun cleanup() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        val current = projection
        projection = null
        if (current != null) {
            runCatching { current.unregisterCallback(projectionCallback) }
            runCatching { current.stop() }
        }
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
        latestFrame = null
        latestAt = 0L
    }

    private fun startForegroundSafe(id: Int, notification: android.app.Notification, type: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, id, notification, type)
            } else {
                startForeground(id, notification)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            fail("前台服务启动失败：" + (e.message ?: ""))
        }
    }

    private fun updateServiceText(text: String) {
        updateNotification(NotificationHelper.SERVICE_ID, NotificationHelper.buildService(this, text))
    }

    private fun updateNotification(id: Int, notification: android.app.Notification) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(id, notification)
        }
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
    }

    private fun fail(message: String) {
        NotificationHelper.showError(this, message)
        stopSession()
    }

    override fun onDestroy() {
        instance = null
        cleanup()
        scope.cancel()
        super.onDestroy()
    }
}
