package com.aiopenpocket.voice

// @trace voice-assistant-mode-a81a99

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Whether this app is the phone's assistant right now.
 *
 * Android 10+ has a public answer (the assistant role). Before that the only source is the secure
 * setting the system itself writes, read by its hidden name; if it cannot be read the answer is "no",
 * and the settings card simply offers to set it up.
 */
fun isDefaultAssistant(context: Context): Boolean {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val roles = context.getSystemService(RoleManager::class.java) ?: return false
    return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
  }
  val service = runCatching {
    Settings.Secure.getString(context.contentResolver, VOICE_INTERACTION_SERVICE_SETTING)
  }.getOrNull()
  return service?.substringBefore('/') == context.packageName
}

/**
 * Opens the system screen where the person picks the assistant. An app cannot make itself the assistant:
 * the role is not requestable (unlike the default dialer or SMS app), only a person can choose it there.
 * Falls back to the default apps list, then to Settings itself, since phone makers move these screens.
 */
fun openAssistantSettings(context: Context) {
  val candidates = listOf(
    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    Intent(Settings.ACTION_SETTINGS),
  )
  for (intent in candidates) {
    try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return
    } catch (_: ActivityNotFoundException) {
      // Try the next, more general screen.
    }
  }
}

/** Settings.Secure.VOICE_INTERACTION_SERVICE is a hidden constant, hence a string. */
private const val VOICE_INTERACTION_SERVICE_SETTING = "voice_interaction_service"
