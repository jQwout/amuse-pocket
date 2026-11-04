package com.aiopenpocket.ui.call

// @trace voice-call-mode-54cb49

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * The call screen, built like a phone call: who you talk to and the call time at the top, the app's
 * lime-on-ink orb (AssistantOrb, ported from the owner's earlier assistant), one line of large words under
 * it, and four round buttons — photo, mute, repeat, hang up. The whole conversation is not on
 * screen: it is in the chat once the call ends, and a call is listened to, not read (owner, 4 Nov 2026: the
 * first version, a flat circle over a glass transcript card, "looked like nothing", and their earlier app
 * did it far better).
 *
 * The words under the orb are whatever matters now: what the person is saying while they speak, the
 * sentence being read word by word while the agent speaks, otherwise the last reply (a tap reads it
 * again). A tap anywhere else cuts the agent off. Colours follow the app theme, light or dark (an always-dark
 * call broke the theme, owner, 4 Nov 2026), and the backdrop runs up under the status bar, which is why
 * MainActivity leaves out its status bar scrim on this route. [onHangUp] gets the conversation id (null if
 * nothing was said), so the caller can open the chat with the whole call in it.
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
      .background(
        Brush.verticalGradient(
          0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.background),
          0.6f to MaterialTheme.colorScheme.background,
        ),
      )
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { viewModel.tap() }
      .testTag("call_screen"),
  ) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .systemBarsPadding()
          .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Like a phone call: who you are talking to, big, and under it how long and what is happening.
        Text(
          text = state.agentName ?: tr("Ассистент", "Assistant"),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 12.dp).testTag("call_title"),
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

        Spacer(Modifier.weight(1f))
        AssistantOrb(state = orbState(state), size = 280.dp)
        Spacer(Modifier.height(8.dp))

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
          Spacer(Modifier.height(16.dp))
        }
        state.pendingApproval?.let { request ->
          ToolApprovalBar(
            request = request,
            onApprove = { viewModel.resolveApproval(approved = true) },
            onDeny = { viewModel.resolveApproval(approved = false) },
          )
          Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
          PhotoButton(onOpening = viewModel::onPhotoPickerOpening, onPicked = viewModel::onPhotoPicked)
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
            onClick = hangUp,
            tag = "call_hang_up",
            size = 64.dp,
            container = HangUpRed,
          )
        }
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
    CallPhase.LISTENING -> if (state.micMuted) OrbState.IDLE else OrbState.LISTENING
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
        WordsKind.THINKING -> AnnotatedString(state.activity ?: "")
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
