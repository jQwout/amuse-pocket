package com.example.ui.webview

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ChatRepository
import com.example.navigation.NavigationEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import com.example.navigation.NavigationBridge

class WebViewViewModel(
    private val repository: ChatRepository,
    private val navigationBridge: NavigationBridge
) : ViewModel() {


    private val _state = MutableStateFlow(WebViewUiState())
    val state = _state.asStateFlow()

    init {
        checkExistingCookie()
    }

    private fun checkExistingCookie() {
        viewModelScope.launch {
            val cookie = repository.getCookie()
            if (cookie != null && cookie.cookieValue.isNotEmpty()) {
                // Cookies already captured, skip to Chat direct
                navigationBridge.navigateTo(NavigationEvent.NavigateToChat)
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun isAuthCookie(cookie: String?): Boolean {
        if (cookie == null || cookie.isEmpty()) return false
        val lowercaseCookie = cookie.lowercase()
        val parts = lowercaseCookie.split(";").map { it.trim() }
        var hasAuthIndicator = false
        for (part in parts) {
            val key = part.substringBefore("=")
            if (key.contains("token") || key.contains("session") || key.contains("auth") || 
                key.contains("user") || key.contains("login") || key.contains("sid") || 
                key.contains("uid") || key.contains("jwt") || key.contains("at_p") || 
                key.contains("g_state") || key.contains("account")) {
                hasAuthIndicator = true
                break
            }
        }
        return hasAuthIndicator || cookie.length > 250
    }

    fun handleIntent(intent: WebViewIntent) {
        when (intent) {
            is WebViewIntent.PageLoadProgress -> {
                _state.update { it.copy(isLoading = intent.progress < 100) }
            }
            is WebViewIntent.PageFinished -> {
                _state.update { 
                    it.copy(
                        isLoading = false,
                        capturedCookie = intent.cookieString
                    )
                }
                if (!intent.cookieString.isNullOrEmpty() && isAuthCookie(intent.cookieString)) {
                    handleIntent(WebViewIntent.OnCookieCaptured(intent.cookieString))
                }
            }
            is WebViewIntent.OnCookieCaptured -> {
                viewModelScope.launch {
                    repository.saveCookie(intent.cookies)
                    _state.update { it.copy(capturedCookie = intent.cookies) }
                    // Navigate automatically
                    navigationBridge.navigateTo(NavigationEvent.NavigateToChat)
                }
            }
            is WebViewIntent.ForceProceed -> {
                viewModelScope.launch {
                    val cookieStr = CookieManager.getInstance().getCookie("https://chat.qwen.ai/")
                    val finalCookies = if (cookieStr.isNullOrEmpty()) {
                        "manual_bypass_cookie_placeholder"
                    } else {
                        cookieStr
                    }
                    repository.saveCookie(finalCookies)
                    _state.update { it.copy(capturedCookie = finalCookies) }
                    navigationBridge.navigateTo(NavigationEvent.NavigateToChat)
                }
            }
            is WebViewIntent.OnError -> {
                _state.update { it.copy(error = intent.description, isLoading = false) }
            }
        }
    }
}
