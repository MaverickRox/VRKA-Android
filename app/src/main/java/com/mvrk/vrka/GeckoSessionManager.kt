package com.mvrk.vrka

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebRequestError
import java.net.URLEncoder

class GeckoSessionManager(private val context: Context) {

    private val runtimeManager = GeckoRuntimeManager.getInstance(context)
    val mediaBridge = runtimeManager.mediaBridge

    private val _navState = MutableStateFlow(BrowserNavState())
    val navState: StateFlow<BrowserNavState> = _navState.asStateFlow()

    private var session: GeckoSession? = null
    private var lastLoadedHost: String? = null

    val activeSession: GeckoSession
        get() {
            if (session == null || !session!!.isOpen) {
                createAndOpenSession()
            }
            return session!!
        }

    private fun createAndOpenSession(): GeckoSession {
        val newSession = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .useTrackingProtection(true)
                .build()
        )

        setupDelegates(newSession)
        newSession.open(runtimeManager.runtime)
        session = newSession
        return newSession
    }

    private fun setupDelegates(targetSession: GeckoSession) {
        targetSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
        }

        targetSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val cleanUrl = url.orEmpty()
                val currentHost = runCatching { Uri.parse(cleanUrl).host }.getOrNull()
                if (currentHost != null && currentHost != lastLoadedHost) {
                    lastLoadedHost = currentHost
                    mediaBridge.clear()
                }

                _navState.value = _navState.value.copy(
                    currentUrl = cleanUrl,
                    error = null
                )
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _navState.value = _navState.value.copy(canGoBack = canGoBack)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                _navState.value = _navState.value.copy(canGoForward = canGoForward)
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                loadUri(uri)
                return GeckoResult.fromValue(null)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                Log.w(TAG, "Load error on $uri: ${error.code}")
                _navState.value = _navState.value.copy(
                    isLoading = false,
                    error = "Failed to load: ${error.code}"
                )
                return GeckoResult.fromValue(null)
            }
        }

        targetSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                _navState.value = _navState.value.copy(
                    isLoading = true,
                    progress = 10,
                    error = null
                )
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                _navState.value = _navState.value.copy(
                    isLoading = false,
                    progress = 100
                )
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                _navState.value = _navState.value.copy(
                    progress = progress,
                    isLoading = progress < 100
                )
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) {
                _navState.value = _navState.value.copy(
                    isSecure = securityInfo.isSecure
                )
            }
        }

        targetSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    _navState.value = _navState.value.copy(title = title)
                }
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "GeckoSession crashed, recreating session...")
                close()
                createAndOpenSession()
                val current = _navState.value.currentUrl
                if (current.isNotBlank()) {
                    loadUri(current)
                }
            }
        }
    }

    fun loadUri(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return

        val formattedUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("about:", ignoreCase = true) || trimmed.startsWith("resource:", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(trimmed, "UTF-8")
        }

        _navState.value = _navState.value.copy(
            currentUrl = formattedUrl,
            isLoading = true,
            progress = 10,
            error = null
        )

        activeSession.loadUri(formattedUrl)
    }

    fun goBack() {
        if (_navState.value.canGoBack) {
            session?.goBack()
        }
    }

    fun goForward() {
        if (_navState.value.canGoForward) {
            session?.goForward()
        }
    }

    fun reload() {
        session?.reload()
    }

    fun stop() {
        session?.stop()
        _navState.value = _navState.value.copy(isLoading = false)
    }

    fun close() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session: ${e.message}")
        }
        session = null
    }

    companion object {
        private const val TAG = "VRKA-GeckoSession"
    }
}
