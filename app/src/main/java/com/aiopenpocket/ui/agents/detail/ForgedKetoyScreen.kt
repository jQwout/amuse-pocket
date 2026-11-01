package com.aiopenpocket.ui.agents.detail

// @trace cloud-forged-screens-40d702

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import dev.ketoy.runtime.bundle.KetoyBundleSource
import dev.ketoy.runtime.compose.KetoyScreen

/**
 * Renders one forged screen from the bytes the app downloaded. `Raw` rather than Ketoy's `Remote`
 * source on purpose: the download, its cache and its checksum stay with the app's own client, and the
 * runtime only ever interprets what is already on disk.
 *
 * The runtime comes from `LocalKetoyRuntime`, set once in `MainActivity`. When the bundle does not
 * load - wrong signature, an entry point that is not in it, a host older than the bundle - Ketoy
 * reports the reason through `onBundleLoadFailed` and draws `errorContent`; the app shows the reason instead
 * of a blank card.
 */
@Composable
fun ForgedKetoyScreen(
  entryPoint: String,
  bundle: ByteArray,
  modifier: Modifier = Modifier,
) {
  val source = remember(bundle) { KetoyBundleSource.Raw(bundle) }
  KetoyScreen(
    entryPoint = entryPoint,
    bundleSource = source,
    modifier = modifier,
    onBundleLoadFailed = { error -> Log.w(TAG, "Forged screen $entryPoint did not load: ${error.message}") },
    errorContent = { error -> ForgedScreenFallback(error.message ?: error::class.simpleName) },
    // The overload WITH nativeFallback. The one without it looks the entry point up in the KSP-generated
    // registry of native twins and throws from composition when there is none - which is always our
    // case: a forged screen exists only in the bundle.
    nativeFallback = { ForgedScreenFallback(null) },
  )
}

@Composable
internal fun ForgedScreenFallback(reason: String?, modifier: Modifier = Modifier) {
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

private const val TAG = "ForgedKetoyScreen"
