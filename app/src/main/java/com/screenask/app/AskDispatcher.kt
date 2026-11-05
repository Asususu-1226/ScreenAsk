package com.screenask.app

import android.content.Context

/** Routes a "识屏 / 追问" request to the cheapest available capture path. */
object AskDispatcher {
    fun snap(context: Context, question: String? = null) {
        val config = Prefs.load(context)
        val session = ScreenCaptureService.instance
        if (session != null && session.isCapturing()) {
            session.requestSnap(question)
            return
        }
        if (config.accessibilitySilentCapture && ScreenTextAccessibilityService.isConnected()) {
            val started = AskService.start(context, AskService.ACTION_ACCESSIBILITY_ASK, question, null)
            if (started) return
        }
        CaptureTriggerActivity.start(context, question)
    }

    fun followUp(context: Context, text: String) {
        val session = ScreenCaptureService.instance
        if (session != null && session.isCapturing()) {
            session.requestFollowUp(text)
            return
        }
        val started = AskService.start(context, AskService.ACTION_FOLLOW_UP, null, text)
        if (!started) {
            // Starting a foreground service from the background can be refused;
            // a visible activity is always allowed to do it.
            CaptureTriggerActivity.startFollowUp(context, text)
        }
    }

    fun askStoredFrame(context: Context, question: String? = null) {
        val started = AskService.start(context, AskService.ACTION_FRAME_ASK, question, null)
        if (!started) NotificationHelper.showError(context, "后台任务启动失败，请打开应用后再试")
    }
}
