package com.aiopenpocket.ui.forge

// @trace forge-host-capabilities-f648ae

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiopenpocket.agent.core.llm.ChatImages
import com.aiopenpocket.i18n.tr
import dev.ketoy.runtime.capability.CapabilityRegistry
import dev.ketoy.runtime.compose.KBCColorResolver
import dev.ketoy.runtime.compose.MapBackedKBCColorResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the app lends a forged screen, the "doors" of the old Primochki design: the screen never
 * touches the database, the gallery or a permission itself; it calls a function, and the app's own
 * code answers. The forge declares the same functions (forge/screens/.../host/ForgeHost.kt) and their
 * ids (forge/ketoy-capabilities.json); a bundle calling anything else does not compile, and a bundle
 * calling an id this app does not register is refused by the runtime.
 *
 * - Storage: Int and text by key, kept per agent ([ForgeHostScope]), so a tracker remembers its count.
 * - PhotoPicker: the app draws a button that opens the system photo picker (no permission needed: the
 *   app gets only the photo the person picked) and then the photo, kept under the key for next time.
 *
 * Arguments come from interpreted code, so every one is read defensively: an interpreter exception
 * kills the process (Compose has no error boundary), and a wrong type must not.
 */
object ForgeHostIds {
  const val LOAD_INT: Short = 16385
  const val SAVE_INT: Short = 16386
  const val LOAD_TEXT: Short = 16387
  const val SAVE_TEXT: Short = 16388
  const val PHOTO_PICKER: Short = 16389
}

/**
 * Whose screen is on and in which theme. Set by [ForgedBundleHost] as it composes: storage keys are that
 * agent's, and the named colors ([forgeColorResolver]) are the app's current color scheme.
 */
object ForgeHostScope {
  @Volatile
  var agentId: String = ""

  @Volatile
  var colors: ColorScheme? = null
}

/**
 * The app's palette by name, so a forged screen looks like the app in either theme: the bundle writes
 * `themeColor("accent")` (a Ketoy intrinsic) and this answers with the color of the scheme the host is
 * drawn in. Before any host composed (never, in practice) the light paper palette stands in.
 */
fun forgeColorResolver(): KBCColorResolver = MapBackedKBCColorResolver.Builder().apply {
  FORGE_COLOR_NAMES.forEach { (name, pick) -> register(name) { pick(ForgeHostScope.colors ?: FALLBACK_SCHEME) } }
}.build()

/** The names the forge's rules teach (forge/AGENTS.md); one list, so the two cannot drift silently. */
val FORGE_COLOR_NAMES: List<Pair<String, (ColorScheme) -> Color>> = listOf(
  "page" to { it.background },
  "card" to { it.surfaceVariant },
  "text" to { it.onBackground },
  "textMuted" to { it.onSurfaceVariant },
  "accent" to { it.primary },
  "onAccent" to { it.onPrimary },
  "line" to { it.outlineVariant },
)

private val FALLBACK_SCHEME = lightColorScheme()

/** The screens' storage: one preferences file, every key under the agent it belongs to. */
class ForgeScreenStorage(context: Context) {
  private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  private fun scoped(key: String) = "${ForgeHostScope.agentId}/$key"

  fun loadInt(key: String, default: Int): Int = runCatching { prefs.getInt(scoped(key), default) }.getOrDefault(default)

  fun saveInt(key: String, value: Int) = prefs.edit().putInt(scoped(key), value).apply()

  fun loadText(key: String, default: String): String =
    runCatching { prefs.getString(scoped(key), default) }.getOrNull() ?: default

  fun saveText(key: String, value: String) = prefs.edit().putString(scoped(key), value.take(MAX_TEXT)).apply()

  private companion object {
    const val FILE = "forged_screen_storage"
    /** A screen keeps notes and counters, not documents. */
    const val MAX_TEXT = 4_000
  }
}

fun CapabilityRegistry.registerForgeHostCapabilities(context: Context): CapabilityRegistry {
  val storage = ForgeScreenStorage(context)
  register(ForgeHostIds.LOAD_INT) { args -> storage.loadInt(args.string(0), args.int(1)) }
  register(ForgeHostIds.SAVE_INT) { args -> storage.saveInt(args.string(0), args.int(1)) }
  register(ForgeHostIds.LOAD_TEXT) { args -> storage.loadText(args.string(0), args.string(1)) }
  register(ForgeHostIds.SAVE_TEXT) { args -> storage.saveText(args.string(0), args.string(1)) }
  registerComposable(ForgeHostIds.PHOTO_PICKER) { args -> ForgedPhotoPicker(args.string(0), args.string(1), storage) }
  return this
}

private fun List<Any?>.string(index: Int): String = getOrNull(index)?.toString().orEmpty()

private fun List<Any?>.int(index: Int): Int = (getOrNull(index) as? Number)?.toInt() ?: 0

/** The photo slot a screen asked for: the photo kept under [key] (if any), and a button to pick one. */
@Composable
private fun ForgedPhotoPicker(key: String, label: String, storage: ForgeScreenStorage) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val slot = "photo/$key"
  var path by remember(key) { mutableStateOf(storage.loadText(slot, "")) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
      scope.launch {
        // The same copy a chat photo gets: the picker's grant ends, the copy stays.
        val saved = withContext(Dispatchers.IO) { ChatImages.import(context, uri) } ?: return@launch
        storage.saveText(slot, saved)
        path = saved
      }
    }
  }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (path.isNotBlank() && File(path).isFile) {
      AsyncImage(
        model = File(path),
        contentDescription = label,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp)),
      )
    }
    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
      Text(text = if (path.isBlank()) label.ifBlank { tr("Выбрать фото", "Pick a photo") } else tr("Другое фото", "Another photo"))
    }
  }
}
