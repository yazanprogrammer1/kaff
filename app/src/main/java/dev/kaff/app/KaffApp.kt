package dev.kaff.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.kaff.app.service.notifications.KaffNotifications

@HiltAndroidApp
class KaffApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KaffNotifications.createChannels(this)
    }
}
