package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aiopenpocket.agent.core.mcp.ToolResult

/** Проверяет, выдано ли runtime-разрешение. */
internal fun Context.hasPermission(permission: String): Boolean =
  ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Возвращает [ToolResult.Error] с понятным сообщением, если разрешение не выдано,
 * иначе null. Инструменты исполняются в фоне и не могут сами показать системный
 * диалог — разрешение выдаётся пользователем заранее в Настройках → Интеграции.
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
