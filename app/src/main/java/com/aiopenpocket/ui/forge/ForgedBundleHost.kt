package com.aiopenpocket.ui.forge

// @trace cloud-forged-screens-40d702

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aiopenpocket.AiOpenPocketApp
import com.aiopenpocket.agent.core.forge.ForgedScreenState
import com.aiopenpocket.i18n.tr
import dev.ketoy.runtime.bundle.KetoyBundleSource
import dev.ketoy.runtime.compose.KetoyScreen
import dev.ketoy.runtime.compose.LocalKetoyBundleLoader
import dev.ketoy.runtime.compose.LocalKetoyRuntime
import kotlinx.coroutines.delay

/**
 * Renders one forged screen from the bytes the app downloaded, with everything the host owes it:
 *
 * - the Ketoy runtime, read from the app graph the first time a forged screen composes - not at
 *   start-up, since most launches never open one;
 * - `Raw` bytes rather than Ketoy's `Remote` source: the download, its cache and its checksum stay
 *   with the app's own client, the runtime only interprets what is on disk;
 * - the crash sentinel (see `ForgedScreenStore.markRendering`): the flag is written synchronously
 *   before the bundle composes and taken down [GRACE_MS] after Ketoy reports the bundle loaded - the
 *   load is asynchronous, so "two frames after the card composed" would be too early - and again
 *   when the screen leaves composition. An interpreter exception kills the process (Compose has no
 *   error boundary), and the flag is what turns the next launch into a failure card.
 *
 * When the bundle does not load - wrong signature, an entry point that is not in it - Ketoy draws
 * `errorContent`; the reason is shown, not a blank card.
 */
@Composable
fun ForgedBundleHost(
  agentId: String,
  ready: ForgedScreenState.Ready,
  bundle: ByteArray,
  modifier: Modifier = Modifier,
) {
  val graph = (LocalContext.current.applicationContext as AiOpenPocketApp).appGraph
  val runtime = remember { graph.ketoyRuntime }
  val loader = remember { graph.ketoyBundleLoader }
  val repository = graph.forgedScreenRepository

  remember(ready.jobId) { repository.markRendering(agentId, ready.jobId); Unit }
  var loaded by remember(ready.jobId) { mutableStateOf(false) }
  LaunchedEffect(ready.jobId, loaded) {
    if (!loaded) return@LaunchedEffect
    withFrameNanos { }
    withFrameNanos { }
    delay(GRACE_MS)
    repository.clearRendering(agentId)
  }
  DisposableEffect(ready.jobId) { onDispose { repository.clearRendering(agentId) } }

  val source = remember(bundle) { KetoyBundleSource.Raw(bundle) }
  CompositionLocalProvider(
    LocalKetoyRuntime provides runtime,
    LocalKetoyBundleLoader provides loader,
  ) {
    // A new job is a new screen: key on it so the interpreter is recreated, not recomposed with the
    // previous bundle's state (the entry point name is usually the same).
    key(ready.jobId) {
      // A light surface whatever the app theme: the worker's catalog tells the model to use fixed
      // dark text colours (the bundle cannot read the theme), and on the dark theme they vanished.
      Box(modifier = modifier.background(FORGED_SURFACE)) {
        KetoyScreen(
          entryPoint = ready.entryPoint,
          bundleSource = source,
          modifier = Modifier.fillMaxSize(),
        onBundleLoaded = { loaded = true },
        onBundleLoadFailed = { error -> Log.w(TAG, "Forged screen ${ready.entryPoint} did not load: ${error.message}") },
          errorContent = { error -> ForgedScreenFallback(error.message ?: error::class.simpleName) },
          nativeFallback = { ForgedScreenFallback(null) },
        )
      }
    }
  }
}

@Composable
fun ForgedScreenFallback(reason: String?, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
    Text(
      text = tr("Экран не запустился", "The screen could not start"),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.error,
    )
    Text(
      text = reason ?: tr("Бандл отклонён рантаймом.", "The runtime rejected the bundle."),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private const val TAG = "ForgedBundleHost"
private val FORGED_SURFACE = Color(0xFFF7F6F2)
private const val GRACE_MS = 1_500L
