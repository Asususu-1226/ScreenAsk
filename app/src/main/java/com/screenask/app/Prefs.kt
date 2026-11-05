package com.screenask.app

import android.content.Context

data class ApiProfile(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    /** Optional failover endpoint used only when the primary one fails. */
    val backupBaseUrl: String = "",
    val backupApiKey: String = "",
    val backupModel: String = "",
    /** "Header: value" lines appended to every request (e.g. custom gateway headers). */
    val extraHeaders: String = "",
    val timeoutSeconds: Int = 120
) {
    fun primary(): ApiProfile = copy(
        backupBaseUrl = "",
        backupApiKey = "",
        backupModel = ""
    )

    fun backup(): ApiProfile? {
        if (backupBaseUrl.isBlank() || backupModel.isBlank()) return null
        return ApiProfile(
            baseUrl = backupBaseUrl,
            apiKey = backupApiKey,
            model = backupModel,
            timeoutSeconds = timeoutSeconds,
            extraHeaders = extraHeaders
        )
    }
}

data class AppConfig(
    val vision: ApiProfile = ApiProfile(model = "gpt-4o-mini"),
    val stt: ApiProfile = ApiProfile(model = "whisper-1"),
    val multimodal: ApiProfile = ApiProfile(model = "gpt-4o-mini"),
    /** Use the multimodal profile first; vision profile remains the fallback pool. */
    val useMultimodal: Boolean = true,
    val prompt: String = DEFAULT_PROMPT,
    val userPrompt: String = "请解答这张截图里的内容。",
    val delaySeconds: Int = 3,
    val temperature: Float = 0.3f,
    val topP: Float = 1f,
    val maxTokens: Int = 1500,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0f,
    val seed: Int = -1,
    val imageDetail: String = "auto",
    val imageMaxSide: Int = 1600,
    val jpegQuality: Int = 80,
    val frameIntervalMs: Int = 400,
    val persistentCapture: Boolean = true,
    val allowHttp: Boolean = false,
    val answerInNotification: Boolean = true,
    val silentNotifications: Boolean = false,
    val neutralText: Boolean = false,
    val secureWindow: Boolean = true,
    val showOverlay: Boolean = false,
    val includeAccessibilityText: Boolean = true,
    val accessibilitySilentCapture: Boolean = true,
    val sttLanguage: String = "zh",
    val sttSource: String = "mic",
    val keepHistory: Boolean = true,
    val historyLimit: Int = 200,
    val autoStartOnBoot: Boolean = true,
    val extraBodyJson: String = "",
    val autoCopyAnswer: Boolean = false,
    val voiceReadAnswer: Boolean = false
) {
    fun visionProfiles(): List<ApiProfile> {
        val ordered = if (useMultimodal) listOf(multimodal, vision) else listOf(vision, multimodal)
        val result = ArrayList<ApiProfile>(4)
        ordered.forEach { profile ->
            val primary = profile.primary()
            if (primary.baseUrl.isNotBlank() && primary.model.isNotBlank()) result += primary
            profile.backup()?.let { if (it.baseUrl.isNotBlank()) result += it }
        }
        return result.distinctBy { it.baseUrl.trimEnd('/') + "|" + it.model }
    }

    companion object {
        const val DEFAULT_PROMPT =
            "你是屏幕内容理解助手。请先给出结论，再分点说明依据。若是题目，请给出完整解题思路与答案，并标出关键步骤；若是外语或听力材料，请给出原文与中文翻译。看不清或不确定的内容必须明确说明，不要编造。"
    }
}

object Prefs {
    private const val FILE = "screenask_settings"

    fun load(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        fun profile(prefix: String, defaultUrl: String, defaultModel: String): ApiProfile {
            return ApiProfile(
                baseUrl = prefs.getString(prefix + "_url", defaultUrl) ?: defaultUrl,
                apiKey = SecretStore.decrypt(prefs.getString(prefix + "_key", "").orEmpty()),
                model = prefs.getString(prefix + "_model", defaultModel) ?: defaultModel,
                backupBaseUrl = prefs.getString(prefix + "_backup_url", "").orEmpty(),
                backupApiKey = SecretStore.decrypt(prefs.getString(prefix + "_backup_key", "").orEmpty()),
                backupModel = prefs.getString(prefix + "_backup_model", "").orEmpty(),
                extraHeaders = prefs.getString(prefix + "_headers", "").orEmpty(),
                timeoutSeconds = prefs.getInt(prefix + "_timeout", 120).coerceIn(5, 600)
            )
        }
        val defaults = AppConfig()
        return AppConfig(
            vision = profile("vision", "https://api.openai.com/v1", "gpt-4o-mini"),
            stt = profile("stt", "https://api.openai.com/v1", "whisper-1"),
            multimodal = profile("multi", "https://api.openai.com/v1", "gpt-4o-mini"),
            useMultimodal = prefs.getBoolean("use_multi", true),
            prompt = prefs.getString("prompt", defaults.prompt) ?: defaults.prompt,
            userPrompt = prefs.getString("user_prompt", defaults.userPrompt) ?: defaults.userPrompt,
            delaySeconds = prefs.getInt("delay", 3).coerceIn(0, 30),
            temperature = prefs.getFloat("temperature", 0.3f).coerceIn(0f, 2f),
            topP = prefs.getFloat("top_p", 1f).coerceIn(0f, 1f),
            maxTokens = prefs.getInt("max_tokens", 1500).coerceIn(1, 32000),
            presencePenalty = prefs.getFloat("presence_penalty", 0f).coerceIn(-2f, 2f),
            frequencyPenalty = prefs.getFloat("frequency_penalty", 0f).coerceIn(-2f, 2f),
            seed = prefs.getInt("seed", -1),
            imageDetail = prefs.getString("image_detail", "auto") ?: "auto",
            imageMaxSide = prefs.getInt("image_max_side", 1600).coerceIn(480, 4096),
            jpegQuality = prefs.getInt("jpeg_quality", 80).coerceIn(30, 100),
            frameIntervalMs = prefs.getInt("frame_interval", 400).coerceIn(100, 5000),
            persistentCapture = prefs.getBoolean("persistent_capture", true),
            allowHttp = prefs.getBoolean("allow_http", false),
            answerInNotification = prefs.getBoolean("answer_in_notification", true),
            silentNotifications = prefs.getBoolean("silent_notifications", false),
            neutralText = prefs.getBoolean("neutral_text", false),
            secureWindow = prefs.getBoolean("secure_window", true),
            showOverlay = prefs.getBoolean("show_overlay", false),
            includeAccessibilityText = prefs.getBoolean("include_accessibility_text", true),
            accessibilitySilentCapture = prefs.getBoolean("accessibility_silent_capture", true),
            sttLanguage = prefs.getString("stt_language", "zh") ?: "zh",
            sttSource = prefs.getString("stt_source", "mic") ?: "mic",
            keepHistory = prefs.getBoolean("keep_history", true),
            historyLimit = prefs.getInt("history_limit", 200).coerceIn(10, 1000),
            autoStartOnBoot = prefs.getBoolean("boot_launcher", true),
            extraBodyJson = prefs.getString("extra_body_json", "").orEmpty(),
            autoCopyAnswer = prefs.getBoolean("auto_copy", false),
            voiceReadAnswer = prefs.getBoolean("voice_read", false)
        )
    }

