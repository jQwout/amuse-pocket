package com.aiopenpocket.ui.call

// @trace voice-call-mode-54cb49
// @trace voice-assistant-mode-a81a99
// @trace agent-router-dc2233

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.aiopenpocket.voice.call.CallBackground
import com.aiopenpocket.voice.call.CallControl
import com.aiopenpocket.voice.call.CallForegroundService
import com.aiopenpocket.voice.call.CallStatus
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.assistant.AssistantOrb
import com.aiopenpocket.ui.assistant.OrbState
import com.aiopenpocket.ui.chat.components.LessonPhotoCapture
import com.aiopenpocket.ui.chat.components.ToolApprovalBar
import com.aiopenpocket.ui.chat.components.newPhotoCaptureUri
import com.aiopenpocket.voice.call.CallPhase
import com.aiopenpocket.voice.call.CallSpeaker
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TextButton
import androidx.compose.ui.BiasAlignment
import com.aiopenpocket.agent.core.agent.RouteChoice
import com.aiopenpocket.ui.chat.RouteAgent
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle

/**
 * A voice conversation with an agent: the call screen in the app and the phone's assistant over other apps
 * are this one screen on one engine ([CallViewModel]); they used to be two (the assistant ran on the chat's
 * engine: Google hearing, the reply read only once finished, one question per tap), and the owner found
 * them behaving "very differently" (4 Nov 2026).
 *
 * Built like a phone call: who you talk to, the call time and what is happening at the top; the app's
 * lime-on-ink orb (AssistantOrb, from the owner's earlier assistant); one line of large words under it; four
 * round buttons — photo, mute, repeat, end. The orb waits low with the router chips under it and glides up
 * once the first phrase is said, the motion the owner liked in the assistant. The whole conversation is in
 * the chat once the session ends: a call is listened to, not read.
 *
 * The words are whatever matters now: what the person is saying, the sentence being read word by word, or
 * the last reply (a tap reads it again). A tap anywhere else cuts the agent off. Colours follow the theme.
 * [overlay] is the assistant's window: a dimmed backdrop over the app underneath (its activity forces the
 * dark theme), a close button and "Open in chat" ([onOpenChat]). In the app the backdrop is the page and
 * runs up under the status bar, which is why MainActivity leaves out its status bar scrim on this route.
 * [onEnd] gets the conversation id (null if nothing was said).
 */
