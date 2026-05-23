package com.dns.fridaloader

import android.app.Application
import com.downloader.PRDownloader
import com.downloader.PRDownloaderConfig

class MainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PRDownloader.initialize(
            this,
            PRDownloaderConfig.newBuilder().setDatabaseEnabled(true).build()
        )
    }
}
