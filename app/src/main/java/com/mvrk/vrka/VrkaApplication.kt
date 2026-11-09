package com.mvrk.vrka

import android.app.Application
import android.content.Context
import com.mvrk.vrka.storage.StorageRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VrkaApplication : Application() {
    private lateinit var applicationScope: CoroutineScope
    internal lateinit var storageRuntime: StorageRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storageRuntime = StorageRuntime(this, applicationScope).also { it.start() }
    }

    val downloads: VrkaDownloadManager by lazy {
        VrkaDownloadManager(
            context = this,
            settingsRepository = SettingsRepository(this, applicationScope),
            scope = applicationScope,
            customRepository = storageRuntime.repository,
            storageRuntime = storageRuntime,
        )
    }
}

val Context.vrkaApplication: VrkaApplication
    get() = applicationContext as VrkaApplication
