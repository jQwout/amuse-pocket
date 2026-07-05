package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.aiopenpocket.MainActivity
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Пуш «агент ответил/ход упал», когда приложение в фоне. Тап ведёт в тот самый диалог.
 * notificationId = hashCode диалога: новое уведомление того же чата заменяет старое.
 * Без разрешения POST_NOTIFICATIONS showAgentNotification молча вернёт false — ответ всё
 * равно сохранён в истории.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AgentTurnNotifier(private val application: Application) : TurnNotifier {

  override fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean) {
    val intent = Intent(application, MainActivity::class.java).apply {
      action = MainActivity.ACTION_OPEN_CONVERSATION
      putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentIntent = PendingIntent.getActivity(
      application,
      conversationId.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    showAgentNotification(
      context = application,
      title = if (failed) "Ход не удался" else (conversationTitle ?: "Агент ответил"),
      message = preview,
      notificationId = conversationId.hashCode(),
      contentIntent = contentIntent,
    )
  }
}
