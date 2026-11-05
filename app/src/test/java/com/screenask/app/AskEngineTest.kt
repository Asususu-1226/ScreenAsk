package com.screenask.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenAskApp::class)
class AskEngineTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HistoryStore.clear(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun askSavesAnswerAndHistory() {
        server.enqueue(
            MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"答案是 42\"}}]}"
            ).setHeader("Content-Type", "application/json")
        )
        val base = server.url("/v1").toString().trimEnd('/')
        Prefs.save(
            context,
            AppConfig(
                multimodal = ApiProfile(baseUrl = base, model = "test-model", apiKey = ""),
                vision = ApiProfile(baseUrl = base, model = "test-model", apiKey = ""),
                useMultimodal = true,
                allowHttp = true,
                answerInNotification = false
            )
        )
        val result = runBlocking {
            AskEngine.ask(context, "parse", "请回答", byteArrayOf(1, 2, 3), notify = false)
        }
        assertNotNull(result.answer)
        assertEquals("答案是 42", result.answer)
        assertEquals("test-model", result.model)
        val history = HistoryStore.all(context)
        assertEquals(1, history.size)
        assertEquals("答案是 42", history[0].answer)
        assertTrue(history[0].hasImage)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("image_url"))
        assertTrue(body.contains("test-model"))
    }

    @Test
    fun transcribeSendsMultipartAndParsesText() {
        server.enqueue(
            MockResponse().setBody("{\"text\":\"听力原文\"}")
                .setHeader("Content-Type", "application/json")
        )
        val base = server.url("/v1").toString().trimEnd('/')
        val config = AppConfig(
            stt = ApiProfile(baseUrl = base, model = "whisper-1", apiKey = ""),
            allowHttp = true
        )
        val file = java.io.File(context.cacheDir, "unit-test.m4a").apply { writeBytes(ByteArray(64)) }
        val text = OpenAiClient.transcribe(config.stt, config, file)
        assertEquals("听力原文", text)
        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("whisper-1"))
        assertTrue(body.contains("name=\"file\""))
        file.delete()
    }

    @Test
    fun listModelsParsesData() {
        server.enqueue(
            MockResponse().setBody("{\"data\":[{\"id\":\"b-model\"},{\"id\":\"a-model\"}]}")
                .setHeader("Content-Type", "application/json")
        )
        val base = server.url("/v1").toString().trimEnd('/')
        val models = OpenAiClient.listModels(ApiProfile(baseUrl = base, model = "x", apiKey = ""), true)
        assertEquals(listOf("a-model", "b-model"), models)
    }

    @Test
    fun askFallsBackToSecondaryProfile() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"boom\"}}"))
        server.enqueue(
            MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":\"备用成功\"}}]}"
            ).setHeader("Content-Type", "application/json")
        )
        val base = server.url("/v1").toString().trimEnd('/')
        Prefs.save(
            context,
            AppConfig(
                multimodal = ApiProfile(baseUrl = base, model = "bad-model", apiKey = ""),
                vision = ApiProfile(baseUrl = base, model = "good-model", apiKey = ""),
                useMultimodal = true,
                allowHttp = true,
                answerInNotification = false
            )
        )
        val result = runBlocking {
            AskEngine.ask(context, "parse", "问题", null, notify = false)
        }
        assertEquals("备用成功", result.answer)
        assertEquals("good-model", result.model)
        assertEquals(2, server.requestCount)
    }
}