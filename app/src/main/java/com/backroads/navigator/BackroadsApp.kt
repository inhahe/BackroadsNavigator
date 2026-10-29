package com.backroads.navigator

import android.app.Application
import org.osmdroid.config.Configuration

class BackroadsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid requires a user-agent for tile downloads
        Configuration.getInstance().apply {
            userAgentValue = "BackroadsNavigator/1.0"
            // Store tiles in app-specific cache
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }
    }
}
