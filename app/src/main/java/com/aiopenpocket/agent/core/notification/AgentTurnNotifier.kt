package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.aiopenpocket.MainActivity
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The "the agent replied / the turn failed" push when the app is in the background. A tap leads to that
 * very conversation. notificationId = the conversation's hashCode: a new notification of the same chat
 * replaces the old one. Without the POST_NOTIFICATIONS permission showAgentNotification silently
 * returns false — the answer is saved in the history anyway.
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
      title = if (failed) tr("Ход не удался", "The turn failed") else (conversationTitle ?: tr("Агент ответил", "The agent replied")),
      message = preview,
      notificationId = conversationId.hashCode(),
      contentIntent = contentIntent,
    )
  }
}
