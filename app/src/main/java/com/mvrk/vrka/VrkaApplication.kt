package com.mvrk.vrka

import android.app.Application
import android.content.Context

class VrkaApplication : Application() {
    val downloads: VrkaDownloadManager by lazy {
        VrkaDownloadManager.create(this)
    }
}

val Context.vrkaApplication: VrkaApplication
    get() = applicationContext as VrkaApplication

