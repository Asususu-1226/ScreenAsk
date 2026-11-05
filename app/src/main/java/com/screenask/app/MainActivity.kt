package com.screenask.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private class ProfileFields(
        val url: EditText,
        val key: EditText,
        val model: EditText,
        val backupUrl: EditText,
        val backupKey: EditText,
        val backupModel: EditText,
        val headers: EditText,
        val timeout: EditText
    )

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ui by lazy { UiKit(this) }
    private lateinit var panels: List<View>
    private lateinit var tabLayout: TabLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var statusCardBody: LinearLayout
    private var accessibilityStatusText: TextView? = null
    private var shizukuStatusText: TextView? = null

    private lateinit var visionFields: ProfileFields
    private lateinit var multiFields: ProfileFields
    private lateinit var sttFields: ProfileFields

    private lateinit var useMultimodalSwitch: MaterialSwitch
    private lateinit var promptField: EditText
    private lateinit var userPromptField: EditText
    private lateinit var delayField: EditText
    private lateinit var temperatureField: EditText
    private lateinit var topPField: EditText
    private lateinit var maxTokensField: EditText
    private lateinit var presenceField: EditText
    private lateinit var frequencyField: EditText
    private lateinit var seedField: EditText
    private lateinit var extraBodyField: EditText
    private lateinit var imageMaxSideField: EditText
    private lateinit var jpegQualityField: EditText
    private lateinit var frameIntervalField: EditText
    private lateinit var historyLimitField: EditText
    private lateinit var sttLanguageField: EditText
    private var imageDetailSpinner: Spinner? = null
    private var sttSourceSpinner: Spinner? = null

    private lateinit var persistentSwitch: MaterialSwitch
    private lateinit var accessibilityCaptureSwitch: MaterialSwitch
    private lateinit var includeAccessibilityTextSwitch: MaterialSwitch
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var answerNotificationSwitch: MaterialSwitch
    private lateinit var silentSwitch: MaterialSwitch
    private lateinit var neutralSwitch: MaterialSwitch
    private lateinit var autoCopySwitch: MaterialSwitch
    private lateinit var keepHistorySwitch: MaterialSwitch
    private lateinit var allowHttpSwitch: MaterialSwitch
    private lateinit var secureWindowSwitch: MaterialSwitch
    private lateinit var bootSwitch: MaterialSwitch

    private var pendingAudioSource = AudioRecordingService.SOURCE_MIC

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAudio(pendingAudioSource) else toast("未获得麦克风权限")
    }

    private val audioProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            AudioRecordingService.start(
                this,
                AudioRecordingService.SOURCE_SYSTEM,
                result.resultCode,
                result.data
            )
            toast("已开始采集系统音频，从通知栏停止并转写")
        } else {
            toast("未授权屏幕采集，无法采集系统内部音频")
        }
    }

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            applyAfterOnboarding()
        } else {
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val config = Prefs.load(this)
        if (config.secureWindow) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        buildUi(config)
        refreshStatus()
        if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(this)) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        if (!Prefs.isOnboarded(this)) {
            window.decorView.post { launchOnboarding() }
        }
    }

    private fun launchOnboarding() {
        runCatching {
            onboardingLauncher.launch(Intent(this, OnboardingActivity::class.java))
        }.onFailure { toast("无法打开使用引导：" + (it.message ?: "")) }
    }

    private fun applyAfterOnboarding() {
        val config = Prefs.load(this)
        if (config.showOverlay && Permissions.hasOverlay(this) && !OverlayService.isRunning) {
            OverlayService.start(this)
        }
        recreate()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        val config = Prefs.load(this)
        if (this::overlaySwitch.isInitialized && overlaySwitch.isChecked != config.showOverlay) {
            overlaySwitch.isChecked = config.showOverlay
        }
        if (config.showOverlay && Permissions.hasOverlay(this) && !OverlayService.isRunning) {
            OverlayService.start(this)
        }
    }

    private fun dp(value: Int): Int = ui.dp(value)

    private fun colorOf(resId: Int): Int = ui.color(resId)

    private fun secondaryColor(): Int = ui.secondary()

    private fun isDarkMode(): Boolean = ui.isDark()

    private fun background(): Int = if (isDarkMode()) colorOf(R.color.bg_dark) else colorOf(R.color.bg_light)

    // ---------------------------------------------------------------- UI shell

    private fun buildUi(config: AppConfig) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background())
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
            setBackgroundColor(colorOf(R.color.brand))
            setPadding(dp(20), dp(16), dp(14), dp(6))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "识屏问答"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val historyButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_history)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "历史记录"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            val outValue = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
                setBackgroundResource(outValue.resourceId)
            }
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
            }
        }
        titleRow.addView(historyButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(titleRow)
        header.addView(TextView(this).apply {
            text = "通知栏直达 · 多接口 · 常驻录屏 · 听力转写"
            textSize = 12.5f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, dp(2), 0, 0)
        })

        tabLayout = TabLayout(this).apply {
            setBackgroundColor(colorOf(R.color.brand))
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            setSelectedTabIndicatorColor(Color.WHITE)
            setTabTextColors(0xB3FFFFFF.toInt(), Color.WHITE)
            val names = listOf("识屏", "接口", "参数", "语音", "隐私")
            for (name in names) addTab(newTab().setText(name))
        }
        root.addView(header)
        root.addView(tabLayout)

        contentScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
        }
        val panelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        contentScroll.addView(panelContainer)
        root.addView(contentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(View(this).apply {
            setBackgroundColor(ui.outline())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.surface())
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        val save = MaterialButton(this).apply {
            text = "保存并应用"
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(12)
            setOnClickListener {
                runCatching { saveSettings(true) }
                    .onFailure { toast("保存失败：" + (it.message ?: "")) }
            }
        }
        val snap = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "立即识屏"
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(12)
            setOnClickListener { snapNow() }
        }
        bottom.addView(save, LinearLayout.LayoutParams(0, dp(48), 1.1f))
        bottom.addView(snap, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(bottom)

        setContentView(root)
        root.isFocusableInTouchMode = true
        root.requestFocus()

        panels = listOf(
            buildIdentifyPanel(config),
            buildApiPanel(config),
            buildParamsPanel(config),
            buildAudioPanel(config),
            buildPrivacyPanel(config)
        )
        for (panel in panels) {
            panel.visibility = View.GONE
            panelContainer.addView(panel)
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchPanel(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        switchPanel(0)
        tabLayout.getTabAt(0)?.select()
    }

    private fun switchPanel(index: Int) {
        if (!this::panels.isInitialized) return
        hideKeyboard()
        for (i in panels.indices) {
            panels[i].visibility = if (i == index) View.VISIBLE else View.GONE
        }
        contentScroll.scrollTo(0, 0)
    }

    private fun newPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun heading(text: String, size: Float = 15f): TextView = ui.heading(text, size)

    private fun caption(text: String): TextView = ui.caption(text)

    private fun card(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout =
        ui.card(parent, title, subtitle)

    private fun input(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String = "",
        password: Boolean = false,
        multiline: Boolean = false,
        number: Boolean = false,
        decimal: Boolean = false,
        signed: Boolean = false,
        uri: Boolean = false
    ): EditText = ui.input(
        parent, label, value, hint,
        password = password,
        multiline = multiline,
        number = number,
        decimal = decimal,
        signed = signed,
        uri = uri
    )

    private fun switchRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        checked: Boolean,
        onChange: ((Boolean) -> Unit)? = null
    ): MaterialSwitch = ui.switchRow(parent, title, summary, checked, onChange)

    private fun button(parent: LinearLayout, text: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton =
        ui.button(parent, text, outlined, onClick)

    private fun buttonRow(
        parent: LinearLayout,
        leftText: String,
        rightText: String,
        left: () -> Unit,
        right: () -> Unit
    ) = ui.buttonRow(parent, leftText, rightText, left, right)

    private fun spinner(
        parent: LinearLayout,
        label: String,
        options: List<String>,
        selected: Int,
        onSelected: ((Int) -> Unit)? = null
    ): Spinner = ui.spinner(parent, label, options, selected, onSelected)

    // ---------------------------------------------------------------- panels

    private fun buildIdentifyPanel(config: AppConfig): LinearLayout {
        val panel = newPanel()
        statusCardBody = card(panel, "运行状态", "权限与采集通道就绪情况")

        val mode = card(panel, "识屏方式", "通知栏点“识屏”，等待设定秒数后取一帧发送给 AI")
        delayField = input(mode, "采集延迟（秒）", config.delaySeconds.toString(), "0–30 秒，点“识屏”后等待这么久再取帧", number = true)
        promptField = input(mode, "系统 Prompt", config.prompt, "定义模型角色与回答风格，可点下方预设", multiline = true)
        userPromptField = input(mode, "每次识屏的问题", config.userPrompt, "触发识屏时随截图一起发送", multiline = true)
        buttonRow(mode, "问题预设", "Prompt 预设", {
            choosePreset(userPromptField, PromptPresets.questions)
        }, {
            choosePreset(promptField, PromptPresets.systemPrompts)
        })
        persistentSwitch = switchRow(
            mode,
            "常驻录屏（推荐）",
            "只授权一次，之后每次识屏直接取最新一帧，无需再次授权",
            config.persistentCapture
        )
        accessibilityCaptureSwitch = switchRow(
            mode,
            "无障碍静默截图",
            "Android 11+ 开启无障碍后可免系统授权直接截图",
            config.accessibilitySilentCapture
        )
        includeAccessibilityTextSwitch = switchRow(
            mode,
            "附带无障碍读取的屏幕文字",
            "把当前屏幕文字一并发送，帮助模型识别模糊内容",
            config.includeAccessibilityText
        )
        buttonRow(mode, "立即识屏", "发布通知按钮", { snapNow() }, {
            runCatching { saveSettings(false) }
            NotificationHelper.showLauncher(this)
            toast("已发布通知栏按钮，可从通知栏直接识屏/追问")
        })
        buttonRow(mode, "开始常驻录屏", "停止录屏", {
            runCatching { saveSettings(false) }
            CaptureTriggerActivity.start(this, null, true)
        }, {
            ScreenCaptureService.instance?.stopSession()
            stopService(Intent(this, ScreenCaptureService::class.java))
            NotificationHelper.cancelSession(this)
            toast("已停止常驻录屏")
        })
        overlaySwitch = switchRow(
            mode,
            "悬浮球",
            "点按识屏、长按输入追问，可拖动位置",
            config.showOverlay
        ) { checked -> onOverlaySwitch(checked) }

        val notificationCard = card(panel, "回答与通知", "答案会以完整文本显示在通知栏，可直接在通知栏追问")
        answerNotificationSwitch = switchRow(
            notificationCard,
            "在通知栏显示回答",
            "关闭后仅写入历史记录",
            config.answerInNotification
        )
        silentSwitch = switchRow(
            notificationCard,
            "静默通知",
            "不响铃、不弹窗，适合课堂环境",
            config.silentNotifications
        )
        neutralSwitch = switchRow(
            notificationCard,
            "中性文案",
            "通知标题显示为“系统通知”，隐藏应用名",
            config.neutralText
        )
        autoCopySwitch = switchRow(
            notificationCard,
            "自动复制答案",
            "收到答案时同时写入剪贴板",
            config.autoCopyAnswer
        )
        return panel
    }

    private fun buildApiPanel(config: AppConfig): LinearLayout {
        val panel = newPanel()
        val intro = card(panel, "接口方案", "所有接口均为 OpenAI 兼容格式：/v1/chat/completions 与 /v1/audio/transcriptions")
        useMultimodalSwitch = switchRow(
            intro,
            "优先使用多模态接口",
            "开启：多模态接口优先，失败时自动切换到视觉解释接口；关闭则顺序相反",
            config.useMultimodal
        )
        intro.addView(caption("Base URL 填到 /v1 层级，例如 https://api.example.com/v1。留空的备用接口不会启用。"))

        val multiCard = card(panel, "多模态接口（主）", "需要支持 image_url 的视觉模型")
        multiFields = profileFields(multiCard, config.multimodal, "多模态")

        val visionCard = card(panel, "图像解释接口", "只负责图片理解的模型")
        visionFields = profileFields(visionCard, config.vision, "视觉")

        val sttCard = card(panel, "STT 语音转文字接口", "用于听力音频转写，例如 whisper-1")
        sttFields = profileFields(sttCard, config.stt, "STT")

        val tools = card(panel, "模型与连通性", "可读取服务端模型列表并一键填入")
        buttonRow(tools, "读取多模态模型", "读取视觉模型", {
            fetchModelsFor(multiFields, "多模态")
        }, {
            fetchModelsFor(visionFields, "视觉")
        })
        buttonRow(tools, "读取 STT 模型", "测试连接", {
            fetchModelsFor(sttFields, "STT")
        }, {
            testConnection()
        })
        return panel
    }

    private fun profileFields(parent: LinearLayout, profile: ApiProfile, label: String): ProfileFields {
        val url = input(parent, label + " Base URL", profile.baseUrl, "填到 /v1，例如 https://api.example.com/v1", uri = true)
        val key = input(parent, label + " API Key", profile.apiKey, "仅保存在本机，Keystore 加密", password = true)
        val model = input(parent, label + " 模型名", profile.model, "例如 gpt-4o-mini")
        val backupUrl = input(parent, "备用 Base URL", profile.backupBaseUrl, "可选，主接口失败时自动切换", uri = true)
        val backupKey = input(parent, "备用 API Key", profile.backupApiKey, "可选", password = true)
        val backupModel = input(parent, "备用模型名", profile.backupModel, "可选，需与备用地址配套")
        val headers = input(
            parent,
            "自定义请求头",
            profile.extraHeaders,
            "可选，每行 Name: Value",
            multiline = true
        )
        val timeout = input(parent, "请求超时（秒）", profile.timeoutSeconds.toString(), "5–600 秒", number = true)
        return ProfileFields(url, key, model, backupUrl, backupKey, backupModel, headers, timeout)
    }

    private fun buildParamsPanel(config: AppConfig): LinearLayout {
        val panel = newPanel()
        val generation = card(panel, "生成参数", "按服务商支持情况调整；不兼容的参数会自动降级重试")
        temperatureField = input(generation, "temperature", config.temperature.toString(), "0–2，越低越稳定", decimal = true)
        topPField = input(generation, "top_p", config.topP.toString(), "0–1，与 temperature 二选一调整即可", decimal = true)
        maxTokensField = input(generation, "max_tokens", config.maxTokens.toString(), "1–32000", number = true)
        presenceField = input(generation, "presence_penalty", config.presencePenalty.toString(), "−2 到 2", decimal = true)
        frequencyField = input(generation, "frequency_penalty", config.frequencyPenalty.toString(), "−2 到 2", decimal = true)
        seedField = input(generation, "seed", config.seed.toString(), "−1 表示不发送该字段", number = true, signed = true)
        extraBodyField = input(
            generation,
            "附加请求字段（JSON）",
            config.extraBodyJson,
            "可选，例如 {\"reasoning_effort\": \"low\"}",
            multiline = true
        )

        val image = card(panel, "图片与录制", "影响清晰度、上传体积与耗电")
        imageDetailSpinner = spinner(
            image, "图片细节（image detail）",
            listOf("auto", "high", "low"),
            listOf("auto", "high", "low").indexOf(config.imageDetail).coerceAtLeast(0)
        )
        imageMaxSideField = input(image, "图片最长边（像素）", config.imageMaxSide.toString(), "480–4096，越大越清晰、越费流量", number = true)
        jpegQualityField = input(image, "JPEG 质量", config.jpegQuality.toString(), "30–100", number = true)
        frameIntervalField = input(image, "常驻录屏取帧间隔（毫秒）", config.frameIntervalMs.toString(), "100–5000，越大越省电", number = true)

        val misc = card(panel, "其他", "本地数据与网络策略")
        allowHttpSwitch = switchRow(
            misc,
            "允许非 HTTPS 接口",
            "仅在访问局域网自建服务时开启，HTTP 会明文传输 API Key",
            config.allowHttp
        )
        keepHistorySwitch = switchRow(
            misc,
            "保存识屏历史",
            "答案保存在应用私有目录，可随时清除",
            config.keepHistory
        )
        historyLimitField = input(misc, "历史保留条数", config.historyLimit.toString(), "10–1000", number = true)
        bootSwitch = switchRow(
            misc,
            "开机后恢复通知栏入口",
            "开机自动发布“识屏问答已就绪”通知",
            config.autoStartOnBoot
        )
        return panel
    }

    private fun buildAudioPanel(config: AppConfig): LinearLayout {
        val panel = newPanel()
        val card = card(panel, "听力转写（STT）", "录音结束后上传到 STT 接口，结果写入通知栏与历史")
        val sources = listOf("麦克风", "系统内部音频（Android 10+）")
        sttSourceSpinner = spinner(
            card, "音源",
            sources,
            if (config.sttSource == AudioRecordingService.SOURCE_SYSTEM) 1 else 0
        )
        sttLanguageField = input(card, "转写语言", config.sttLanguage, "zh / en / ja / auto 等，auto 表示自动识别")
        caption("语言留空或 auto 表示自动识别。系统内部音频需要再次授权屏幕采集，且部分播放器（如受保护课程）不允许被采集。")
        buttonRow(card, "开始录音", "停止并转写", {
            startAudio(selectedAudioSource())
        }, {
            startService(
                Intent(this, AudioRecordingService::class.java).apply {
                    action = AudioRecordingService.ACTION_STOP
                }
            )
            toast("正在停止并转写…")
        })
        caption("麦克风模式会把扬声器外放的声音一并录进去；戴耳机时请使用系统内部音频模式，或在设置中调整音源。")
        return panel
    }

    private fun buildPrivacyPanel(config: AppConfig): LinearLayout {
        val panel = newPanel()
        val privacy = card(panel, "隐私与防检测", "避免截图泄露应用界面、隐藏桌面入口")
        secureWindowSwitch = switchRow(
            privacy,
            "保护本应用界面",
            "开启后系统截图/录屏无法看到本应用内容（FLAG_SECURE）",
            config.secureWindow
        )
        buttonRow(privacy, "隐藏桌面图标", "恢复桌面图标", {
            setLauncherVisible(false)
        }, {
            setLauncherVisible(true)
        })
        buttonRow(privacy, "通知设置", "应用信息", {
            Permissions.openNotificationSettings(this)
        }, {
            Permissions.openAppDetails(this)
        })
        button(privacy, "清除本地截图、回答与历史", outlined = true) {
            HistoryStore.clear(this)
            FrameStore.clear(this)
            Prefs.saveLastAnswer(this, "")
            Prefs.saveLastQuestion(this, "")
            NotificationManagerCompat.from(this).cancelAll()
            toast("已清除本地数据")
        }

        val accessibility = card(panel, "无障碍（推荐）", "用于读取屏幕文字，并在 Android 11+ 免授权截图")
        accessibilityStatusText = caption("状态：读取中…")
        accessibility.addView(accessibilityStatusText)
        button(accessibility, "打开无障碍设置", outlined = true) {
            Permissions.openAccessibilitySettings(this)
        }

        val shizuku = card(panel, "Shizuku 增强（可选）", "通过 Shizuku 可免 ADB 授予权限、启用无障碍")
        shizukuStatusText = caption("状态：读取中…")
        shizuku.addView(shizukuStatusText)
        buttonRow(shizuku, "请求 Shizuku 授权", "自动授权并启用", {
            if (!ShizukuHelper.isAvailable()) {
                toast("未检测到 Shizuku，请先安装并启动 Shizuku")
            } else if (!ShizukuHelper.hasPermission()) {
                ShizukuHelper.requestPermission()
                toast("请在 Shizuku 弹窗中允许本应用")
            } else {
                toast("Shizuku 已授权")
            }
        }, {
            uiScope.launch {
                if (!ShizukuHelper.hasPermission()) {
                    toast("请先请求 Shizuku 授权")
                    return@launch
                }
                toast("正在通过 Shizuku 执行授权…")
                val grant = withContext(Dispatchers.IO) { ShizukuHelper.grantRuntimePermissions(this@MainActivity) }
                val accessibility = withContext(Dispatchers.IO) { ShizukuHelper.enableAccessibility(this@MainActivity) }
                refreshStatus()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Shizuku 执行结果")
                    .setMessage((grant + "\n" + accessibility).ifBlank { "完成" })
                    .setPositiveButton("知道了", null)
                    .show()
            }
        })
        caption("Shizuku 由第三方应用提供，需要用户自行安装并授权。本应用仅调用其公开接口执行 pm/appops/settings 命令。")

        val about = card(panel, "关于", "识屏问答")
        about.addView(caption("版本 " + BuildConfig.VERSION_NAME + " · 本地处理优先，仅在你主动触发时上传截图或录音。"))
        about.addView(caption("屏幕与音频可能包含隐私内容，请确保你已获得相关授权，并避免上传密码、支付信息或他人敏感数据。"))
        about.addView(caption("受 FLAG_SECURE 保护的页面（部分银行、视频、考试软件）无法被采集，这是系统限制。"))
        button(about, "重新运行使用引导", outlined = true) { launchOnboarding() }
        return panel
    }

    // ---------------------------------------------------------------- status

    private fun refreshStatus() {
        if (this::statusCardBody.isInitialized) {
            statusCardBody.removeAllViews()
            val notifications = Permissions.hasNotifications(this)
            statusRow(
                statusCardBody, "通知权限",
                if (notifications) "已授权" else "未授权", notifications,
                if (notifications) "设置" else "授权"
            ) {
                if (Build.VERSION.SDK_INT >= 33 && !notifications) {
                    runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                } else {
                    Permissions.openNotificationSettings(this)
                }
            }
            val accessibility = Permissions.isAccessibilityEnabled(this)
            statusRow(
                statusCardBody, "无障碍服务", if (accessibility) "已开启" else "未开启", accessibility,
                if (accessibility) "设置" else "开启"
            ) {
                Permissions.openAccessibilitySettings(this)
            }
            val overlay = Permissions.hasOverlay(this)
            statusRow(
                statusCardBody, "悬浮窗", if (overlay) "已授权" else "未授权", overlay,
                if (overlay) null else "授权"
            ) {
                Permissions.openOverlaySettings(this)
            }
            val session = ScreenCaptureService.instance?.isCapturing() == true
            statusRow(
                statusCardBody, "常驻录屏", if (session) "运行中" else "未运行", session,
                if (session) null else "开始"
            ) {
                CaptureTriggerActivity.start(this, null, true)
            }
            val config = Prefs.load(this)
            val apiReady = config.visionProfiles().any {
                it.apiKey.isNotBlank() || !it.baseUrl.startsWith("https://api.openai.com", ignoreCase = true)
            }
            statusRow(
                statusCardBody, "AI 接口", if (apiReady) "已配置" else "未配置", apiReady,
                if (apiReady) null else "去配置"
            ) {
                tabLayout.getTabAt(1)?.select()
            }
        }
        accessibilityStatusText?.text =
            "状态：" + if (Permissions.isAccessibilityEnabled(this)) "已开启，可读取屏幕文字与静默截图" else "未开启"
        shizukuStatusText?.text = "状态：" + ShizukuHelper.statusText()
    }

    private fun statusRow(
        parent: LinearLayout,
        label: String,
        value: String,
        ok: Boolean,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (ok) Color.parseColor("#22C55E") else Color.parseColor("#EF4444"))
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        row.addView(TextView(this).apply {
            text = value
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (ok) Color.parseColor("#16A34A") else Color.parseColor("#DC2626"))
        })
        if (actionText != null && action != null) {
            row.addView(TextView(this).apply {
                text = actionText
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ui.brand())
                setPadding(dp(10), dp(4), dp(2), dp(4))
                setOnClickListener { action() }
            })
        }
        parent.addView(row)
    }

    // ---------------------------------------------------------------- actions

    private fun choosePreset(target: EditText, presets: List<PromptPreset>) {
        val items = presets.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择预设")
            .setItems(items) { _, which -> target.setText(presets[which].question) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectedAudioSource(): String =
        if ((sttSourceSpinner?.selectedItemPosition ?: 0) == 1) AudioRecordingService.SOURCE_SYSTEM
        else AudioRecordingService.SOURCE_MIC

    private fun snapNow() {
        runCatching { saveSettings(false) }
        AskDispatcher.snap(this)
    }

    private fun startAudio(source: String) {
        runCatching { saveSettings(false) }
        if (!Permissions.hasRecordAudio(this)) {
            pendingAudioSource = source
            runCatching { audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
            return
        }
        if (source == AudioRecordingService.SOURCE_SYSTEM) {
            if (Build.VERSION.SDK_INT < 29) {
                toast("Android 10 以下不支持系统内部音频采集，请改用麦克风")
                return
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            runCatching { audioProjection.launch(manager.createScreenCaptureIntent()) }
                .onFailure { toast("无法打开采集授权：" + (it.message ?: "")) }
        } else {
            AudioRecordingService.start(this, source)
            toast("已开始录音，可在通知栏点“停止并转写”")
        }
    }

    private fun onOverlaySwitch(checked: Boolean) {
        Prefs.setShowOverlay(this, checked)
        if (!checked) {
            stopService(Intent(this, OverlayService::class.java))
            return
        }
        if (!Permissions.hasOverlay(this)) {
            toast("请先授予悬浮窗权限")
            Permissions.openOverlaySettings(this)
            return
        }
        if (!OverlayService.isRunning) {
            val started = OverlayService.start(this)
            if (!started) toast("悬浮球启动失败")
        }
    }

    private fun setLauncherVisible(visible: Boolean) {
        if (!visible && !Permissions.hasNotifications(this)) {
            toast("请先授予通知权限：隐藏桌面图标后只能通过通知栏进入应用")
            return
        }
        val component = ComponentName(this, packageName + ".Launcher")
        val state = if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }.onSuccess {
            toast(if (visible) "已恢复桌面图标" else "已隐藏桌面图标；可通过通知栏“设置”进入应用")
        }.onFailure {
            toast("操作失败：" + (it.message ?: ""))
        }
    }

    private fun fetchModelsFor(fields: ProfileFields, label: String) {
        runCatching { saveSettings(false) }
        val config = Prefs.load(this)
        val profile = ApiProfile(
            baseUrl = fields.url.text.toString().trim(),
            apiKey = fields.key.text.toString().trim(),
            model = fields.model.text.toString().trim().ifBlank { "gpt-4o-mini" },
            extraHeaders = fields.headers.text.toString().trim(),
            timeoutSeconds = fields.timeout.text.toString().toIntOrNull() ?: 120
        )
        if (profile.baseUrl.isBlank()) {
            toast("请先填写 Base URL")
            return
        }
        toast("正在读取" + label + "模型列表…")
        uiScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { OpenAiClient.listModels(profile, config.allowHttp) }
            }.onSuccess { models ->
                if (models.isEmpty()) {
                    toast("服务端没有返回模型列表")
                    return@onSuccess
                }
                val shown = models.take(300)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(label + "模型（" + models.size + "）")
                    .setItems(shown.toTypedArray()) { _, which ->
                        fields.model.setText(shown[which])
                        toast("已填入模型：" + shown[which])
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }.onFailure {
                toast("读取失败：" + (it.message ?: "未知错误"))
            }
        }
    }

    private fun testConnection() {
        runCatching { saveSettings(false) }
        val config = Prefs.load(this)
        val profile = config.visionProfiles().firstOrNull()
        if (profile == null) {
            toast("请先填写接口地址与模型名")
            return
        }
        toast("正在测试连接…")
        uiScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { OpenAiClient.testConnection(profile, config.allowHttp) }
            }.onSuccess { message ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("连接测试")
                    .setMessage(message + "\n\n" + profile.baseUrl + "\n模型：" + profile.model)
                    .setPositiveButton("知道了", null)
                    .show()
            }.onFailure {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("连接失败")
                    .setMessage(it.message ?: "未知错误")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- save

    private fun saveSettings(showToast: Boolean): AppConfig {
        hideKeyboard()
        val defaults = AppConfig()
        val config = AppConfig(
            vision = readProfile(visionFields, defaults.vision),
            stt = readProfile(sttFields, defaults.stt),
            multimodal = readProfile(multiFields, defaults.multimodal),
            useMultimodal = useMultimodalSwitch.isChecked,
            prompt = promptField.text.toString().ifBlank { defaults.prompt },
            userPrompt = userPromptField.text.toString().ifBlank { defaults.userPrompt },
            delaySeconds = delayField.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 3,
            temperature = temperatureField.text.toString().toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.3f,
            topP = topPField.text.toString().toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
            maxTokens = maxTokensField.text.toString().toIntOrNull()?.coerceIn(1, 32000) ?: 1500,
            presencePenalty = presenceField.text.toString().toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f,
            frequencyPenalty = frequencyField.text.toString().toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f,
            seed = seedField.text.toString().toIntOrNull() ?: -1,
            imageDetail = imageDetailSpinner?.selectedItem?.toString() ?: "auto",
            imageMaxSide = imageMaxSideField.text.toString().toIntOrNull()?.coerceIn(480, 4096) ?: 1600,
            jpegQuality = jpegQualityField.text.toString().toIntOrNull()?.coerceIn(30, 100) ?: 80,
            frameIntervalMs = frameIntervalField.text.toString().toIntOrNull()?.coerceIn(100, 5000) ?: 400,
            persistentCapture = persistentSwitch.isChecked,
            allowHttp = allowHttpSwitch.isChecked,
            answerInNotification = answerNotificationSwitch.isChecked,
            silentNotifications = silentSwitch.isChecked,
            neutralText = neutralSwitch.isChecked,
            secureWindow = secureWindowSwitch.isChecked,
            showOverlay = overlaySwitch.isChecked,
            includeAccessibilityText = includeAccessibilityTextSwitch.isChecked,
            accessibilitySilentCapture = accessibilityCaptureSwitch.isChecked,
            sttLanguage = sttLanguageField.text.toString().trim().ifBlank { "zh" },
            sttSource = selectedAudioSource(),
            keepHistory = keepHistorySwitch.isChecked,
            historyLimit = historyLimitField.text.toString().toIntOrNull()?.coerceIn(10, 1000) ?: 200,
            autoStartOnBoot = bootSwitch.isChecked,
            extraBodyJson = extraBodyField.text.toString().trim(),
            autoCopyAnswer = autoCopySwitch.isChecked,
            voiceReadAnswer = false
        )
        Prefs.save(this, config)
        applyWindowFlags(config)
        if (config.showOverlay && !Permissions.hasOverlay(this)) {
            toast("悬浮球需要悬浮窗权限")
        }
        if (!config.allowHttp && config.visionProfiles().any { it.baseUrl.startsWith("http://", true) }) {
            toast("提示：HTTP 接口需要在“参数”中允许非 HTTPS")
        }
        if (showToast) toast("设置已保存")
        refreshStatus()
        return config
    }

    private fun readProfile(fields: ProfileFields, fallback: ApiProfile): ApiProfile {
        return ApiProfile(
            baseUrl = fields.url.text.toString().trim().ifBlank { fallback.baseUrl },
            apiKey = fields.key.text.toString().trim(),
            model = fields.model.text.toString().trim().ifBlank { fallback.model },
            backupBaseUrl = fields.backupUrl.text.toString().trim(),
            backupApiKey = fields.backupKey.text.toString().trim(),
            backupModel = fields.backupModel.text.toString().trim(),
            extraHeaders = fields.headers.text.toString().trim(),
            timeoutSeconds = fields.timeout.text.toString().toIntOrNull()?.coerceIn(5, 600) ?: 120
        )
    }

    private fun applyWindowFlags(config: AppConfig) {
        if (config.secureWindow) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun hideKeyboard() {
        runCatching {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
