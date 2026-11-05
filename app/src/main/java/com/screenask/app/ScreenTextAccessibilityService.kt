package com.screenask.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional accessibility service.
 * - collects visible text so answers can reference text the model might misread;
 * - on Android 11+ takes screenshots without the MediaProjection consent dialog.
 */
class ScreenTextAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        private var instance: ScreenTextAccessibilityService? = null

        @Volatile
        var lastText: String = ""
            private set

        @Volatile
        var lastPackage: String = ""
            private set

        fun isConnected(): Boolean = instance != null

        fun captureScreenshot(timeoutMs: Long = 3000): ByteArray? {
            if (Build.VERSION.SDK_INT < 30) return null
            val service = instance ?: return null
            val latch = CountDownLatch(1)
            var result: ByteArray? = null
            val executor = Executors.newSingleThreadExecutor()
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardware = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                            if (bitmap != null) {
                                val software = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                if (software != null) {
                                    val out = ByteArrayOutputStream()
                                    software.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                    result = out.toByteArray()
                                    software.recycle()
                                }
                                bitmap.recycle()
                            }
                            hardware.close()
                        } catch (_: Throwable) {
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                })
            } catch (_: Throwable) {
                latch.countDown()
            }
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                executor.shutdown()
            }
            return result
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        val builder = StringBuilder()
        collect(root, builder, 0)
        val text = builder.toString().trim()
        if (text.isNotBlank()) {
            lastText = text.take(8000)
            lastPackage = root.packageName?.toString().orEmpty()
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, builder: StringBuilder, depth: Int) {
        if (node == null || depth > 24 || builder.length > 12000) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) builder.append(text).append('\n')
        else if (description.isNotEmpty()) builder.append(description).append('\n')
        val count = node.childCount
        for (i in 0 until count) {
            collect(node.getChild(i), builder, depth + 1)
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
