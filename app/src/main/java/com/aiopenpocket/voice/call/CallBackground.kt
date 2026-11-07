package com.aiopenpocket.voice.call

// @trace background-voice-calls-a-picture-in-picture-window-and-a-call-that-goes-on-off-screen-3ab748

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aiopenpocket.MainActivity
import com.aiopenpocket.R
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** A button outside the call screen: the notification's or the picture-in-picture window's. */
enum class CallControl { MUTE, HANG_UP }

/** What the notification and the mini window say about the call now. */
data class CallStatus(
  val title: String = "",
  val phase: String = "",
  val muted: Boolean = false,
  /** The microphone gave up after a long silence: the mute button wakes it instead. */
  val paused: Boolean = false,
)

/**
 * The call as the rest of the app sees it, for the owner's "a call like picture-in-picture on Windows" (Nov 7,
 * 2026): leaving the call screen shrinks it into a mini window over other apps, and closing that window or
 * locking the screen does not end it - the call goes on listening and speaking, with an ongoing notification
 * that can mute or hang up.
 *
 * Plain flows in an object, not the DI graph: the system creates the service, the receiver and the activity,
 * and all three only need to read or nudge one call at a time.
 */
object CallBackground {
  private val _onCall = MutableStateFlow(false)

  /** A call screen in the app is open (not the assistant's window, which ends when it is left). */
  val onCall: StateFlow<Boolean> = _onCall.asStateFlow()

  private val _inPip = MutableStateFlow(false)

  /** MainActivity is in picture-in-picture mode: the call draws its mini layout. */
  val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

  private val _overlayOpen = MutableStateFlow(false)

  /**
   * The assistant's own voice window is open over the app. It listens itself, so a call going on behind it
   * keeps quiet meanwhile - two recognizers on one microphone hear nothing.
   */
  val overlayOpen: StateFlow<Boolean> = _overlayOpen.asStateFlow()

  private val _status = MutableStateFlow(CallStatus())
  val status: StateFlow<CallStatus> = _status.asStateFlow()

  private val _controls = MutableSharedFlow<CallControl>(extraBufferCapacity = 4)

  /** Taps on the notification's and the mini window's buttons; the call screen acts on them. */
  val controls: SharedFlow<CallControl> = _controls.asSharedFlow()

  fun setOnCall(on: Boolean) {
    _onCall.value = on
  }

  fun setInPip(inPip: Boolean) {
    _inPip.value = inPip
  }

  fun setOverlayOpen(open: Boolean) {
    _overlayOpen.value = open
  }

  fun setStatus(status: CallStatus) {
    _status.value = status
  }

  fun send(control: CallControl) {
    _controls.tryEmit(control)
  }

  /** The intent a notification or mini-window button fires; explicit, so only this app receives it. */
  fun controlIntent(context: Context, control: CallControl): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      control.ordinal,
      Intent(context, CallControlReceiver::class.java).setAction(ACTION_PREFIX + control.name),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  internal const val ACTION_PREFIX = "com.aiopenpocket.call."
}

/** Turns a button tap from outside the app's window into a [CallControl] for the call screen. */
class CallControlReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val name = intent.action?.removePrefix(CallBackground.ACTION_PREFIX) ?: return
    val control = CallControl.entries.firstOrNull { it.name == name } ?: return
    CallBackground.send(control)
  }
}

/**
 * Keeps the microphone allowed while the call is off screen. Android lets an app hear only while it is in
 * front, unless a foreground service of type `microphone` runs - and that one must be started while the app
 * is in front, so the call screen starts it as soon as the microphone is granted and stops it when the call
 * ends. Its notification is the person's way to see and stop a call they cannot see.
 */
class CallForegroundService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watching = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      notification(CallBackground.status.value),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
    )
    if (!watching) {
      watching = true
      val manager = getSystemService(NotificationManager::class.java)
      scope.launch {
        CallBackground.status.drop(1).collect { manager.notify(NOTIFICATION_ID, notification(it)) }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun notification(status: CallStatus): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        // Low: the call is already speaking, the notification must not ring over it.
        NotificationChannel(CHANNEL_ID, tr("Звонок", "Call"), NotificationManager.IMPORTANCE_LOW),
      )
    }
    val open = PendingIntent.getActivity(
      this,
      NOTIFICATION_ID,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val muteLabel = when {
      status.paused -> tr("Слушать", "Listen")
      status.muted -> tr("Включить микрофон", "Unmute")
      else -> tr("Без звука", "Mute")
    }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_agent)
      .setContentTitle(status.title.ifBlank { tr("Звонок", "Call") })
      .setContentText(status.phase)
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .setContentIntent(open)
      .addAction(R.drawable.ic_call_mic_off, muteLabel, CallBackground.controlIntent(this, CallControl.MUTE))
      .addAction(R.drawable.ic_call_end, tr("Завершить", "Hang up"), CallBackground.controlIntent(this, CallControl.HANG_UP))
      // Shown at once: the person has just left the screen and must see that the call still hears them.
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  companion object {
    private const val TAG = "CallForeground"
    private const val CHANNEL_ID = "call_channel"
    private const val NOTIFICATION_ID = 4343

    /** Starts it; quietly does nothing when Android refuses (the app already in the background). */
    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java))
      } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) or a SecurityException without the permission:
        // the call still works on screen, it just stops hearing off it.
        Log.w(TAG, "Could not keep the call going off screen: ${e.message}")
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, CallForegroundService::class.java))
    }
  }
}
