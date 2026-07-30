package com.aiopenpocket.ui.chat.components

// @trace streaming-llm-chat-6595a1
//
// Композер чата: обычная строка ввода и полноэкранный режим. Вся работа с текстом идёт через
// параметры-колбэки, состояние живёт на экране.

import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.voice.input.VoiceInputState
import kotlinx.coroutines.launch

// internal: см. MessageBubble — нужен превью-каркасу (в т.ч. ChatInputStatesPreview).
@Composable
internal fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isLoading: Boolean,
    glassState: GlassState,
    blurEnabled: Boolean = true,
    placeholder: String = "Сообщение...",
    onExpand: () -> Unit,
    voiceState: VoiceInputState,
    onVoiceToggle: () -> Unit,
    onPermissionDenied: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var pendingStart by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) onVoiceToggle()
        if (!granted) onPermissionDenied()
        pendingStart = false
    }

    fun handleMicTap() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onVoiceToggle()
        } else {
            pendingStart = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Единая скруглённая рамка: сверху — текст, снизу — панель управления (как в макете). Blur/tint/
    // блик-бордер — от GlassSurface; фиолетовый акцент-бордер оставлен поверх как второй, внутренний.
    // Плавающее оформление как у навбара/шапки: боковые отступы + мягкая тень (рим — от GlassSurface).
    val inputShape = RoundedCornerShape(24.dp)
    GlassSurface(
        state = glassState,
        shape = inputShape,
        blurEnabled = blurEnabled,
        // Поле ввода — непрозрачное, в отличие от остальной обвязки. Здесь просвечивание мешает не
        // эстетически, а функционально: сквозь полупрозрачную подложку читались сообщения ленты и
        // мешались с набираемым текстом. Плюс это единственная поверхность, куда пользователь
        // смотрит и печатает — ей положено быть спокойным фоном, а не витриной.
        plateAlpha = 1f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(16.dp, inputShape, clip = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    shape = inputShape,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp, max = 160.dp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        // Плейсхолдер тонирован под акцент (как фиолетовая рамка), чтобы отличаться от текста.
                        Text(placeholder, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    }
                    inner()
                },
            )
            Spacer(Modifier.height(6.dp))
            // Нижняя панель внутри рамки: слева — развернуть и микрофон, справа — очистить и отправить.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExpand, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = "Развернуть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                MicButton(state = voiceState, onClick = ::handleMicTap, modifier = Modifier.size(36.dp))
                Spacer(Modifier.weight(1f))
                // Очистить поле — показываем только когда есть текст.
                if (value.isNotBlank()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Очистить",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (isLoading) {
                    // Прогресс вокруг кнопки «стоп» — даёт пользователю возможность отменить ход.
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Stop, contentDescription = "Остановить")
                        }
                    }
                } else {
                    // Кнопка-кружок: залита акцентом, когда есть что отправить; иначе приглушена (как в макете).
                    val canSend = value.isNotBlank()
                    IconButton(
                        // Отправка сворачивает клавиатуру: снимаем фокус с поля -> IME скрывается.
                        onClick = { focusManager.clearFocus(); onSend() },
                        enabled = canSend,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Default.Send,
                            contentDescription = "Отправить",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Полноэкранный режим набора (иконка «развернуть» в инлайновом вводе). Редактирует тот же текст,
 * что и [ChatInput]; «Свернуть» прячет режим без потери набранного, «Очистить» обнуляет, «Отправить»
 * шлёт и сворачивает.
 */
@Composable
internal fun FullScreenComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onCollapse: () -> Unit,
    onSend: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Композер — полноэкранный поверх Scaffold, поэтому сам учитывает статус-бар, навбар и
                // клавиатуру. safeDrawingPadding = max(системные бары, IME), без двойного счёта навбара.
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCollapse) {
                    Icon(Icons.Default.CloseFullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Свернуть")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = value.isNotEmpty()) {
                    Text("Очистить")
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Напишите сообщение...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { focusManager.clearFocus(); onSend() }, enabled = value.isNotBlank()) {
                    Text("Отправить")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