@Composable
fun VoiceSessionScreen(
  conversationId: String,
  onEnd: (String?) -> Unit,
  overlay: Boolean = false,
  onOpenChat: ((String?) -> Unit)? = null,
) {
  val viewModel: CallViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current

  // @trace background-voice-calls-a-picture-in-picture-window-and-a-call-that-goes-on-off-screen-3ab748
  // A call in the app goes on off screen - in a picture-in-picture window, behind another app, with the
  // screen locked (owner, Nov 7, 2026) - but only when its ongoing notification can be seen: that is how the
  // person sees and stops a microphone they cannot see. With notifications off (or the call channel muted)
  // it keeps quiet off screen as before. The assistant's window is a moment: off screen it stops hearing and
  // speaking, and leaving it ends it.
  val goesOnOffScreen = remember(overlay) { !overlay && CallForegroundService.canShowNotification(context) }

  // The service must start while the screen is in front (Android allows it only then): with the permission.
  val onMicGranted = { granted: Boolean ->
    CallBackground.setHoldPip(false)
    viewModel.onMicPermission(granted)
    if (granted && goesOnOffScreen) CallForegroundService.start(context)
  }
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    onMicGranted(granted)
  }
  // The system's permission dialog is not the person leaving: the call must not shrink under it.
  val askMic = {
    CallBackground.setHoldPip(true)
    micPermission.launch(Manifest.permission.RECORD_AUDIO)
  }
  LaunchedEffect(conversationId) {
    viewModel.start(conversationId)
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    if (granted) onMicGranted(true) else askMic()
  }
  val inPip by CallBackground.inPip.collectAsState()

  // A call does not let the screen go dark under the person's ear, as a phone call doesn't - but a mini
  // window over another app is not at the ear, and must not hold the screen on.
  val view = LocalView.current
  DisposableEffect(view, inPip) {
    view.keepScreenOn = !inPip
    onDispose { view.keepScreenOn = false }
  }

  val end = { onEnd(viewModel.hangUp()) }
  BackHandler(onBack = end)

  val lifecycleOwner = LocalLifecycleOwner.current
  val currentEnd by rememberUpdatedState(end)
  DisposableEffect(lifecycleOwner, overlay, goesOnOffScreen) {
    val observer = LifecycleEventObserver { _, event ->
      if (!goesOnOffScreen) {
        when (event) {
          Lifecycle.Event.ON_PAUSE -> viewModel.onScreenHidden()
          Lifecycle.Event.ON_RESUME -> viewModel.onScreenShown()
          Lifecycle.Event.ON_STOP -> if (overlay && !viewModel.isPickingPhoto) currentEnd()
          else -> Unit
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  DisposableEffect(overlay, goesOnOffScreen) {
    when {
      overlay -> CallBackground.setOverlayOpen(true)
      goesOnOffScreen -> CallBackground.setOnCall(true)
    }
    onDispose {
      if (overlay) CallBackground.setOverlayOpen(false) else CallForegroundService.leaveBackground(context)
    }
  }
  if (goesOnOffScreen) {
    // Everything below is a plain collect, not a recomposition: while the app is not in front Compose does not
    // recompose, and the call must still go quiet, show its state and hang up from the notification.
    LaunchedEffect(Unit) {
      // The assistant's own window listens: two recognizers on one microphone hear nothing.
      CallBackground.overlayOpen.collect { open -> if (open) viewModel.onScreenHidden() else viewModel.onScreenShown() }
    }
    LaunchedEffect(Unit) {
      viewModel.state
        .map { CallStatus(title = callTitle(it), phase = phaseLabel(it), muted = it.micMuted, paused = it.micPaused) }
        .distinctUntilChanged()
        .collect(CallBackground::setStatus)
    }
    LaunchedEffect(Unit) {
      CallBackground.controls.collect { control ->
        when (control) {
          // Mute, or wake a microphone that gave up after a long silence.
          CallControl.MUTE -> if (viewModel.state.value.micPaused) viewModel.tap() else viewModel.toggleMute()
          // The notification goes now: the screen's own dispose waits for the app to be opened again.
          CallControl.HANG_UP -> {
            CallForegroundService.leaveBackground(context)
            currentEnd()
          }
        }
      }
    }
  }

  // The orb waits low, then glides up once something was said: the assistant's motion, kept for both.
  val started = state.said != null
  val orbBias by animateFloatAsState(
    targetValue = if (started) -0.9f else 0.25f,
    animationSpec = tween(600, easing = FastOutSlowInEasing),
    label = "orbBias",
  )
  val orbSize by animateDpAsState(
    targetValue = if (started) 200.dp else 260.dp,
    animationSpec = tween(600, easing = FastOutSlowInEasing),
    label = "orbSize",
  )
  if (!overlay && inPip) {
    CallPipContent(state)
    return
  }

  val background = MaterialTheme.colorScheme.background
  val backdrop = if (overlay) {
    // The app underneath stays visible through the top, as in the assistant before.
    Brush.verticalGradient(0f to background.copy(alpha = 0.55f), 0.45f to background.copy(alpha = 0.9f), 1f to background)
  } else {
    Brush.verticalGradient(0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.16f).compositeOver(background), 0.6f to background)
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(backdrop)
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewModel.tap() }
      .testTag("call_screen"),
  ) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
      if (overlay) {
        IconButton(
          onClick = end,
          modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
        ) { Icon(Icons.Default.Close, contentDescription = tr("Закрыть", "Close")) }
      }
      Column(
        modifier = Modifier
          .fillMaxSize()
          .systemBarsPadding()
          .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Like a phone call: who you are talking to, big, and under it how long and what is happening.
        Text(
          text = state.agentName ?: state.routedAgentName ?: tr("Ассистент", "Assistant"),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 12.dp, start = 40.dp, end = 40.dp).testTag("call_title"),
        )
        Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          CallTimer(startedAt = state.startedAt, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
          Text(" · ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
          Text(
            text = phaseLabel(state),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("call_phase"),
          )
        }
        // Whose model hears the person: shown when it is Meta's, the point of a Meta-built call.
        state.hearingModel?.let { model ->
          Text(
            text = tr("Слышит: $model", "Listening with $model"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 2.dp).testTag("call_hearing_model"),
          )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(BiasAlignment(0f, orbBias)),
          ) {
            AssistantOrb(state = orbState(state), size = orbSize)
            CallWords(state, onReplay = viewModel::replay)
            // Who answers: the router (Auto) or one agent, the same choice as the chip in a chat.
            AnimatedVisibility(visible = !started && state.canRoute && state.routeAgents.isNotEmpty()) {
              RouteChips(
                agents = state.routeAgents,
                choice = state.routeChoice,
                onChoose = viewModel::setRoute,
                modifier = Modifier.padding(top = 12.dp),
              )
            }
            state.notice?.let {
              Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp).testTag("call_notice"),
              )
            }
            if (state.needsMicPermission) {
              Button(
                onClick = askMic,
                modifier = Modifier.padding(top = 8.dp),
              ) { Text(tr("Разрешить микрофон", "Allow the microphone")) }
            }
          }
        }

        if (state.photoPath != null || state.photoLoading) {
          PhotoChip(path = state.photoPath, loading = state.photoLoading, onRemove = viewModel::removePhoto)
          Spacer(Modifier.height(16.dp))
        }
        state.pendingApproval?.let { request ->
          ToolApprovalBar(
            request = request,
            onApprove = { viewModel.resolveApproval(request, approved = true) },
            onDeny = { viewModel.resolveApproval(request, approved = false) },
          )
          Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
          PhotoButton(
            // The camera or the gallery is part of the call: it must not shrink into the mini window.
            onOpening = {
              CallBackground.setHoldPip(true)
              viewModel.onPhotoPickerOpening()
            },
            onPicked = { uri ->
              CallBackground.setHoldPip(false)
              viewModel.onPhotoPicked(uri)
            },
          )
          CallButton(
            icon = if (state.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = if (state.micMuted) tr("Включить", "Unmute") else tr("Без звука", "Mute"),
            onClick = viewModel::toggleMute,
            tag = "call_mute",
            toggled = state.micMuted,
          )
          CallButton(
            icon = Icons.Default.Replay,
            label = tr("Повтор", "Repeat"),
            onClick = viewModel::replay,
            tag = "call_replay",
            enabled = state.reply.isNotBlank(),
          )
          CallButton(
            icon = Icons.Default.CallEnd,
            label = tr("Завершить", "End"),
            onClick = end,
            tag = "call_hang_up",
            size = 64.dp,
            container = HangUpRed,
          )
        }
        if (onOpenChat != null) {
          TextButton(
            onClick = { onOpenChat(viewModel.hangUp()) },
            modifier = Modifier.padding(top = 4.dp).testTag("call_open_chat"),
          ) { Text(tr("Открыть в чате", "Open in chat")) }
        } else {
          Text(
            text = if (state.phase == CallPhase.SPEAKING || state.phase == CallPhase.THINKING) {
              tr("Коснись экрана, чтобы перебить", "Tap the screen to interrupt")
            } else {
              " "
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 10.dp),
          )
        }
      }
    }
  }
}

