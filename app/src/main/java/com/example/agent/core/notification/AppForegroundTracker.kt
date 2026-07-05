package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * «Приложение на экране?» через ProcessLifecycleOwner: STARTED и выше — любой экран приложения
 * виден. Гейт пуша об окончании фонового хода: внутри приложения вместо пуша — бейджи списка.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AppForegroundTracker : ForegroundState {
  override fun isInForeground(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
