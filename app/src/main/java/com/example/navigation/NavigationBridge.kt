package com.example.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface NavigationEvent {
    object NavigateToWebView : NavigationEvent
    object NavigateToChat : NavigationEvent
}

object NavigationBridge {
    private val _events = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun navigateTo(event: NavigationEvent) {
        _events.tryEmit(event)
    }
}
