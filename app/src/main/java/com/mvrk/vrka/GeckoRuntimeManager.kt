package com.mvrk.vrka

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
import org.mozilla.geckoview.StorageController
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

    /**
     * Task-scoped browser sessions currently open. Keyed by fallback task id so a stale
     * cleanup can never mark a newer session inactive.
     */
    private val openBrowserSessions = MutableStateFlow<Set<String>>(emptySet())
    val browserSessionActive: StateFlow<Boolean> = openBrowserSessions
        .map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun browserSessionOpened(taskId: String) {
        openBrowserSessions.update { it + taskId }
    }

    fun browserSessionClosed(taskId: String) {
        openBrowserSessions.update { it - taskId }
    }

    /**
     * True when the bundled media-detector extension is available for lazy registration.
     * Reads the APK assets only; it must never instantiate the GeckoView runtime.
     */
    fun hasBundledMediaDetector(): Boolean = runCatching {
        context.assets.list(MEDIA_DETECTOR_ASSET_DIR)?.isNotEmpty() == true
    }.getOrDefault(false)

    @Volatile
    var mediaDetector: WebExtension? = null
        private set

    private val initMutex = Mutex()

    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .aboutConfigEnabled(GeckoPrivacyDefaults.ABOUT_CONFIG)
            .globalPrivacyControlEnabled(GeckoPrivacyDefaults.GLOBAL_PRIVACY_CONTROL)
            .remoteDebuggingEnabled(GeckoPrivacyDefaults.REMOTE_DEBUGGING)
            .debugLogging(GeckoPrivacyDefaults.DEBUG_LOGGING)
            .consoleOutput(GeckoPrivacyDefaults.CONSOLE_OUTPUT)
            .loginAutofillEnabled(GeckoPrivacyDefaults.LOGIN_AUTOFILL)
            .translationsOfferPopup(GeckoPrivacyDefaults.TRANSLATION_POPUP)
            .webManifest(GeckoPrivacyDefaults.WEB_MANIFEST)
            .crashPullNeverShowAgain(true)
            .experimentDelegate(NoExperimentsDelegate)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(GeckoPrivacyDefaults.ANTI_TRACKING)
                    .safeBrowsing(GeckoPrivacyDefaults.SAFE_BROWSING)
                    .cookieBehavior(GeckoPrivacyDefaults.COOKIE_BEHAVIOR)
                    .cookieBehaviorPrivateMode(GeckoPrivacyDefaults.COOKIE_BEHAVIOR)
                    .enhancedTrackingProtectionLevel(GeckoPrivacyDefaults.ETP_LEVEL)
                    .strictSocialTrackingProtection(GeckoPrivacyDefaults.STRICT_SOCIAL_TRACKING)
                    .cookiePurging(GeckoPrivacyDefaults.COOKIE_PURGING)
                    .bounceTrackingProtectionMode(GeckoPrivacyDefaults.BOUNCE_TRACKING_MODE)
                    .queryParameterStrippingEnabled(GeckoPrivacyDefaults.QUERY_PARAMETER_STRIPPING)
                    .queryParameterStrippingPrivateBrowsingEnabled(
                        GeckoPrivacyDefaults.QUERY_PARAMETER_STRIPPING,
                    )
                    .emailTrackerBlockingPrivateMode(
                        GeckoPrivacyDefaults.EMAIL_TRACKER_BLOCKING_PRIVATE_MODE,
                    )
                    .build()
            )
            .build()

        val instance = GeckoRuntime.create(context.applicationContext, settings)
        // GeckoView only collects usage telemetry after the embedder opts in. Keep the
        // preference explicitly false so an engine-side default can never turn it on.
        instance.notifyTelemetryPrefChanged(GeckoPrivacyDefaults.TELEMETRY)
        setupPromptDelegate(instance)
        _isReady.value = true
        instance
    }

    private object NoExperimentsDelegate : org.mozilla.geckoview.ExperimentDelegate {
        override fun onGetExperimentFeature(feature: String): GeckoResult<org.json.JSONObject> =
            GeckoResult.fromException(
                org.mozilla.geckoview.ExperimentDelegate.ExperimentException(
                    org.mozilla.geckoview.ExperimentDelegate.ExperimentException.ERROR_FEATURE_NOT_FOUND,
                ),
            )

        override fun onRecordExposureEvent(feature: String): GeckoResult<Void> =
            GeckoResult.fromValue(null)

        override fun onRecordExperimentExposureEvent(slug: String, feature: String): GeckoResult<Void> =
            GeckoResult.fromValue(null)

        override fun onRecordMalformedConfigurationEvent(slug: String, feature: String): GeckoResult<Void> =
            GeckoResult.fromValue(null)
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
                        removeRetiredExtension(controller)
                        seedUblockIfMissing()
                        val xpiDir = java.io.File(context.filesDir, "extensions/xpis")
                        val ublockXpi = java.io.File(xpiDir, "ublock.xpi")

                        // 1. Install & Register Media Detector WebExtension (internal bridge)
                        if (!_mediaDetectorActive.value) {
                            Log.i(TAG, "Installing Media Detector extension...")
                            val detector = runCatching {
                                controller.ensureBuiltIn(
                                    "resource://android/assets/extensions/media-detector/",
                                    MEDIA_DETECTOR_ID
                                ).awaitResult()
                            }.getOrNull() ?: throw IllegalStateException("Media Detector returned null")

                            val privateDetector = controller.setAllowedInPrivateBrowsing(detector, true).awaitResult()
                            val effectiveDetector = privateDetector ?: detector
                            detector.setMessageDelegate(mediaBridge, "browser")
                            effectiveDetector.setMessageDelegate(mediaBridge, "browser")
                            mediaDetector = effectiveDetector
                            Log.i(TAG, "Media Detector active and allowed in private browsing: ${effectiveDetector.id}")
                            _mediaDetectorActive.value = true
                        }

                        // 2. Install & Register uBlock Origin WebExtension from intact XPI
                        if (!_uBlockActive.value && ublockXpi.exists() && ublockXpi.length() > 0L) {
                            Log.i(TAG, "Installing uBlock Origin extension from intact XPI...")
                            ensurePromptDelegate(controller)
                            val ublock = installExtensionInternal(controller, "file://${ublockXpi.absolutePath}", UBLOCK_ID)
                                ?: throw IllegalStateException("uBlock Origin returned null")

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

    private suspend fun removeRetiredExtension(controller: WebExtensionController) {
        val installed = controller.list().awaitResult()
            ?.firstOrNull { it.id == RETIRED_WEB_EXTENSION_ID }
        if (installed != null) {
            val removed = runCatching {
                controller.uninstall(installed).awaitResult()
                controller.list().awaitResult()?.none { it.id == RETIRED_WEB_EXTENSION_ID } == true
            }.onFailure { error ->
                Log.w(TAG, "Could not uninstall a retired browser extension: ${error.message}")
            }.getOrDefault(false)
            if (removed) {
                Log.i(TAG, "Removed a retired browser extension")
            } else {
                Log.w(TAG, "A retired browser extension remains in the GeckoView profile")
            }
        }

        val retiredXpi = java.io.File(context.filesDir, "extensions/xpis/puemos.xpi")
        if (retiredXpi.exists() && !retiredXpi.delete()) {
            Log.w(TAG, "Could not remove a retired browser extension asset")
        }
    }

    private fun ensurePromptDelegate(controller: WebExtensionController) {
        if (controller.promptDelegate == null) {
            controller.promptDelegate = object : WebExtensionController.PromptDelegate {
                override fun onInstallPromptRequest(
                    extension: WebExtension,
                    permissions: Array<out String>,
                    origins: Array<out String>,
                    installReason: Array<out String>
                ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                    return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, true))
                }

                override fun onUpdatePrompt(
                    extension: WebExtension,
                    permissions: Array<out String>,
                    origins: Array<out String>,
                    installReason: Array<out String>
                ): GeckoResult<AllowOrDeny>? {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                override fun onOptionalPrompt(
                    extension: WebExtension,
                    permissions: Array<out String>,
                    origins: Array<out String>,
                    installReason: Array<out String>
                ): GeckoResult<AllowOrDeny>? {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
            }
        }
    }

    private suspend fun installExtensionInternal(controller: WebExtensionController, uri: String, id: String): WebExtension? {
        return if (uri.startsWith("resource://")) {
            controller.ensureBuiltIn(uri, id).awaitResult()
        } else {
            val list = controller.list().awaitResult() ?: emptyList()
            val existing = list.firstOrNull { it.id == id }
            if (existing != null) {
                runCatching { controller.uninstall(existing).awaitResult() }
            }
            controller.install(uri, WebExtensionController.INSTALLATION_METHOD_FROM_FILE).awaitResult()
        }
    }

    /**
     * Installs or updates a WebExtension from an intact verified XPI file and asserts that GeckoView
     * registers it as active with matching ID and version.
     */
    suspend fun installAndVerifyExtension(
        xpiFile: java.io.File,
        expectedId: String,
        expectedVersion: String,
    ): WebExtension = withContext(Dispatchers.Main) {
        if (!xpiFile.exists() || xpiFile.length() == 0L) {
            throw java.io.FileNotFoundException("XPI file does not exist or is empty: ${xpiFile.absolutePath}")
        }
        val controller = runtime.webExtensionController
        ensurePromptDelegate(controller)
        val fileUri = "file://${xpiFile.absolutePath}"
        Log.i(TAG, "Installing WebExtension $expectedId from $fileUri...")

        val extension = installExtensionInternal(controller, fileUri, expectedId)
            ?: throw IllegalStateException("GeckoView WebExtensionController returned null for $expectedId")

        controller.setAllowedInPrivateBrowsing(extension, true).awaitResult()

        // Read-back verification from GeckoView runtime
        val installedList = controller.list().awaitResult() ?: emptyList()
        val confirmedExt = installedList.firstOrNull { it.id == expectedId } ?: extension

        val actualId = confirmedExt.id
        val actualVersion = confirmedExt.metaData?.version?.trim() ?: ""
        val isEnabled = confirmedExt.metaData?.enabled ?: true

        if (actualId != expectedId) {
            throw SecurityException("Runtime verification failed: expected extension ID '$expectedId', got '$actualId'")
        }
        if (actualVersion.isNotBlank() && ComponentUpdateManager.cleanVersionString(actualVersion) != ComponentUpdateManager.cleanVersionString(expectedVersion)) {
            throw IllegalStateException("Runtime verification failed: expected version '$expectedVersion', got '$actualVersion'")
        }
        if (!isEnabled) {
            throw IllegalStateException("Runtime verification failed: extension $expectedId is not enabled in GeckoView")
        }

        if (expectedId == UBLOCK_ID) {
            _uBlockActive.value = true
        }
        Log.i(TAG, "WebExtension verified active in GeckoView: $actualId v$actualVersion")
        confirmedExt
    }

    /**
     * Restores previous known-good extension state in GeckoView if an update or verification fails.
     */
    suspend fun rollbackExtension(previousXpi: java.io.File?, extensionId: String) = withContext(Dispatchers.Main) {
        val controller = runtime.webExtensionController
        ensurePromptDelegate(controller)
        runCatching {
            if (previousXpi != null && previousXpi.exists() && previousXpi.length() > 0L) {
                val restored = installExtensionInternal(controller, "file://${previousXpi.absolutePath}", extensionId)
                if (restored != null) {
                    controller.setAllowedInPrivateBrowsing(restored, true).awaitResult()
                }
            } else {
                val assetName = "ublock.xpi"
                val targetXpi = java.io.File(context.filesDir, "extensions/xpis/$assetName")
                runCatching {
                    context.assets.open("extensions/$assetName").use { input ->
                        targetXpi.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (targetXpi.exists() && targetXpi.length() > 0L) {
                    val restored = installExtensionInternal(controller, "file://${targetXpi.absolutePath}", extensionId)
                    if (restored != null) {
                        controller.setAllowedInPrivateBrowsing(restored, true).awaitResult()
                        if (extensionId == UBLOCK_ID) {
                            _uBlockActive.value = true
                        }
                    }
                }
            }
            Log.i(TAG, "Rollback completed for extension: $extensionId")
        }.onFailure { error ->
            Log.e(TAG, "Rollback encountered error for $extensionId: ${error.message}", error)
        }
    }

    fun seedBundledExtensionsIfMissing() {
        seedBundledExtensionIfMissing("ublock.xpi")
    }

    private fun seedUblockIfMissing() {
        seedBundledExtensionIfMissing("ublock.xpi")
    }

    private fun seedBundledExtensionIfMissing(assetName: String) {
        val xpiDir = java.io.File(context.filesDir, "extensions/xpis")
        if (!xpiDir.exists()) xpiDir.mkdirs()
        val targetXpi = java.io.File(xpiDir, assetName)
        if (!targetXpi.exists() || targetXpi.length() == 0L) {
            runCatching {
                context.assets.open("extensions/$assetName").use { input ->
                    targetXpi.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Seeded bundled $assetName (${targetXpi.length()} bytes)")
            }
        }
    }

    suspend fun getInstalledExtensionVersion(id: String): String? = withContext(Dispatchers.Main) {
        runCatching {
            ensureExtensionsReady()
            if (!_isReady.value) return@runCatching null
            val controller = runtime.webExtensionController
            val list = controller.list().awaitResult() ?: emptyList()
            val ext = list.firstOrNull { it.id == id }
            ext?.metaData?.version?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    /**
     * Clears all browser session data (cookies, storage, auth sessions, site data, caches)
     * from GeckoView runtime storage and clears in-memory media bridge candidates.
     * Guaranteed NOT to modify download history, queue state, settings, or diagnostic logs.
     */
    suspend fun clearBrowserSession(): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val flags = StorageController.ClearFlags.COOKIES or
                StorageController.ClearFlags.DOM_STORAGES or
                StorageController.ClearFlags.AUTH_SESSIONS or
                StorageController.ClearFlags.SITE_DATA or
                StorageController.ClearFlags.ALL_CACHES
            runtime.storageController.clearData(flags).awaitResult()
            mediaBridge.clear()
            Log.i(TAG, "Browser session cleared successfully")
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to clear browser session: ${error.message}", error)
            false
        }
    }

    companion object {
        private const val TAG = "VRKA-GeckoRuntime"
        const val UBLOCK_ID = "uBlock0@raymondhill.net"
        const val MEDIA_DETECTOR_ID = "media-detector@vrka.mvrk.com"
        private const val RETIRED_WEB_EXTENSION_ID = "{e3ec0551-9bfa-4233-b9dd-6b36f6a80962}"
        private const val MEDIA_DETECTOR_ASSET_DIR = "extensions/media-detector"

        @Volatile
        private var INSTANCE: GeckoRuntimeManager? = null

        fun getInstance(context: Context): GeckoRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeckoRuntimeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

internal suspend fun <T> GeckoResult<T>.awaitResult(): T? =
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
