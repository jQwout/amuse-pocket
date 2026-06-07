package com.example.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.Metro
import com.example.navigation.NavigationEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val navigationBridge = Metro.navigationBridge

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 64)
    val navigationEvents = _navigationEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            navigationBridge.events.collect { event ->
                _navigationEvents.emit(event)
            }
        }
    }
}
