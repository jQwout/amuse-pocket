package com.example.ui.webview

data class WebViewUiState(
    val url: String = "https://chat.qwen.ai/", // Direct route to Qwen AI portal as requested
    val isLoading: Boolean = true,
    val capturedCookie: String? = null,
    val error: String? = null
)

sealed interface WebViewIntent {
    data class PageLoadProgress(val progress: Int) : WebViewIntent
    data class PageFinished(val url: String, val cookieString: String?) : WebViewIntent
    data class OnCookieCaptured(val cookies: String) : WebViewIntent
    data class OnError(val description: String) : WebViewIntent
    object ForceProceed : WebViewIntent
}
