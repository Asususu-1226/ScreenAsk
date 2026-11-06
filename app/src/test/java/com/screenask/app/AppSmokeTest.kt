package com.screenask.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenAskApp::class)
class AppSmokeTest {

    @Test
    fun mainActivityCreatesWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.setOnboarded(context, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun historyStoreRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        HistoryStore.clear(context)
        HistoryStore.add(context, "parse", "问题", "答案", "model-x", true, 10)
        val all = HistoryStore.all(context)
        assertEquals(1, all.size)
        assertEquals("答案", all[0].answer)
        assertEquals("model-x", all[0].model)
        assertTrue(all[0].hasImage)
        HistoryStore.clear(context)
        assertTrue(HistoryStore.all(context).isEmpty())
    }

    @Test
    fun frameStoreRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FrameStore.clear(context)
        assertFalse(FrameStore.exists(context))
        FrameStore.save(context, byteArrayOf(1, 2, 3, 4))
        assertTrue(FrameStore.exists(context))
        assertEquals(4, FrameStore.read(context)?.size)
        FrameStore.clear(context)
    }

    @Test
    fun endpointsAreNormalised() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiClient.endpoint("https://api.example.com/v1", "chat/completions", false)
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiClient.endpoint("https://api.example.com", "chat/completions", false)
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiClient.endpoint("https://api.example.com/v1/chat/completions", "chat/completions", false)
        )
    }

    @Test
    fun httpIsRejectedUnlessAllowed() {
        try {
            OpenAiClient.endpoint("http://192.168.1.5:8080/v1", "chat/completions", false)
            throw AssertionError("HTTP should have been rejected")
        } catch (expected: ApiException) {
            assertTrue(expected.message!!.contains("HTTP"))
        }
        assertEquals(
            "http://192.168.1.5:8080/v1/chat/completions",
            OpenAiClient.endpoint("http://192.168.1.5:8080/v1", "chat/completions", true)
        )
    }

    @Test
    fun messagesContainImagePart() {
        val config = AppConfig(imageDetail = "high")
        val messages = OpenAiClient.buildMessages(
            config, "问题", "data:image/jpeg;base64,abc", "上一轮", "屏幕文字", true
        )
        assertEquals(2, messages.length())
        val user = messages.getJSONObject(1)
        val parts = user.getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("high", parts.getJSONObject(1).getJSONObject("image_url").getString("detail"))
        assertTrue(parts.getJSONObject(0).getString("text").contains("屏幕文字"))
    }

    @Test
    fun onboardingActivityCreatesAndNavigates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.setOnboarded(context, false)
        val controller = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        // The wizard must survive pause/resume (permission dialogs do this)
        controller.pause().resume()
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
        Prefs.setOnboarded(context, true)
    }

    @Test
    fun historyActivityCreates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        HistoryStore.clear(context)
        HistoryStore.add(context, "parse", "题目", "解答", "model", true, 10)
        val controller = Robolectric.buildActivity(HistoryActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun notificationsCanBeBuilt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationHelper.createChannels(context)
        NotificationHelper.showLauncher(context)
        NotificationHelper.showAnswer(context, "这是答案", "AI 识屏回答")
        NotificationHelper.showSession(context, "常驻录屏中")
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.getNotificationChannel(NotificationHelper.CHANNEL_ANSWER) != null)
        NotificationHelper.cancelSession(context)
    }

    private fun collectInputTexts(root: View): List<String> {
        val values = ArrayList<String>()
        fun walk(view: View) {
            if (view is EditText) values.add(view.text?.toString().orEmpty())
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return values
    }

    @Test
    fun recreationDoesNotCopyOneInputIntoAllFields() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.setOnboarded(context, true)
        Prefs.save(
            context,
            AppConfig(
                delaySeconds = 7,
                temperature = 0.55f,
                maxTokens = 1234,
                historyLimit = 321,
                sttLanguage = "zh"
            )
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val before = collectInputTexts(controller.get().window.decorView)
        val state = Bundle()
        controller.saveInstanceState(state)
        controller.pause().stop().destroy()

        val controller2 = Robolectric.buildActivity(MainActivity::class.java)
            .create(state).start().resume().visible()
        val after = collectInputTexts(controller2.get().window.decorView)
        assertEquals(before.size, after.size)
        assertEquals(before, after)
        // Only the STT language field is allowed to contain "zh".
        assertEquals(1, after.count { it == "zh" })
        controller2.pause().stop().destroy()
    }

    @Test
    fun corruptedSettingsAreRepairedOnLoad() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("screenask_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("multi_url", "zh")
            .putString("multi_model", "zh")
            .putString("vision_url", "zh")
            .putString("vision_model", "zh")
            .putString("prompt", "zh")
            .putString("user_prompt", "zh")
            .putString("stt_language", "zh")
            .apply()
        val config = Prefs.load(context)
        assertTrue(config.multimodal.baseUrl.startsWith("https://"))
        assertEquals("gpt-4o-mini", config.multimodal.model)
        assertTrue(config.vision.baseUrl.startsWith("https://"))
        assertEquals(AppConfig.DEFAULT_PROMPT, config.prompt)
        assertEquals("zh", config.sttLanguage)
    }

    @Test
    fun promptPresetsAreNotEmpty() {
        assertTrue(PromptPresets.questions.isNotEmpty())
        assertTrue(PromptPresets.systemPrompts.isNotEmpty())
        assertTrue(JSONObject().put("ok", true).getBoolean("ok"))
    }
}