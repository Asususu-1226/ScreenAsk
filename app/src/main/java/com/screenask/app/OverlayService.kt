package com.screenask.app

import android.app.Dialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** Floating quick-access bubble: tap = identify, long press = follow-up input. */
class OverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.screenask.app.action.OVERLAY_SHOW"
        @Volatile var isRunning = false
            private set

        fun setEnabled(context: Context, enabled: Boolean) {
            Prefs.setShowOverlay(context, enabled)
        }

        fun start(context: Context): Boolean = runCatching {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_SHOW }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            true
        }.getOrDefault(false)
    }

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dialog: Dialog? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Permissions.hasOverlay(this)) {
            Prefs.setShowOverlay(this, false)
            NotificationHelper.showError(this, "缺少悬浮窗权限，无法显示悬浮球")
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.OVERLAY_ID,
                    NotificationHelper.overlayNotification(this),
                    type
                )
            } else {
                startForeground(NotificationHelper.OVERLAY_ID, NotificationHelper.overlayNotification(this))
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        isRunning = true
        return START_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(52)
        val container = FrameLayout(this)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@OverlayService, R.color.brand))
            setStroke(dp(1), 0x33FFFFFF)
        }
        container.background = background
        container.elevation = dp(10).toFloat()
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            setColorFilter(Color.WHITE)
        }
        val iconSize = dp(26)
        container.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(12)
            y = resources.displayMetrics.heightPixels / 2
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var dragged = false

        container.setOnTouchListener { _, event ->
            val current = bubbleParams ?: params
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = current.x
                    startY = current.y
                    downTime = System.currentTimeMillis()
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragged = true
                    current.x = startX + dx
                    current.y = startY + dy
                    runCatching { windowManager.updateViewLayout(container, current) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (!dragged) {
                        if (elapsed >= 550L) showQuickInput() else AskDispatcher.snap(this@OverlayService)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleParams = params
        bubble = container
        runCatching { windowManager.addView(container, params) }
    }

    private fun showQuickInput() {
        if (dialog?.isShowing == true) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF1E2230.toInt())
            }
        }
        val title = TextView(this).apply {
            text = "追问"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        }
        val edit = EditText(this).apply {
            hint = "输入追问内容…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9AA3B2.toInt())
            textSize = 14f
            minLines = 1
            maxLines = 4
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        val cancel = TextView(this).apply {
            text = "取消"
            setTextColor(0xFF9AA3B2.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { dialog?.dismiss() }
        }
        val send = TextView(this).apply {
            text = "发送"
            setTextColor(0xFF7C93FF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                val text = edit.text.toString().trim()
                if (text.isNotEmpty()) {
                    AskDispatcher.followUp(this@OverlayService, text)
                    dialog?.dismiss()
                }
            }
        }
        row.addView(cancel)
        row.addView(send)
        container.addView(title)
        container.addView(edit)
        container.addView(row)

        val newDialog = Dialog(this)
        newDialog.setContentView(container)
        newDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setType(
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE
            )
            setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        newDialog.setOnDismissListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
            dialog = null
        }
        dialog = newDialog
        runCatching { newDialog.show() }.onFailure {
            dialog = null
            Toast.makeText(this, "无法显示追问输入框：" + (it.message ?: ""), Toast.LENGTH_SHORT).show()
            return
        }
        edit.requestFocus()
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroy() {
        isRunning = false
        dialog?.dismiss()
        dialog = null
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        Prefs.setShowOverlay(this, false)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
