package com.screenask.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single entry point for every AI request: profile failover, history, notification.
 */
object AskEngine {
    private val requestLock = Mutex()

    data class Result(val answer: String?, val error: String?, val model: String?)

    suspend fun ask(
        context: Context,
        kind: String,
        question: String,
        image: ByteArray?,
        previousAnswer: String? = null,
        useSystemPrompt: Boolean = true,
        includeAccessibilityText: Boolean = true,
        notify: Boolean = true
    ): Result = requestLock.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val config = Prefs.load(appContext)
            val profiles = config.visionProfiles()
            if (profiles.isEmpty()) {
                val message = "尚未配置可用的 AI 接口（Base URL / API Key / 模型名）"
                if (notify) NotificationHelper.showError(appContext, message)
                return@withContext Result(null, message, null)
            }
            val imageUrl = image?.let {
                "data:image/jpeg;base64," + Base64.encodeToString(it, Base64.NO_WRAP)
            }
            val accessibility = if (includeAccessibilityText && config.includeAccessibilityText) {
                ScreenTextAccessibilityService.lastText.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val messages = OpenAiClient.buildMessages(
                config = config,
                question = question,
                imageDataUrl = imageUrl,
                previousAnswer = previousAnswer,
                accessibilityText = accessibility,
                useSystemPrompt = useSystemPrompt
            )
            val errors = ArrayList<String>()
            for (profile in profiles) {
                try {
                    val answer = OpenAiClient.chat(profile, config, messages)
                    Prefs.saveLastAnswer(appContext, answer)
                    Prefs.saveLastQuestion(appContext, question)
                    if (config.keepHistory) {
                        HistoryStore.add(
                            appContext,
                            kind = kind,
                            question = question,
                            answer = answer,
                            model = profile.model,
                            hasImage = image != null,
                            limit = config.historyLimit
                        )
                    }
                    if (config.autoCopyAnswer) copyToClipboard(appContext, answer)
                    if (notify && config.answerInNotification) {
                        NotificationHelper.showAnswer(appContext, answer, NotificationHelper.titleFor(kind))
                    }
                    return@withContext Result(answer, null, profile.model)
                } catch (e: Exception) {
                    errors += (profile.model + "：" + (e.message ?: e.javaClass.simpleName))
                }
            }
            val message = errors.joinToString("\n").ifBlank { "未知错误" }
            if (notify) NotificationHelper.showError(appContext, message)
            Result(null, message, null)
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("识屏问答", text))
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ shows the standard clipboard confirmation itself.
            }
        }
    }
}
