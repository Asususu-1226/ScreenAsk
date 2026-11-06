package com.screenask.app

import android.Manifest
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three-step first-run wizard: permissions -> API endpoint -> capture strategy.
 */
class OnboardingActivity : AppCompatActivity() {
    private val ui by lazy { UiKit(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var step = 0
    private var mode = "session"

    private lateinit var steps: List<View>
    private lateinit var stepLabel: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var backButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var skipButton: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var testStatus: TextView
    private lateinit var baseUrlField: EditText
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var modeHint: TextView
    private lateinit var delayValue: TextView
    private lateinit var delaySlider: Slider
    private lateinit var publishSwitch: MaterialSwitch

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val config = Prefs.load(this)
        val profile = if (config.useMultimodal) config.multimodal else config.vision
        mode = when {
            config.persistentCapture -> "session"
            config.accessibilitySilentCapture -> "accessibility"
            else -> "manual"
        }
        build(config, profile)
        render()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishOnboarding(skip = true)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun build(config: AppConfig, profile: ApiProfile) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (ui.isDark()) ui.color(R.color.bg_dark) else ui.color(R.color.bg_light))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.brand())
            setPadding(ui.dp(20), ui.dp(22), ui.dp(16), ui.dp(16))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "欢迎使用识屏问答"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        skipButton = TextView(this).apply {
            text = "跳过"
            textSize = 13f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8))
            setOnClickListener { finishOnboarding(skip = true) }
        }
        titleRow.addView(skipButton)
        header.addView(titleRow)
        stepLabel = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, ui.dp(4), 0, 0)
        }
        header.addView(stepLabel)
        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(12), 0, 0)
        }
        header.addView(dotsRow)

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(18))
        }
        scroll.addView(container)
        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        steps = listOf(
            buildPermissionStep(),
            buildApiStep(profile),
            buildCaptureStep(config)
        )
        for (view in steps) {
            view.visibility = View.GONE
            container.addView(view)
        }

        root.addView(View(this).apply {
            setBackgroundColor(ui.outline())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1))
        })
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.surface())
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
        }
        val outlined = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val filled = com.google.android.material.R.attr.materialButtonStyle
        backButton = MaterialButton(this, null, outlined).apply {
            text = "上一步"
            textSize = 14f
            isAllCaps = false
            cornerRadius = ui.dp(12)
            setOnClickListener { go(step - 1) }
        }
        nextButton = MaterialButton(this, null, filled).apply {
            text = "下一步"
            textSize = 14f
            isAllCaps = false
            cornerRadius = ui.dp(12)
            setOnClickListener {
                if (step < 2) go(step + 1) else finishOnboarding(skip = false)
            }
        }
        bottom.addView(backButton, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        bottom.addView(nextButton, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply {
            marginStart = ui.dp(8)
        })
        root.addView(bottom)
        setContentView(root)
    }

    private fun stepPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildPermissionStep(): LinearLayout {
        val panel = stepPanel()
        val card = ui.card(
            panel,
            "第 1 步 · 权限与隐私",
            "通知权限是识屏入口；无障碍服务可让 Android 11+ 免授权截图"
        )
        notificationStatus = ui.caption("通知权限：检查中…")
        card.addView(notificationStatus)
        ui.button(card, "授予通知权限", outlined = true) {
            if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(this)) {
                runCatching {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                Permissions.openNotificationSettings(this)
            }
        }
        accessibilityStatus = ui.caption("无障碍服务：检查中…")
        card.addView(accessibilityStatus)
        ui.button(card, "打开无障碍设置", outlined = true) {
            Permissions.openAccessibilitySettings(this)
        }
        card.addView(
            ui.caption(
                "所有截图与录音只在你主动触发时上传；API Key 使用 Android Keystore 加密保存，应用不含任何统计或广告 SDK。"
            )
        )

        val next = ui.card(panel, "接下来会配置什么", null)
        next.addView(ui.caption("1. 一个 OpenAI 兼容接口：Base URL、API Key、模型名"))
        next.addView(ui.caption("2. 采集方式：常驻录屏 / 无障碍静默截图 / 每次授权"))
        next.addView(ui.caption("3. 完成后可从通知栏直接识屏、追问，并发布通知栏入口"))
        next.addView(ui.caption("听力转写、备用接口、模型参数等高级选项可稍后在设置中调整。"))
        return panel
    }

    private fun buildApiStep(profile: ApiProfile): LinearLayout {
        val panel = stepPanel()
        val card = ui.card(
            panel,
            "第 2 步 · 配置 AI 接口",
            "填一个 OpenAI 兼容接口即可开始；稍后可在“接口”页添加备用接口与 STT"
        )
        baseUrlField = ui.input(
            card,
            "Base URL",
            profile.baseUrl,
            "填到 /v1，例如 https://api.openai.com/v1",
            uri = true
        )
        keyField = ui.input(
            card,
            "API Key",
            profile.apiKey,
            "仅保存在本机，可留空（部分本地服务不需要）",
            password = true
        )
        modelField = ui.input(
            card,
            "模型名",
            profile.model,
            "视觉模型，例如 gpt-4o-mini / qwen-vl-max / gemini-2.0-flash"
        )
        testStatus = ui.caption("")
        card.addView(testStatus)
        ui.button(card, "测试连接", outlined = true) { testConnection() }
        card.addView(
            ui.caption("使用局域网 HTTP 服务时，请先在“参数”页允许非 HTTPS 接口。")
        )
        return panel
    }

    private fun buildCaptureStep(config: AppConfig): LinearLayout {
        val panel = stepPanel()
        val card = ui.card(
            panel,
            "第 3 步 · 选择采集方式",
            "决定每次识屏如何拿到画面，之后可在“识屏”页随时切换"
        )
        modeHint = ui.caption("")
        card.addView(modeHint)

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val session = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "常驻录屏（推荐）"
            textSize = 14f
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        val accessibility = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "无障碍静默截图"
            textSize = 14f
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        val manual = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "每次授权截图"
            textSize = 14f
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        group.addView(session)
        group.addView(accessibility)
        group.addView(manual)
        val ids = mapOf(
            session.id to "session",
            accessibility.id to "accessibility",
            manual.id to "manual"
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            mode = ids[checkedId] ?: mode
            updateModeHint()
        }
        when (mode) {
            "accessibility" -> group.check(accessibility.id)
            "manual" -> group.check(manual.id)
            else -> group.check(session.id)
        }
        card.addView(group)
        updateModeHint()

        delayValue = ui.caption("采集延迟：" + config.delaySeconds + " 秒")
        card.addView(delayValue)
        delaySlider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 30f
            stepSize = 1f
            setTickVisible(false)
            value = config.delaySeconds.toFloat().coerceIn(0f, 30f)
            addOnChangeListener { _, value, _ ->
                delayValue.text = "采集延迟：" + value.toInt() + " 秒"
            }
        }
        card.addView(
            delaySlider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        publishSwitch = ui.switchRow(
            card,
            "完成后发布通知栏按钮",
            "之后可直接从通知栏识屏/追问",
            true
        )
        card.addView(
            ui.caption("提示：常驻录屏会显示系统录屏提示；无障碍静默截图在受保护页面可能失败。")
        )
        card.addView(
            ui.caption("若识屏截图全黑：请在“开发者选项 → 关闭屏幕采集保护”（部分 MIUI/HyperOS 或定制系统提供该开关），然后重试。")
        )
        return panel
    }

    private fun updateModeHint() {
        if (!this::modeHint.isInitialized) return
        modeHint.text = when (mode) {
            "session" -> "常驻录屏：首次点识屏时授权一次，之后每次直接取最新一帧，最适合课堂连续使用。"
            "accessibility" -> "无障碍截图：先开启无障碍服务；无需录屏授权，但受保护页面可能截不到。"
            else -> "每次授权：每次识屏都会弹出系统授权框，最安全但操作最多。"
        }
    }

    private fun go(target: Int) {
        step = target.coerceIn(0, 2)
        render()
    }

    private fun render() {
        if (!this::steps.isInitialized) return
        for (i in steps.indices) {
            steps[i].visibility = if (i == step) View.VISIBLE else View.GONE
        }
        stepLabel.text = "第 " + (step + 1) + " 步，共 3 步"
        backButton.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = if (step == 2) "完成并开始" else "下一步"
        if (this::notificationStatus.isInitialized) {
            notificationStatus.text = "通知权限：" +
                if (Permissions.hasNotifications(this)) "已授权" else "未授权"
            accessibilityStatus.text = "无障碍服务：" +
                if (Permissions.isAccessibilityEnabled(this)) "已开启" else "未开启"
        }
        renderDots()
    }

    private fun renderDots() {
        if (!this::dotsRow.isInitialized) return
        dotsRow.removeAllViews()
        for (i in 0..2) {
            val active = i == step
            dotsRow.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active) Color.WHITE else 0x66FFFFFF)
                }
            }, LinearLayout.LayoutParams(if (active) ui.dp(18) else ui.dp(7), ui.dp(7)).apply {
                marginEnd = ui.dp(6)
            })
        }
    }

    private fun testConnection() {
        val url = baseUrlField.text.toString().trim()
        val model = modelField.text.toString().trim()
        if (url.isBlank() || model.isBlank()) {
            toast("请先填写 Base URL 和模型名")
            return
        }
        val profile = ApiProfile(
            baseUrl = url,
            apiKey = keyField.text.toString().trim(),
            model = model
        )
        testStatus.text = "正在测试连接…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    OpenAiClient.testConnection(profile, Prefs.load(this@OnboardingActivity).allowHttp)
                }
            }
            testStatus.text = result.getOrElse {
                "连接失败：" + (it.message ?: "未知错误")
            }
        }
    }

    private fun finishOnboarding(skip: Boolean) {
        if (!skip) {
            var config = Prefs.load(this)
            val url = baseUrlField.text.toString().trim()
            val key = keyField.text.toString().trim()
            val model = modelField.text.toString().trim()
            if (url.isNotBlank() && model.isNotBlank()) {
                val merged = config.multimodal.copy(
                    baseUrl = url,
                    apiKey = key,
                    model = model
                )
                config = config.copy(
                    multimodal = merged,
                    vision = merged,
                    useMultimodal = true
                )
            }
            config = config.copy(
                persistentCapture = mode == "session",
                accessibilitySilentCapture = mode == "accessibility",
                delaySeconds = delaySlider.value.toInt().coerceIn(0, 30)
            )
            Prefs.save(this, config)
            if (publishSwitch.isChecked) {
                NotificationHelper.showLauncher(this)
            }
            if (mode == "accessibility" && !Permissions.isAccessibilityEnabled(this)) {
                toast("提示：无障碍服务尚未开启，可稍后在“隐私”页开启")
            }
        }
        Prefs.setOnboarded(this, true)
        setResult(if (skip) RESULT_CANCELED else RESULT_OK)
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
