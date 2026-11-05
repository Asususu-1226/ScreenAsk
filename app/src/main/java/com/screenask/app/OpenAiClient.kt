package com.screenask.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal but tolerant OpenAI-compatible client:
 * chat completions (vision + text), model list and audio transcription.
 */
object OpenAiClient {
    private val clients = HashMap<Int, OkHttpClient>()

    private fun http(timeoutSeconds: Int): OkHttpClient = synchronized(clients) {
        clients.getOrPut(timeoutSeconds) {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .callTimeout((timeoutSeconds + 30).toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    fun normalizeBase(baseUrl: String, allowHttp: Boolean): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.isNotEmpty()) { "API 地址不能为空" }
        val lower = base.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw ApiException("API 地址必须以 http:// 或 https:// 开头")
        }
        if (lower.startsWith("http://") && !allowHttp) {
            throw ApiException("该地址使用 HTTP。若确认可信（如局域网自建），请在“参数”里打开“允许非 HTTPS 接口”")
        }
        return base
    }

    fun endpoint(baseUrl: String, path: String, allowHttp: Boolean): String {
        val base = normalizeBase(baseUrl, allowHttp)
        val suffix = path.trimStart('/')
        // The user may have pasted a full endpoint instead of a base URL.
        if (base.endsWith("/" + suffix)) return base
        if (base.endsWith("/chat/completions") || base.endsWith("/audio/transcriptions")) {
            return base.substringBeforeLast("/") + "/" + suffix
        }
        val hasVersion = Regex("/v[0-9]+[a-zA-Z0-9]*$").containsMatchIn(base)
        return if (hasVersion) base + "/" + suffix else base + "/v1/" + suffix
    }

