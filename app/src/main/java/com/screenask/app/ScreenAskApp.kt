package com.screenask.app

import android.app.Application

class ScreenAskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
