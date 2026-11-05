package com.screenask.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Invisible trampoline used by notifications / overlay / tiles.
 * Decides between: existing session -> snap, accessibility screenshot -> AskService,
 * otherwise request MediaProjection consent and start the capture service.
 */
class CaptureTriggerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_QUESTION = "extra_question"
        const val EXTRA_FORCE_SESSION = "extra_force_session"
        const val EXTRA_FOLLOW_UP = "extra_follow_up"

        fun start(context: Context, question: String? = null, forceSession: Boolean = false) {
            val intent = Intent(context, CaptureTriggerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                question?.let { putExtra(EXTRA_QUESTION, it) }
                putExtra(EXTRA_FORCE_SESSION, forceSession)
            }
            runCatching { context.startActivity(intent) }
        }

        fun startFollowUp(context: Context, text: String) {
            val intent = Intent(context, CaptureTriggerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_FOLLOW_UP, text)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    private var requested = false

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startCaptureService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "未授权屏幕采集", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val followUp = intent?.getStringExtra(EXTRA_FOLLOW_UP)
        if (!followUp.isNullOrBlank()) {
            AskService.start(this, AskService.ACTION_FOLLOW_UP, null, followUp)
            finish()
            return
        }
        val question = intent?.getStringExtra(EXTRA_QUESTION)
        val forceSession = intent?.getBooleanExtra(EXTRA_FORCE_SESSION, false) == true
        val session = ScreenCaptureService.instance
        if (session != null && session.isCapturing()) {
            session.requestSnap(question)
            finish()
            return
        }
        val config = Prefs.load(this)
        if (!forceSession && config.accessibilitySilentCapture && ScreenTextAccessibilityService.isConnected()) {
            val started = AskService.start(this, AskService.ACTION_ACCESSIBILITY_ASK, question, null)
            if (started) {
                finish()
                return
            }
        }
        requestConsent()
    }

    private fun requestConsent() {
        if (requested) return
        requested = true
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        runCatching { consentLauncher.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                Toast.makeText(this, "无法打开屏幕采集授权：" + (it.message ?: ""), Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val config = Prefs.load(this)
        val session = intent?.getBooleanExtra(EXTRA_FORCE_SESSION, false) == true || config.persistentCapture
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = if (session) ScreenCaptureService.ACTION_START_SESSION else ScreenCaptureService.ACTION_ONESHOT
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SESSION, session)
            putExtra(ScreenCaptureService.EXTRA_QUESTION, intent?.getStringExtra(EXTRA_QUESTION))
        }
        runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
            .onFailure {
                Toast.makeText(this, "启动采集服务失败：" + (it.message ?: ""), Toast.LENGTH_LONG).show()
            }
    }
}
