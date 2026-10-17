package com.hearthassist.app

import android.app.Application

class HearthAssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
