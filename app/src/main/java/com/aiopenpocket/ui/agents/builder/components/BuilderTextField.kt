package com.aiopenpocket.ui.agents.builder.components

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * Единое поле ввода визарда конструктора агента. Раньше каждый шаг рисовал свой
 * `OutlinedTextField(...).glassCardSurface()` — glassCardSurface добавляет СВОЙ фон и рамку, а
 * OutlinedTextField поверх — ещё одну (в error-состоянии салмон, другого радиуса): двойная
 * несовпадающая рамка, а на свежем черновике все обязательные поля пусты → все поля разом красные.
 *
 * Здесь один filled [TextField] по тому же паттерну, что уже работает в Настройках: стеклянная
 * заливка [GlassTokens.LightTint], индикаторы прозрачные (никакой рамки/подчёркивания), явные
 * СВЕТЛЫЕ цвета текста/лейбла. Ошибка обозначается мягкой заливкой контейнера и цветом лейбла плюс
 * текстом ошибки под полем (см. `ErrorText`), а не резкой рамкой.
 */
@Composable
fun BuilderTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String? = null,
  placeholder: String? = null,
  isError: Boolean = false,
  minLines: Int = 1,
  maxLines: Int = 1,
) {
  TextField(
    value = value,
    onValueChange = onValueChange,
    label = label?.let { { Text(it) } },
    placeholder = placeholder?.let { { Text(it) } },
    isError = isError,
    minLines = minLines,
    maxLines = maxLines,
    shape = RoundedCornerShape(16.dp),
    modifier = modifier.fillMaxWidth(),
    colors = TextFieldDefaults.colors(
      focusedContainerColor = GlassTokens.LightTint,
      unfocusedContainerColor = GlassTokens.LightTint,
      // Ошибка — мягкая красная заливка, а не резкая рамка: сигнал есть, «сломанного» вида нет.
      errorContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
      focusedIndicatorColor = Color.Transparent,
      unfocusedIndicatorColor = Color.Transparent,
      errorIndicatorColor = Color.Transparent,
      disabledIndicatorColor = Color.Transparent,
      cursorColor = MaterialTheme.colorScheme.primary,
      focusedTextColor = MaterialTheme.colorScheme.onSurface,
      unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
      focusedLabelColor = MaterialTheme.colorScheme.primary,
      unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
      focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
      unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
  )
}
