package com.mvrk.vrka

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class GeckoRuntimeManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val mediaBridge = GeckoMediaBridge()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _uBlockActive = MutableStateFlow(false)
    val uBlockActive: StateFlow<Boolean> = _uBlockActive.asStateFlow()

    private val _mediaDetectorActive = MutableStateFlow(false)
    val mediaDetectorActive: StateFlow<Boolean> = _mediaDetectorActive.asStateFlow()

    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .aboutConfigEnabled(false)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .build()
            )
            .build()

        val instance = GeckoRuntime.create(context.applicationContext, settings)
        initializeExtensions(instance)
        _isReady.value = true
        instance
    }

    private fun initializeExtensions(runtimeInstance: GeckoRuntime) {
        scope.launch(Dispatchers.Main) {
            val controller = runtimeInstance.webExtensionController

            // 1. Install & Register Media Detector WebExtension
            controller.ensureBuiltIn(
                "resource://android/assets/extensions/media-detector/",
                "media-detector@vrka.mvrk.com"
            ).accept(
                { extension ->
                    if (extension != null) {
                        Log.i(TAG, "Media Detector extension registered successfully: ${extension.id}")
                        extension.setMessageDelegate(mediaBridge, "browser")
                        _mediaDetectorActive.value = true
                    } else {
                        Log.w(TAG, "Media Detector extension returned null")
                    }
                },
                { error ->
                    Log.e(TAG, "Failed to register Media Detector extension: ${error?.message}", error)
                }
            )

            // 2. Install & Register uBlock Origin WebExtension
            controller.ensureBuiltIn(
                "resource://android/assets/extensions/ublock/",
                "uBlock0@raymondhill.net"
            ).accept(
                { extension ->
                    if (extension != null) {
                        Log.i(TAG, "uBlock Origin registered successfully: ${extension.id}")
                        _uBlockActive.value = true
                    } else {
                        Log.w(TAG, "uBlock Origin extension returned null")
                    }
                },
                { error ->
                    Log.e(TAG, "Failed to register uBlock Origin: ${error?.message}", error)
                }
            )
        }
    }

    companion object {
        private const val TAG = "VRKA-GeckoRuntime"

        @Volatile
        private var INSTANCE: GeckoRuntimeManager? = null

        fun getInstance(context: Context): GeckoRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeckoRuntimeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
