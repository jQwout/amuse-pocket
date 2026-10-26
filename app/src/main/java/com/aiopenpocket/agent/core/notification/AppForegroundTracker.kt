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
 * "Is the app on screen?" via ProcessLifecycleOwner: STARTED and above — some screen of the app is
 * visible. The gate for the push about a background turn finishing: inside the app there are list
 * badges instead of a push.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AppForegroundTracker : ForegroundState {
  override fun isInForeground(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
