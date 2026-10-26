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
 * The single input field of the agent builder wizard. Every step used to draw its own
 * `OutlinedTextField(...).glassCardSurface()` — glassCardSurface adds ITS OWN background and border, and
 * the OutlinedTextField on top adds another (salmon in the error state, with a different radius): a
 * double mismatched border, and on a fresh draft all required fields are empty → all fields red at once.
 *
 * Here it is one filled [TextField] following the pattern that already works in Settings: the glass
 * fill [GlassTokens.LightTint], transparent indicators (no border/underline), explicitly LIGHT text/label
 * colors. An error is shown by a soft container fill and the label color plus the error text under the
 * field (see `ErrorText`), not by a harsh border.
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
      // An error is a soft red fill, not a harsh border: the signal is there, the "broken" look is not.
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