    private fun applyHeaders(builder: Request.Builder, profile: ApiProfile) {
        if (profile.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer " + profile.apiKey.trim())
        }
        profile.extraHeaders.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val index = line.indexOf(':')
            if (index <= 0) return@forEach
            val name = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            if (name.isNotEmpty()) builder.header(name, value)
        }
    }

    private fun requestBuilder(profile: ApiProfile, path: String, allowHttp: Boolean): Request.Builder {
        require(profile.model.isNotBlank()) { "请先填写模型名称" }
        val builder = Request.Builder().url(endpoint(profile.baseUrl, path, allowHttp))
        applyHeaders(builder, profile)
        return builder
    }

    fun buildMessages(
        config: AppConfig,
        question: String,
        imageDataUrl: String?,
        previousAnswer: String?,
        accessibilityText: String?,
        useSystemPrompt: Boolean = true
    ): JSONArray {
        val messages = JSONArray()
        if (useSystemPrompt && config.prompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", config.prompt))
        }
        val textParts = StringBuilder()
        if (!previousAnswer.isNullOrBlank()) {
            textParts.append("上一轮回答：\n").append(previousAnswer).append("\n\n")
        }
        textParts.append(question)
        if (!accessibilityText.isNullOrBlank()) {
            textParts.append("\n\n【无障碍读取到的屏幕文字，可能不完整，仅作参考】\n")
            textParts.append(accessibilityText.take(6000))
        }
        if (imageDataUrl == null) {
            messages.put(JSONObject().put("role", "user").put("content", textParts.toString()))
        } else {
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", textParts.toString()))
            parts.put(
                JSONObject().put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", imageDataUrl).put("detail", config.imageDetail)
                    )
            )
            messages.put(JSONObject().put("role", "user").put("content", parts))
        }
        return messages
    }

    fun chat(profile: ApiProfile, config: AppConfig, messages: JSONArray): String {
        val allowedHttp = config.allowHttp
        val fullBody = JSONObject()
            .put("model", profile.model)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", config.temperature.toDouble())
            .put("top_p", config.topP.toDouble())
            .put("max_tokens", config.maxTokens)
            .put("presence_penalty", config.presencePenalty.toDouble())
            .put("frequency_penalty", config.frequencyPenalty.toDouble())
        if (config.seed >= 0) fullBody.put("seed", config.seed)
        mergeExtraBody(fullBody, config.extraBodyJson)

        var lastError: ApiException? = null
        for (body in listOf(fullBody, minimalBody(fullBody))) {
            try {
                return executeChat(profile, config, body, allowedHttp)
            } catch (e: ApiException) {
                lastError = e
                if (!isParameterIncompatibility(e.message.orEmpty())) throw e
            }
        }
        throw lastError ?: ApiException("请求失败")
    }

    private fun minimalBody(fullBody: JSONObject): JSONObject = JSONObject()
        .put("model", fullBody.optString("model"))
        .put("messages", fullBody.optJSONArray("messages") ?: JSONArray())
        .put("stream", false)

    private fun isParameterIncompatibility(message: String): Boolean {
        val text = message.lowercase()
        if (!text.contains("http 4")) return false
        val keys = listOf("max_tokens", "max_completion_tokens", "temperature", "top_p", "presence_penalty", "frequency_penalty", "unsupported", "unknown field", "invalid")
        return keys.any { text.contains(it) }
    }

    private fun mergeExtraBody(body: JSONObject, extra: String) {
        if (extra.isBlank()) return
        val parsed = runCatching { JSONObject(extra.trim()) }.getOrNull() ?: return
        parsed.keys().forEach { key -> body.put(key, parsed.get(key)) }
    }

    private fun executeChat(
        profile: ApiProfile,
        config: AppConfig,
        body: JSONObject,
        allowHttp: Boolean
    ): String {
        val request = requestBuilder(profile, "chat/completions", allowHttp)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http(profile.timeoutSeconds).newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException("HTTP " + response.code + ": " + errorMessage(payload))
            val root = runCatching { JSONObject(payload) }.getOrElse { throw ApiException("返回内容不是 JSON：" + payload.take(300)) }
            root.optJSONObject("error")?.let { throw ApiException(it.optString("message", "接口返回错误")) }
            val choices = root.optJSONArray("choices")
                ?: throw ApiException("接口没有返回 choices，可能不是 OpenAI 兼容接口")
            if (choices.length() == 0) throw ApiException("接口返回空的 choices")
            val choice = choices.optJSONObject(0)
            val message = choice?.optJSONObject("message")
            val content = message?.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        append(item.optString("text", ""))
                    }
                }
                else -> ""
            }.ifBlank { choice?.optString("text", "").orEmpty() }
            if (text.isBlank()) {
                val reasoning = message?.optString("reasoning_content", "").orEmpty()
                if (reasoning.isNotBlank()) return reasoning
                throw ApiException("模型返回内容为空，可能是服务商响应格式不兼容")
            }
            return text.trim()
        }
    }

    fun listModels(profile: ApiProfile, allowHttp: Boolean): List<String> {
        val request = requestBuilder(profile, "models", allowHttp).get().build()
        http(profile.timeoutSeconds.coerceAtMost(60)).newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException("HTTP " + response.code + ": " + errorMessage(payload))
            val root = runCatching { JSONObject(payload) }.getOrElse { throw ApiException("返回内容不是 JSON") }
            val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
            val result = ArrayList<String>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id", "").ifBlank { item.optString("name", "") }
                if (id.isNotBlank()) result += id
            }
            return result.distinct().sorted()
        }
    }

    fun transcribe(profile: ApiProfile, config: AppConfig, audio: File): String {
        require(audio.exists() && audio.length() > 0L) { "录音文件为空" }
        val mime = if (audio.name.endsWith(".wav", true)) "audio/wav" else "audio/mp4"
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", profile.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", audio.name, audio.asRequestBody(mime.toMediaType()))
        val language = config.sttLanguage.trim()
        if (language.isNotEmpty() && language != "auto") builder.addFormDataPart("language", language)
        val request = requestBuilder(profile, "audio/transcriptions", config.allowHttp)
            .post(builder.build())
            .build()
        http(profile.timeoutSeconds).newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException("HTTP " + response.code + ": " + errorMessage(payload))
            val json = runCatching { JSONObject(payload) }.getOrNull()
            val text = json?.optString("text", "").orEmpty()
            if (text.isNotBlank()) return text.trim()
            return payload.trim()
        }
    }

    fun testConnection(profile: ApiProfile, allowHttp: Boolean): String {
        val models = listModels(profile, allowHttp)
        return if (models.isEmpty()) "连接成功（未返回模型列表）" else "连接成功，可用模型 " + models.size + " 个"
    }

    private fun errorMessage(payload: String): String = try {
        val root = JSONObject(payload)
        root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
            ?: payload.take(600)
    } catch (_: Exception) {
        payload.take(600)
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}

class ApiException(message: String) : Exception(message)
