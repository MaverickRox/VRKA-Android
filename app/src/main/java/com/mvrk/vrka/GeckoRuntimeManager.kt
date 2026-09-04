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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GeckoRuntimeManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val mediaBridge = GeckoMediaBridge()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _uBlockActive = MutableStateFlow(false)
    val uBlockActive: StateFlow<Boolean> = _uBlockActive.asStateFlow()

    private val _mediaDetectorActive = MutableStateFlow(false)
    val mediaDetectorActive: StateFlow<Boolean> = _mediaDetectorActive.asStateFlow()

    @Volatile
    var mediaDetector: WebExtension? = null
        private set

    private val initMutex = Mutex()

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
        setupPromptDelegate(instance)
        _isReady.value = true
        instance
    }

    private fun setupPromptDelegate(runtimeInstance: GeckoRuntime) {
        runtimeInstance.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                Log.i(TAG, "onInstallPromptRequest for built-in extension: ${extension.id}")
                return GeckoResult.fromValue(
                    WebExtension.PermissionPromptResponse(
                        /* isPermissionsGranted = */ true,
                        /* isPrivateModeGranted = */ true,
                        /* isTechnicalAndInteractionDataGranted = */ true
                    )
                )
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }
    }

    /**
     * Deterministic readiness gate for fallback extensions (uBlock Origin & Media Detector).
     * Ensures extensions are installed, granted required permissions, and explicitly allowed
     * in private browsing before navigation occurs.
     */
    suspend fun ensureExtensionsReady(timeoutMs: Long = 15000L): Boolean {
        if (_uBlockActive.value && _mediaDetectorActive.value) {
            return true
        }
        return initMutex.withLock {
            if (_uBlockActive.value && _mediaDetectorActive.value) {
                return@withLock true
            }
            withContext(Dispatchers.Main) {
                runCatching {
                    withTimeout(timeoutMs) {
                        val controller = runtime.webExtensionController

                        // 1. Install & Register Media Detector WebExtension
                        if (!_mediaDetectorActive.value) {
                            Log.i(TAG, "Installing Media Detector extension...")
                            val detector = controller.ensureBuiltIn(
                                "resource://android/assets/extensions/media-detector/",
                                MEDIA_DETECTOR_ID
                            ).awaitResult() ?: throw IllegalStateException("Media Detector returned null")

                            val privateDetector = controller.setAllowedInPrivateBrowsing(detector, true).awaitResult()
                            val effectiveDetector = privateDetector ?: detector
                            detector.setMessageDelegate(mediaBridge, "browser")
                            effectiveDetector.setMessageDelegate(mediaBridge, "browser")
                            mediaDetector = effectiveDetector
                            Log.i(TAG, "Media Detector active and allowed in private browsing: ${effectiveDetector.id}")
                            _mediaDetectorActive.value = true
                        }

                        // 2. Install & Register uBlock Origin WebExtension
                        if (!_uBlockActive.value) {
                            Log.i(TAG, "Installing uBlock Origin extension...")
                            val ublock = controller.ensureBuiltIn(
                                "resource://android/assets/extensions/ublock/",
                                UBLOCK_ID
                            ).awaitResult() ?: throw IllegalStateException("uBlock Origin returned null")

                            val privateUblock = controller.setAllowedInPrivateBrowsing(ublock, true).awaitResult()
                            Log.i(TAG, "uBlock Origin active and allowed in private browsing: ${privateUblock?.id ?: ublock.id}")
                            _uBlockActive.value = true
                        }
                        true
                    }
                }.getOrElse { error ->
                    Log.e(TAG, "Failed to ensure extensions ready within ${timeoutMs}ms: ${error.message}", error)
                    false
                }
            }
        }
    }

    companion object {
        private const val TAG = "VRKA-GeckoRuntime"
        const val UBLOCK_ID = "uBlock0@raymondhill.net"
        const val MEDIA_DETECTOR_ID = "media-detector@vrka.mvrk.com"

        @Volatile
        private var INSTANCE: GeckoRuntimeManager? = null

        fun getInstance(context: Context): GeckoRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeckoRuntimeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

private suspend fun <T> GeckoResult<T>.awaitResult(): T? =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        accept(
            { value ->
                if (continuation.isActive) {
                    continuation.resume(value)
                }
            },
            { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        error ?: RuntimeException("GeckoResult completed exceptionally with null error")
                    )
                }
            }
        )
    }
