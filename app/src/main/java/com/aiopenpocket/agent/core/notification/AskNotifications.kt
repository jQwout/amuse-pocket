package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for POST_NOTIFICATIONS the first time a turn starts, once per install.
 *
 * The app never asked on its own: only an agent setting a reminder showed a permission card. So on a phone
 * where nobody had set a reminder, a reply that finished while the app was in the background (TurnManager's
 * push) was dropped silently, and so was any heartbeat. The first turn is the moment the permission starts
 * to matter, and the person has just acted, so the system dialog is not a surprise. Asked once: a "no" is
 * respected (Android itself stops showing the dialog after two refusals; Settings is the way back).
 */
@Composable
fun AskNotificationsOnFirstTurn(turnRunning: Boolean) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
  val context = LocalContext.current
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(turnRunning) {
    if (!turnRunning) return@LaunchedEffect
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (granted) return@LaunchedEffect
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_ASKED, false)) return@LaunchedEffect
    prefs.edit().putBoolean(KEY_ASKED, true).apply()
    runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
  }
}

private const val PREFS = "notification_permission"
private const val KEY_ASKED = "asked_on_first_turn"
