package com.taksolutions.simsensor

import android.app.Application
import com.taksolutions.simsensor.notifications.Notifications

class SimSensorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}
