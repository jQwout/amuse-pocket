package com.example.ui.webview

data class WebViewUiState(
    val url: String = "https://htmlpreview.github.io/?https://gist.githubusercontent.com/username/mock-login/raw/index.html", // Default mock portal
    val isLoading: Boolean = true,
    val capturedCookie: String? = null,
    val error: String? = null
)

sealed interface WebViewIntent {
    data class PageLoadProgress(val progress: Int) : WebViewIntent
    data class PageFinished(val url: String, val cookieString: String?) : WebViewIntent
    data class OnCookieCaptured(val cookies: String) : WebViewIntent
    data class OnError(val description: String) : WebViewIntent
}
