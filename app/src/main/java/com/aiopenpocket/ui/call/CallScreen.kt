package com.aiopenpocket.ui.call

// @trace voice-call-mode-54cb49

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.components.ToolApprovalBar
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.voice.call.CallPhase
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The call screen: one big voice indicator, what was heard and what the agent says, a red button to
 * hang up. A tap anywhere cuts the agent off and gives the floor back to the person. [onHangUp] gets the
 * conversation id (null if nothing was said), so the caller can open the chat with the whole call in it.
 */
@Composable
fun CallScreen(
  conversationId: String,
  onHangUp: (String?) -> Unit,
) {
  val viewModel: CallViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  val context = LocalContext.current

  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    viewModel.onMicPermission(granted)
  }
  LaunchedEffect(conversationId) {
    viewModel.start(conversationId)
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    if (granted) viewModel.onMicPermission(true) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
  }

  // A call does not let the screen go dark under the person's ear, as a phone call doesn't.
  val view = LocalView.current
  DisposableEffect(view) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }

  val hangUp = { onHangUp(viewModel.hangUp()) }
  BackHandler(onBack = hangUp)

  Box(
    modifier = Modifier
      .fillMaxSize()
      .auroraBackground()
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewModel.tap() }
      .testTag("call_screen"),
  ) {
    // A Box is not a Surface and sets no content colour: without this the title fell back to Compose's
    // default black, unreadable on the dark aurora (emulator, 3 Nov 2026).
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .systemBarsPadding()
          .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = state.title ?: tr("Звонок с ассистентом", "Call with the assistant"),
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = phaseLabel(state),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(top = 4.dp).testTag("call_phase"),
        )

        Spacer(Modifier.weight(0.6f))
        VoiceOrb(phase = state.phase, paused = state.micPaused || state.needsMicPermission)
        Spacer(Modifier.weight(0.4f))

        CallTranscript(state)

        state.activity?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
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
            onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = Modifier.padding(top = 8.dp),
          ) { Text(tr("Разрешить микрофон", "Allow the microphone")) }
        }

        state.pendingApproval?.let { request ->
          ToolApprovalBar(
            request = request,
            onApprove = { viewModel.resolveApproval(approved = true) },
            onDeny = { viewModel.resolveApproval(approved = false) },
          )
        }

        Spacer(Modifier.height(24.dp))
        FilledIconButton(
          onClick = hangUp,
          modifier = Modifier.size(72.dp).testTag("call_hang_up"),
          colors = IconButtonDefaults.filledIconButtonColors(containerColor = HANG_UP_RED, contentColor = Color.White),
        ) {
          Icon(Icons.Default.CallEnd, contentDescription = tr("Завершить звонок", "Hang up"), modifier = Modifier.size(32.dp))
        }
        Text(
          text = if (state.phase == CallPhase.SPEAKING || state.phase == CallPhase.THINKING) {
            tr("Коснись экрана, чтобы перебить", "Tap the screen to interrupt")
          } else {
            " "
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    }
  }
}

private fun phaseLabel(state: CallUiState): String = when {
  state.needsMicPermission -> tr("Нет доступа к микрофону", "No microphone access")
  state.micPaused -> tr("На паузе", "Paused")
  else -> when (state.phase) {
    CallPhase.LISTENING -> tr("Слушаю…", "Listening…")
    CallPhase.THINKING -> tr("Думаю…", "Thinking…")
    CallPhase.SPEAKING -> tr("Говорю", "Speaking")
    CallPhase.WAITING_APPROVAL -> tr("Жду твоего решения", "Waiting for your decision")
  }
}

/** The person's words while they speak; otherwise their last phrase and the agent's reply under it. */
@Composable
private fun CallTranscript(state: CallUiState) {
  GlassCard(modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 260.dp)) {
    Column(
      modifier = Modifier
        .padding(16.dp)
        .verticalScroll(rememberScrollState(), reverseScrolling = true),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val heard = state.heard
      // "Listening" only when the microphone really is on: under "No microphone access" it contradicted the label.
      val micOff = state.micPaused || state.needsMicPermission
      when {
        state.phase == CallPhase.LISTENING && !heard.isNullOrBlank() ->
          Text(heard, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("call_heard"))
        state.phase == CallPhase.LISTENING && state.reply.isBlank() ->
          Text(
            if (micOff) tr("Микрофон выключен", "The microphone is off") else tr("Говори, я слушаю", "Go ahead, I'm listening"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        else -> {
          state.said?.let {
            Text(
              it,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (state.reply.isNotBlank()) {
            Text(state.reply, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("call_reply"))
          }
        }
      }
    }
  }
}

/**
 * The voice indicator: it breathes slowly while listening, pulses while the agent speaks, dims while it
 * thinks. Colour and motion only, no waveform: the platform recognizer does not hand out the audio.
 */
@Composable
private fun VoiceOrb(phase: CallPhase, paused: Boolean) {
  val transition = rememberInfiniteTransition(label = "orb")
  val period = when (phase) {
    CallPhase.SPEAKING -> 420
    CallPhase.LISTENING -> 1400
    else -> 2200
  }
  val pulse by transition.animateFloat(
    initialValue = 0.92f,
    targetValue = if (paused) 0.92f else 1.08f,
    animationSpec = infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "pulse",
  )
  val accent = MaterialTheme.colorScheme.primary
  Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
    Box(
      modifier = Modifier
        .size(200.dp)
        .scale(pulse)
        .alpha(if (phase == CallPhase.THINKING || paused) 0.25f else 0.35f)
        .background(accent, CircleShape),
    )
    Box(
      modifier = Modifier
        .size(120.dp)
        .background(accent.copy(alpha = if (paused) 0.4f else 1f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = if (paused) Icons.Default.MicOff else Icons.Default.Mic,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(48.dp),
      )
    }
  }
}

private val HANG_UP_RED = Color(0xFFD93025)
