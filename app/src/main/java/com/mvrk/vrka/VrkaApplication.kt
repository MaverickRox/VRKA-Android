package com.mvrk.vrka

import android.app.Application
import android.content.Context

class VrkaApplication : Application() {
    lateinit var downloads: VrkaDownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        downloads = VrkaDownloadManager.create(this)
    }
}

val Context.vrkaApplication: VrkaApplication
    get() = applicationContext as VrkaApplication

