package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
//
// The settings selectors: one row shape (SettingsSelectorRow) for model, theme and persona, and the glass
// sheet with the list of values that the row opens.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.glass.glassCardSurface

/**
 * ONE selector row shape for the whole settings screen: a meaningful icon in a round badge, a label + the
 * current value, a chevron to the RIGHT (it opens a sheet rather than dropping down).
 *
 * "Model"/"Theme" used to be a stock `DropdownMenu`, and "Persona" a glass sheet, so two different choice
 * patterns lived on one screen, and the dropdown's was worse: a flat opaque panel with the default rounding
 * over the aurora, `.background()` on the outer modifier fought the menu's inner `Surface` (a hard rectangle
 * against a rounded surface), and the selected item differed only by text color. Plus the icon was always a
 * lightning bolt — even for the theme.
 */
@Composable
private fun SettingsSelectorRow(
  label: String,
  value: String,
  icon: ImageVector,
  onClick: () -> Unit,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .glassCardSurface(SettingsCardShape)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 18.dp)
      .testTag(testTag),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.onPrimary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 16.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Default.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** A value selector (model, theme). Opens a [SelectorSheet] — nothing drops down by itself. */
@Composable
internal fun SelectorCard(
  label: String,
  selected: String,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  testTag: String = "settings_selector",
) {
  SettingsSelectorRow(
    label = label,
    value = selected,
    icon = icon,
    onClick = onClick,
    testTag = testTag,
    modifier = modifier,
  )
}

/**
 * A glass choice sheet — the only way to choose a value in settings (the same pattern the persona already
 * had). There can be many items (models), so the sheet scrolls; the selected one is marked with a checkmark
 * AND a lime edge — like the selected language in onboarding and `PersonaCard`, not by text color alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectorSheet(
  title: String,
  subtitle: String?,
  options: List<String>,
  selected: String,
  sheetState: SheetState,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    SelectorSheetContent(
      title = title,
      subtitle = subtitle,
      options = options,
      selected = selected,
      onSelect = onSelect,
    )
  }
}

/**
 * The content of a [SelectorSheet] without the sheet itself. Moved out separately because `ModalBottomSheet`
 * is a popup window: `onRoot()` in a screenshot test does not capture it, and the sheet's layout would be left
 * without a baseline. It also separates the content from the container.
 */
@Composable
internal fun SelectorSheetContent(
  title: String,
  subtitle: String?,
  options: List<String>,
  selected: String,
  onSelect: (String) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("settings_selector_sheet"),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 20.dp),
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp),
      )
    }
    Spacer(modifier = Modifier.height(12.dp))
    // LazyColumn, not forEach in a scrolling column: the OpenRouter model list is live, it has hundreds of
    // rows, and eagerly composing all the cards would mean a sheet frozen for half a second on every opening.
    // heightIn(max) instead of fillMaxHeight — a short list (theme, provider) must stay a short sheet rather
    // than expand to the whole screen.
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = MAX_SELECTOR_LIST_HEIGHT),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      // No key ON PURPOSE: the rows here hold no state and are not animated, so a key gives nothing — while
      // `key = { it }` crashes LazyColumn on two identical rows. The list's source is a third-party
      // service's catalog, and the settings screen must not crash over a duplicate in someone else's data.
      items(options) { option ->
        val isSelected = option == selected
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .glassCardSurface(SettingsCardShape)
            .then(
              if (isSelected) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, SettingsCardShape)
              } else {
                Modifier
              },
            )
            .clickable { onSelect(option) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = option,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          if (isSelected) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = "Выбрано",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

/** The height ceiling of the list in the glass sheet — beyond it the list scrolls inside itself. */
private val MAX_SELECTOR_LIST_HEIGHT = 520.dp

/**
 * The persona opens its own sheet (it has rich cards with stats, not a flat list of rows), but the shape of
 * the row itself is the shared [SettingsSelectorRow], so the three settings selectors read as one family.
 */
@Composable
internal fun PersonaSelectorCard(
  personaId: String,
  displayName: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsSelectorRow(
    label = "ТЕКУЩАЯ РОЛЬ",
    value = displayName ?: personaId,
    icon = Icons.Default.Psychology,
    onClick = onClick,
    testTag = "settings_persona_selector",
    modifier = modifier,
  )
}
