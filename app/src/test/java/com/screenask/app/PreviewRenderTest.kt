package com.screenask.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenAskApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewRenderTest {
    private fun render(view: View, name: String) {
        val dm = view.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val dir = File("build/ui-preview")
        dir.mkdirs()
        FileOutputStream(File(dir, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun renderScreens() {
        Prefs.setOnboarded(ApplicationProvider.getApplicationContext(), true)
        Prefs.save(
            ApplicationProvider.getApplicationContext(),
            AppConfig(
                multimodal = ApiProfile(
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "sk-demo-key-0123456789",
                    model = "gpt-4o-mini"
                ),
                vision = ApiProfile(
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "sk-demo-key-0123456789",
                    model = "gpt-4o-mini"
                ),
                stt = ApiProfile(
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "sk-demo-key-0123456789",
                    model = "whisper-1"
                ),
                persistentCapture = true
            )
        )
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        render(main.window.decorView, "main-identify.png")

        val field = MainActivity::class.java.getDeclaredField("tabLayout")
        field.isAccessible = true
        val tabs = field.get(main) as TabLayout
        tabs.getTabAt(1)?.select()
        render(main.window.decorView, "main-api.png")
        tabs.getTabAt(2)?.select()
        render(main.window.decorView, "main-params.png")

        val onboarding = Robolectric.buildActivity(OnboardingActivity::class.java).setup().get()
        render(onboarding.window.decorView, "onboarding-step1.png")
        val stepField = OnboardingActivity::class.java.getDeclaredField("step")
        stepField.isAccessible = true
        stepField.setInt(onboarding, 1)
        val renderMethod = OnboardingActivity::class.java.getDeclaredMethod("render")
        renderMethod.isAccessible = true
        renderMethod.invoke(onboarding)
        render(onboarding.window.decorView, "onboarding-step2.png")
        stepField.setInt(onboarding, 2)
        renderMethod.invoke(onboarding)
        render(onboarding.window.decorView, "onboarding-step3.png")
    }
}
