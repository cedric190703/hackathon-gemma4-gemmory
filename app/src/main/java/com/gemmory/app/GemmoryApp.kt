package com.gemmory.app

import android.app.Application
import com.gemmory.core.logging.AppLog

class GemmoryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        AppLog.i("App", "started")
    }
}