    fun save(context: Context, config: AppConfig) {
        val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        fun putProfile(prefix: String, profile: ApiProfile) {
            editor.putString(prefix + "_url", profile.baseUrl.trim())
            editor.putString(prefix + "_key", SecretStore.encrypt(profile.apiKey.trim()))
            editor.putString(prefix + "_model", profile.model.trim())
            editor.putString(prefix + "_backup_url", profile.backupBaseUrl.trim())
            editor.putString(prefix + "_backup_key", SecretStore.encrypt(profile.backupApiKey.trim()))
            editor.putString(prefix + "_backup_model", profile.backupModel.trim())
            editor.putString(prefix + "_headers", profile.extraHeaders.trim())
            editor.putInt(prefix + "_timeout", profile.timeoutSeconds.coerceIn(5, 600))
        }
        putProfile("vision", config.vision)
        putProfile("stt", config.stt)
        putProfile("multi", config.multimodal)
        editor.putBoolean("use_multi", config.useMultimodal)
        editor.putString("prompt", config.prompt)
        editor.putString("user_prompt", config.userPrompt)
        editor.putInt("delay", config.delaySeconds.coerceIn(0, 30))
        editor.putFloat("temperature", config.temperature.coerceIn(0f, 2f))
        editor.putFloat("top_p", config.topP.coerceIn(0f, 1f))
        editor.putInt("max_tokens", config.maxTokens.coerceIn(1, 32000))
        editor.putFloat("presence_penalty", config.presencePenalty.coerceIn(-2f, 2f))
        editor.putFloat("frequency_penalty", config.frequencyPenalty.coerceIn(-2f, 2f))
        editor.putInt("seed", config.seed)
        editor.putString("image_detail", config.imageDetail)
        editor.putInt("image_max_side", config.imageMaxSide.coerceIn(480, 4096))
        editor.putInt("jpeg_quality", config.jpegQuality.coerceIn(30, 100))
        editor.putInt("frame_interval", config.frameIntervalMs.coerceIn(100, 5000))
        editor.putBoolean("persistent_capture", config.persistentCapture)
        editor.putBoolean("allow_http", config.allowHttp)
        editor.putBoolean("answer_in_notification", config.answerInNotification)
        editor.putBoolean("silent_notifications", config.silentNotifications)
        editor.putBoolean("neutral_text", config.neutralText)
        editor.putBoolean("secure_window", config.secureWindow)
        editor.putBoolean("show_overlay", config.showOverlay)
        editor.putBoolean("include_accessibility_text", config.includeAccessibilityText)
        editor.putBoolean("accessibility_silent_capture", config.accessibilitySilentCapture)
        editor.putString("stt_language", config.sttLanguage)
        editor.putString("stt_source", config.sttSource)
        editor.putBoolean("keep_history", config.keepHistory)
        editor.putInt("history_limit", config.historyLimit.coerceIn(10, 1000))
        editor.putBoolean("boot_launcher", config.autoStartOnBoot)
        editor.putString("extra_body_json", config.extraBodyJson)
        editor.putBoolean("auto_copy", config.autoCopyAnswer)
        editor.putBoolean("voice_read", config.voiceReadAnswer)
        editor.apply()
    }

    fun lastAnswer(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("last_answer", "").orEmpty()

    fun saveLastAnswer(context: Context, answer: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("last_answer", answer).apply()
    }

    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("onboarded", false)

    fun setOnboarded(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("onboarded", value).apply()
    }

    fun setShowOverlay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("show_overlay", enabled).apply()
    }

    fun lastQuestion(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("last_question", "").orEmpty()

    fun saveLastQuestion(context: Context, question: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("last_question", question).apply()
    }
}