/** The call screen in the app: [VoiceSessionScreen] on the page; [onHangUp] opens the chat the call wrote into. */
@Composable
fun CallScreen(
  conversationId: String,
  onHangUp: (String?) -> Unit,
) = VoiceSessionScreen(conversationId = conversationId, onEnd = onHangUp)

/** The router chips: Auto, then each of the person's agents; the picked one is filled. */
@Composable
private fun RouteChips(
  agents: List<RouteAgent>,
  choice: RouteChoice,
  onChoose: (RouteChoice) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier.horizontalScroll(rememberScrollState()),
  ) {
    RouteChip(tr("Авто", "Auto"), choice == RouteChoice.Auto) { onChoose(RouteChoice.Auto) }
    agents.forEach { agent ->
      val picked = choice == RouteChoice.Fixed(agent.id)
      RouteChip(agent.name, picked) { onChoose(if (picked) RouteChoice.Auto else RouteChoice.Fixed(agent.id)) }
    }
  }
}

@Composable
private fun RouteChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(50)
  val colors = MaterialTheme.colorScheme
  Text(
    text = label,
    color = if (selected) colors.onPrimary else colors.onBackground,
    style = MaterialTheme.typography.labelLarge,
    maxLines = 1,
    modifier = Modifier
      .clip(shape)
      .background(if (selected) colors.primary else colors.onBackground.copy(alpha = 0.08f))
      .border(1.dp, if (selected) colors.primary else colors.onBackground.copy(alpha = 0.2f), shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

/** The call's length, m:ss, ticking once a second like a phone call's. */
@Composable
private fun CallTimer(startedAt: Long, color: Color) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(startedAt) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  val seconds = ((now - startedAt) / 1_000).coerceAtLeast(0)
  Text(
    text = "%d:%02d".format(seconds / 60, seconds % 60),
    style = MaterialTheme.typography.labelLarge,
    color = color,
    modifier = Modifier.testTag("call_timer"),
  )
}

private fun orbState(state: CallUiState): OrbState = when {
  state.needsMicPermission || state.micPaused -> OrbState.IDLE
  else -> when (state.phase) {
    CallPhase.SPEAKING -> OrbState.SPEAKING
    CallPhase.THINKING, CallPhase.WAITING_APPROVAL -> OrbState.THINKING
    CallPhase.LISTENING -> when {
      state.transcribing -> OrbState.THINKING
      state.micMuted -> OrbState.IDLE
      else -> OrbState.LISTENING
    }
  }
}

/**
 * The call in a picture-in-picture window: the orb, who you talk to, what is happening and the last words.
 * No buttons of its own: the window carries mute and hang-up (MainActivity). A tap shows the system's window
 * controls, and their expand button (or a double tap) brings the full call back.
 */
@Composable
private fun CallPipContent(state: CallUiState) {
  val words = state.heard ?: state.caption?.text ?: state.reply.takeIf { it.isNotBlank() }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .testTag("call_pip"),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
      AssistantOrb(state = orbState(state), size = 64.dp)
      Text(
        text = callTitle(state),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
      )
      Text(
        text = phaseLabel(state),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
      )
      words?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
          textAlign = TextAlign.Center,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

/** Who the call is with, as its title, the notification's and the mini window's. */
private fun callTitle(state: CallUiState): String = state.agentName ?: state.routedAgentName ?: tr("Ассистент", "Assistant")

private fun phaseLabel(state: CallUiState): String = when {
  state.needsMicPermission -> tr("Нет доступа к микрофону", "No microphone access")
  state.transcribing -> tr("Расшифровываю…", "Transcribing…")
  state.micMuted && state.phase == CallPhase.LISTENING -> tr("Микрофон выключен", "Microphone muted")
  state.micPaused -> tr("На паузе", "Paused")
  else -> when (state.phase) {
    CallPhase.LISTENING -> tr("Слушаю…", "Listening…")
    CallPhase.THINKING -> tr("Думаю…", "Thinking…")
    CallPhase.SPEAKING -> tr("Говорю", "Speaking")
    CallPhase.WAITING_APPROVAL -> tr("Жду твоего решения", "Waiting for your decision")
  }
}

/** Which kind of words are under the orb; a change of kind crossfades, the words inside update live. */
private enum class WordsKind { HEARING, SPEAKING, THINKING, REPLY, HINT }

/**
 * The words under the orb: the person's last phrase in small italics, and in large type what matters now —
 * what they are saying, the sentence being read (said words bright, the rest dim), what the agent is doing,
 * or the last reply, which a tap reads again. A fixed height, so the orb above does not jump.
 */
@Composable
private fun CallWords(state: CallUiState, onReplay: () -> Unit) {
  val heard = state.heard
  val caption = state.caption.takeIf { state.phase == CallPhase.SPEAKING }
  val kind = when {
    state.transcribing -> WordsKind.THINKING
    state.phase == CallPhase.LISTENING && !heard.isNullOrBlank() -> WordsKind.HEARING
    caption != null -> WordsKind.SPEAKING
    state.phase == CallPhase.THINKING -> WordsKind.THINKING
    state.phase == CallPhase.LISTENING && state.reply.isNotBlank() -> WordsKind.REPLY
    else -> WordsKind.HINT
  }

  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
    Text(
      text = state.said?.takeUnless { kind == WordsKind.HEARING } ?: " ",
      fontSize = 15.sp,
      fontStyle = FontStyle.Italic,
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(10.dp))
    Crossfade(targetState = kind, animationSpec = tween(250), label = "callWords") { shown ->
      val micOff = state.micPaused || state.micMuted || state.needsMicPermission
      val words: AnnotatedString = when (shown) {
        WordsKind.HEARING -> AnnotatedString(heard.orEmpty())
        WordsKind.SPEAKING -> buildAnnotatedString {
          val text = caption?.text.orEmpty()
          val said = caption?.spokenTo?.takeUnless { it == CallSpeaker.Caption.UNKNOWN } ?: text.length
          withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append(text.take(said)) }
          withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))) { append(text.drop(said)) }
        }
        WordsKind.THINKING -> AnnotatedString(
          if (state.transcribing) tr("Записываю твою фразу…", "Writing down what you said…") else state.activity ?: "",
        )
        WordsKind.REPLY -> AnnotatedString(state.reply)
        WordsKind.HINT -> AnnotatedString(
          if (micOff) tr("Микрофон выключен", "The microphone is off") else tr("Говори, я слушаю", "Go ahead, I'm listening"),
        )
      }
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = WORDS_HEIGHT)
          .then(if (shown == WordsKind.REPLY) Modifier.clickable(onClick = onReplay) else Modifier),
      ) {
        Text(
          text = words,
          fontSize = 24.sp,
          lineHeight = 31.sp,
          fontWeight = FontWeight.SemiBold,
          color = when (shown) {
            WordsKind.HEARING, WordsKind.SPEAKING -> MaterialTheme.colorScheme.onBackground
            WordsKind.REPLY -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
          },
          textAlign = TextAlign.Center,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth().testTag("call_words"),
        )
        if (shown == WordsKind.REPLY) {
          Text(
            text = tr("Коснись, чтобы послушать ещё раз", "Tap to hear it again"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
      }
    }
  }
}

