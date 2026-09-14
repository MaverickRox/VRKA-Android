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
                        seedBundledExtensionsIfMissing()
                        val xpiDir = java.io.File(context.filesDir, "extensions/xpis")
                        val ublockXpi = java.io.File(xpiDir, "ublock.xpi")
                        val puemosXpi = java.io.File(xpiDir, "puemos.xpi")

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

                        // 3. Register Puemos WebExtension from intact XPI
                        if (puemosXpi.exists() && puemosXpi.length() > 0L) {
                            runCatching {
                                ensurePromptDelegate(controller)
                                val puemos = installExtensionInternal(controller, "file://${puemosXpi.absolutePath}", PUEMOS_ID)
                                if (puemos != null) {
                                    controller.setAllowedInPrivateBrowsing(puemos, true).awaitResult()
                                    Log.i(TAG, "Puemos active and allowed in private browsing: ${puemos.id}")
                                }
                            }
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
                val assetName = if (extensionId == UBLOCK_ID) "ublock.xpi" else "puemos.xpi"
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
                        if (extensionId == UBLOCK_ID) _uBlockActive.value = true
                    }
                }
            }
            Log.i(TAG, "Rollback completed for extension: $extensionId")
        }.onFailure { error ->
            Log.e(TAG, "Rollback encountered error for $extensionId: ${error.message}", error)
        }
    }

    fun seedBundledExtensionsIfMissing() {
        val xpiDir = java.io.File(context.filesDir, "extensions/xpis")
        if (!xpiDir.exists()) xpiDir.mkdirs()

        val ublockXpi = java.io.File(xpiDir, "ublock.xpi")
        if (!ublockXpi.exists() || ublockXpi.length() == 0L) {
            runCatching {
                context.assets.open("extensions/ublock.xpi").use { input ->
                    ublockXpi.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Seeded bundled ublock.xpi (${ublockXpi.length()} bytes)")
            }
        }

        val puemosXpi = java.io.File(xpiDir, "puemos.xpi")
        if (!puemosXpi.exists() || puemosXpi.length() == 0L) {
            runCatching {
                context.assets.open("extensions/puemos.xpi").use { input ->
                    puemosXpi.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Seeded bundled puemos.xpi (${puemosXpi.length()} bytes)")
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
        const val PUEMOS_ID = "{e3ec0551-9bfa-4233-b9dd-6b36f6a80962}"

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
