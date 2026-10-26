package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aiopenpocket.agent.core.mcp.ToolResult

/** Checks whether a runtime permission is granted. */
internal fun Context.hasPermission(permission: String): Boolean =
  ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Returns a [ToolResult.Error] with a clear message if the permission is not granted, null otherwise.
 * Tools run in the background and cannot show the system dialog themselves — the user grants the
 * permission in advance in Settings → Integrations.
 */
internal fun Context.requirePermission(permission: String, humanName: String): ToolResult.Error? =
  if (hasPermission(permission)) {
    null
  } else {
    ToolResult.Error(
      "Нет разрешения на $humanName. Выдай его в Настройках → Интеграции.",
      recoverable = false,
    )
  }
