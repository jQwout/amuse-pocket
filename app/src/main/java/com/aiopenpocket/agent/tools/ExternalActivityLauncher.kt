package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aiopenpocket.agent.core.notification.showAgentNotification
import com.aiopenpocket.agent.loop.ForegroundState

/** Чем кончилась попытка открыть чужое приложение — см. [launchExternalActivity]. */
internal sealed interface LaunchOutcome {
  /** Приложение на экране — активити открыта сразу. */
  data object Opened : LaunchOutcome

  /** Приложение в фоне — показано уведомление, по тапу откроется то же самое. Действие ещё НЕ выполнено. */
  data object Notified : LaunchOutcome

  /** Приложение в фоне, а уведомление не показать (нет разрешения) — не случилось ничего. */
  data object NotNotified : LaunchOutcome

  /** На экране, но открыть не вышло (нет подходящего приложения и т.п.). */
  data class Failed(val reason: String?) : LaunchOutcome
}

/**
 * Открывает чужую активити (Часы, почту) из тула с поправкой на фон. Ход агента может закончиться,
 * когда пользователь уже ушёл из приложения (фоновый ход, heartbeat), а с Android 10 система молча
 * блокирует запуск активити из фона: startActivity не бросает, тул рапортовал «будильник открыт», а
 * на экране не появлялось ничего. Поэтому вне экрана — уведомление с тем же интентом: тап по нему —
 * разрешённый путь запуска. Вызывающий тул обязан честно сказать модели, что действие ждёт тапа.
 */
internal fun launchExternalActivity(
  context: Context,
  foreground: ForegroundState,
  intent: Intent,
  notificationTitle: String,
  notificationText: String,
): LaunchOutcome {
  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  if (foreground.isInForeground()) {
    return try {
      context.startActivity(intent)
      LaunchOutcome.Opened
    } catch (e: Exception) {
      LaunchOutcome.Failed(e.message)
    }
  }
  // Один и тот же код и для PendingIntent, и для id уведомления: повтор того же действия заменяет
  // прежнее уведомление, а разные действия (будильник на 7:00 и на 8:00) не затирают друг друга.
  val requestCode = intent.toUri(0).hashCode()
  val contentIntent = PendingIntent.getActivity(
    context,
    requestCode,
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
  val shown = showAgentNotification(context, notificationTitle, notificationText, requestCode, contentIntent)
  return if (shown) LaunchOutcome.Notified else LaunchOutcome.NotNotified
}
