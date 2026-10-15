package com.mindfilter.app

import android.app.Application
import com.mindfilter.app.data.AppSettings

class MindFilterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
