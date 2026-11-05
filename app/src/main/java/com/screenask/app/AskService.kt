package com.screenask.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground worker for asks that do not own a MediaProjection:
 * accessibility screenshots, follow-ups on the stored frame and quick questions.
 */
class AskService : Service() {
    companion object {
        const val ACTION_ACCESSIBILITY_ASK = "com.screenask.app.action.ACCESSIBILITY_ASK"
        const val ACTION_FOLLOW_UP = "com.screenask.app.action.ASK_FOLLOW_UP"
        const val ACTION_FRAME_ASK = "com.screenask.app.action.FRAME_ASK"
        const val EXTRA_QUESTION = "extra_question"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context, action: String, question: String?, text: String?): Boolean {
            val intent = Intent(context, AskService::class.java).apply {
                this.action = action
                question?.let { putExtra(EXTRA_QUESTION, it) }
                text?.let { putExtra(EXTRA_TEXT, it) }
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat("正在处理…")
        val action = intent.action.orEmpty()
        val question = intent.getStringExtra(EXTRA_QUESTION)
        val text = intent.getStringExtra(EXTRA_TEXT)
        scope.launch { handle(action, question, text) }
        return START_NOT_STICKY
    }

    private suspend fun handle(action: String, question: String?, text: String?) {
        try {
            val config = Prefs.load(this)
            when (action) {
                ACTION_ACCESSIBILITY_ASK -> {
                    if (!ScreenTextAccessibilityService.isConnected()) {
                        NotificationHelper.showError(this, "无障碍服务未开启，无法静默截图。请改用“立即识屏”完成系统授权")
                        return
                    }
                    if (config.delaySeconds > 0) {
                        updateForeground("将在 " + config.delaySeconds + " 秒后截取屏幕…")
                        delay(config.delaySeconds * 1000L)
                    }
                    updateForeground("正在截取屏幕…")
                    val screenshot = withContext(Dispatchers.Default) {
                        ScreenTextAccessibilityService.captureScreenshot(4000)
                    }
                    if (screenshot == null) {
                        NotificationHelper.showError(this, "无障碍截图失败（可能是受保护页面）。可改用“立即识屏”的屏幕采集授权")
                        return
                    }
                    FrameStore.save(this, screenshot)
                    updateForeground("正在请求 AI…")
                    AskEngine.ask(this, "parse", question ?: config.userPrompt, screenshot)
                }
                ACTION_FRAME_ASK -> {
                    val image = FrameStore.read(this)
                    if (image == null) {
                        NotificationHelper.showError(this, "还没有可用截图，请先识屏一次")
                        return
                    }
                    updateForeground("正在请求 AI…")
                    AskEngine.ask(
                        this, "parse", question ?: config.userPrompt, image,
                        includeAccessibilityText = true
                    )
                }
                ACTION_FOLLOW_UP -> {
                    if (text.isNullOrBlank()) {
                        NotificationHelper.showError(this, "追问内容为空")
                        return
                    }
                    val image = withContext(Dispatchers.IO) { FrameStore.read(this@AskService) }
                    updateForeground("正在请求 AI…")
                    AskEngine.ask(
                        this, "followup", text, image,
                        previousAnswer = Prefs.lastAnswer(this),
                        useSystemPrompt = true,
                        includeAccessibilityText = true
                    )
                }
            }
        } finally {
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun startForegroundCompat(text: String) {
        val notification = NotificationHelper.buildService(this, text)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NotificationHelper.SERVICE_ID, notification)
            }
            foregroundStarted = true
        } catch (_: Exception) {
            foregroundStarted = false
        }
    }

    private fun updateForeground(text: String) {
        if (!foregroundStarted) return
        runCatching {
            val notification = NotificationHelper.buildService(this, text)
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.notify(NotificationHelper.SERVICE_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