/**
 * A round call button with its label under it, like a phone's in-call buttons: frosted white on the ink,
 * solid white when [toggled] (mute on), or the [container] colour (hang up).
 */
@Composable
private fun CallButton(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  tag: String,
  size: Dp = 58.dp,
  enabled: Boolean = true,
  toggled: Boolean = false,
  container: Color? = null,
) {
  val onBg = MaterialTheme.colorScheme.onBackground
  val background = container ?: if (toggled) onBg else onBg.copy(alpha = 0.1f)
  val tint = when {
    container != null -> Color.White
    toggled -> MaterialTheme.colorScheme.background
    else -> MaterialTheme.colorScheme.onBackground
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.35f)) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(size)
        .clip(CircleShape)
        .background(background)
        .clickable(enabled = enabled, onClick = onClick)
        .testTag(tag),
    ) {
      Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (container != null) 30.dp else 26.dp))
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

/**
 * The photo button: camera or gallery, as in the chat's composer. The call stops listening while either is
 * open ([onOpening]); [onPicked] gets the photo, or null when the person backed out.
 */
@Composable
private fun PhotoButton(onOpening: () -> Unit, onPicked: (Uri?) -> Unit) {
  val context = LocalContext.current
  var menuOpen by remember { mutableStateOf(false) }
  // Saveable: the camera app can outlive a rotation or this process, and the photo must still come back.
  var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
  val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onPicked(uri) }
  val camera = rememberLauncherForActivityResult(LessonPhotoCapture()) { saved ->
    onPicked(captureUri?.takeIf { saved })
    captureUri = null
  }
  Box {
    CallButton(icon = Icons.Default.PhotoCamera, label = tr("Фото", "Photo"), onClick = { menuOpen = true }, tag = "call_photo")
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
      DropdownMenuItem(
        text = { Text(tr("Сфотографировать", "Take a photo")) },
        leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
        onClick = {
          menuOpen = false
          val uri = newPhotoCaptureUri(context)
          captureUri = uri
          onOpening()
          // No camera app at all is rare, but launching into nothing must not crash the call.
          runCatching { camera.launch(uri) }.onFailure {
            captureUri = null
            onPicked(null)
          }
        },
      )
      DropdownMenuItem(
        text = { Text(tr("Из галереи", "From gallery")) },
        leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
        onClick = {
          menuOpen = false
          onOpening()
          gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
      )
    }
  }
}

/** The photo waiting for the next phrase: a thumbnail, what will happen to it, and an "x". */
@Composable
private fun PhotoChip(path: String?, loading: Boolean, onRemove: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .clip(RoundedCornerShape(16.dp))
      .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
      .padding(6.dp)
      .testTag("call_photo_chip"),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
      if (path != null) {
        AsyncImage(
          model = File(path),
          contentDescription = tr("Фото для агента", "Photo for the agent"),
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      } else if (loading) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
      }
    }
    Text(
      text = tr("Фото уйдёт с твоей следующей фразой", "The photo goes with your next phrase"),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
      modifier = Modifier.padding(horizontal = 10.dp),
    )
    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
      Icon(Icons.Default.Close, contentDescription = tr("Убрать фото", "Remove the photo"), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
    }
  }
}

private val HangUpRed = Color(0xFFD93025)

/** Three lines of the large words plus the "tap to hear it again" line. */
private val WORDS_HEIGHT = 120.dp
