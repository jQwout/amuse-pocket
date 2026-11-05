package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for POST_NOTIFICATIONS as soon as the app opens, while it is not granted.
 *
 * The app never asked on its own: only an agent setting a reminder showed a permission card. So on a phone
 * where nobody had set a reminder, a reply that finished while the app was in the background (TurnManager's
 * push) was dropped silently, and so was any heartbeat. The owner chose to ask right at the start.
 * Android itself stops showing the dialog after two refusals, so a "no" is not nagged forever; Settings is
 * the way back.
 */
@Composable
fun AskNotificationsOnStart() {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
  val context = LocalContext.current
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(Unit) {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
  }
}
