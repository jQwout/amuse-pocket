package com.aiopenpocket.ui.call

// @trace voice-call-mode-54cb49

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.chat.components.LessonPhotoCapture
import com.aiopenpocket.ui.chat.components.ToolApprovalBar
import com.aiopenpocket.ui.chat.components.newPhotoCaptureUri
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.voice.call.CallPhase
import com.aiopenpocket.voice.call.CallSpeaker
import dev.zacsweers.metrox.viewmodel.metroViewModel
import java.io.File

/**
 * The call screen, built like a phone call: the voice orb, one line of words under it, and four round
 * buttons — photo, mute, repeat, hang up. The whole conversation is not on screen: it is in the chat once
 * the call ends, and a call is listened to, not read (owner, 4 Nov 2026: the first version, with a glass
 * transcript card, read like a chat with a microphone).
 *
 * The words under the orb are whatever matters now: what the person is saying while they speak, the
 * sentence being read word by word while the agent speaks, otherwise the last reply (a tap reads it
 * again). A tap anywhere else cuts the agent off. [onHangUp] gets the conversation id (null if nothing
 * was said), so the caller can open the chat with the whole call in it.
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

        Spacer(Modifier.weight(1f))
        VoiceOrb(phase = state.phase, paused = state.micPaused || state.micMuted || state.needsMicPermission)
        Spacer(Modifier.height(32.dp))

        CallWords(state, onReplay = viewModel::replay)

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
        Spacer(Modifier.weight(1f))

        if (state.photoPath != null || state.photoLoading) {
          PhotoChip(path = state.photoPath, loading = state.photoLoading, onRemove = viewModel::removePhoto)
          Spacer(Modifier.height(12.dp))
        }
        state.pendingApproval?.let { request ->
          ToolApprovalBar(
            request = request,
            onApprove = { viewModel.resolveApproval(approved = true) },
            onDeny = { viewModel.resolveApproval(approved = false) },
          )
          Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
          PhotoButton(onOpening = viewModel::onPhotoPickerOpening, onPicked = viewModel::onPhotoPicked)
          CallButton(
            icon = if (state.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = if (state.micMuted) tr("Включить", "Unmute") else tr("Без звука", "Mute"),
            onClick = viewModel::toggleMute,
            tag = "call_mute",
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
            onClick = hangUp,
            tag = "call_hang_up",
            size = 64.dp,
            container = HANG_UP_RED,
          )
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
  state.micMuted && state.phase == CallPhase.LISTENING -> tr("Микрофон выключен", "Microphone muted")
  state.micPaused -> tr("На паузе", "Paused")
  else -> when (state.phase) {
    CallPhase.LISTENING -> tr("Слушаю…", "Listening…")
    CallPhase.THINKING -> tr("Думаю…", "Thinking…")
    CallPhase.SPEAKING -> tr("Говорю", "Speaking")
    CallPhase.WAITING_APPROVAL -> tr("Жду твоего решения", "Waiting for your decision")
  }
}

/**
 * The words under the orb: the person's last phrase in small type, and in large type what matters now —
 * what they are saying, the sentence being read (said words bright, the rest dim), what the agent is
 * doing, or the last reply, which a tap reads again. Always three lines high, so the screen does not jump.
 */
@Composable
private fun CallWords(state: CallUiState, onReplay: () -> Unit) {
  val color = MaterialTheme.colorScheme.onBackground
  val dim = color.copy(alpha = 0.45f)
  val heard = state.heard
  val caption = state.caption.takeIf { state.phase == CallPhase.SPEAKING }
  val micOff = state.micPaused || state.micMuted || state.needsMicPermission
  val listeningLive = state.phase == CallPhase.LISTENING && !heard.isNullOrBlank()

  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
    Text(
      text = state.said?.takeUnless { listeningLive }?.let { tr("Ты: $it", "You: $it") } ?: " ",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(8.dp))
    val replayable = !listeningLive && caption == null && state.phase == CallPhase.LISTENING && state.reply.isNotBlank()
    val words: AnnotatedString = when {
      listeningLive -> AnnotatedString(heard.orEmpty())
      caption != null -> buildAnnotatedString {
        val said = if (caption.spokenTo == CallSpeaker.Caption.UNKNOWN) caption.text.length else caption.spokenTo
        withStyle(SpanStyle(color = color)) { append(caption.text.take(said)) }
        withStyle(SpanStyle(color = dim)) { append(caption.text.drop(said)) }
      }
      state.phase == CallPhase.THINKING -> AnnotatedString(state.activity ?: "…")
      replayable -> AnnotatedString(state.reply)
      micOff -> AnnotatedString(tr("Микрофон выключен", "The microphone is off"))
      else -> AnnotatedString(tr("Говори, я слушаю", "Go ahead, I'm listening"))
    }
    Text(
      text = words,
      style = MaterialTheme.typography.titleLarge,
      color = if (listeningLive || caption != null) color else dim,
      textAlign = TextAlign.Center,
      minLines = 3,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .fillMaxWidth()
        .then(if (replayable) Modifier.clickable(onClick = onReplay) else Modifier)
        .testTag("call_words"),
    )
    if (replayable) {
      Text(
        text = tr("Коснись, чтобы послушать ещё раз", "Tap to hear it again"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

/** A round call button with its label under it, like a phone's in-call buttons. */
@Composable
private fun CallButton(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  tag: String,
  size: Dp = 56.dp,
  enabled: Boolean = true,
  container: Color? = null,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    if (container != null) {
      FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size).testTag(tag),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = Color.White),
      ) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
    } else {
      FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size).testTag(tag),
      ) { Icon(icon, contentDescription = label) }
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
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
  var captureUri by remember { mutableStateOf<Uri?>(null) }
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
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
    modifier = Modifier.testTag("call_photo_chip"),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
      Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) {
        if (path != null) {
          AsyncImage(
            model = File(path),
            contentDescription = tr("Фото для агента", "Photo for the agent"),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else if (loading) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
      }
      Text(
        text = tr("Фото уйдёт с твоей следующей фразой", "The photo goes with your next phrase"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp),
      )
      IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Default.Close, contentDescription = tr("Убрать фото", "Remove the photo"), modifier = Modifier.size(18.dp))
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
