package com.aiopenpocket.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.BorderDark
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PurpleAccent

enum class AcademyTab {
  Chat,
  Path,
  Tools,
}

/**
 * Плавающий нижний навбар: тёмная «таблетка» с тенью, оторванная от краёв экрана.
 * Размещается в слоте Scaffold.bottomBar — прозрачный внешний Box резервирует высоту,
 * поэтому контент не перекрывается, а сама панель выглядит парящей над фоном.
 */
@Composable
fun AcademyBottomNav(
  selectedTab: AcademyTab,
  onTabSelected: (AcademyTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp)
      .padding(top = 8.dp, bottom = 20.dp)
      .shadow(16.dp, RoundedCornerShape(28.dp), clip = false)
      .clip(RoundedCornerShape(28.dp))
      .background(CardDark)
      .border(1.dp, BorderDark.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
      .padding(horizontal = 8.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AcademyNavItem(
      icon = Icons.AutoMirrored.Filled.Chat,
      label = "Чат",
      selected = selectedTab == AcademyTab.Chat,
      onClick = { onTabSelected(AcademyTab.Chat) },
    )
    AcademyNavItem(
      icon = Icons.Default.Timeline,
      label = "Путь",
      selected = selectedTab == AcademyTab.Path,
      onClick = { onTabSelected(AcademyTab.Path) },
    )
    AcademyNavItem(
      icon = Icons.Default.Build,
      label = "Тулы",
      selected = selectedTab == AcademyTab.Tools,
      onClick = { onTabSelected(AcademyTab.Tools) },
    )
  }
}

@Composable
private fun AcademyNavItem(
  icon: ImageVector,
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .clip(RoundedCornerShape(18.dp))
      .clickable(onClick = onClick)
      .then(
        if (selected) {
          Modifier
            .background(PurpleAccent.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 8.dp)
        } else {
          Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
        },
      ),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = if (selected) PurpleAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      modifier = Modifier.size(24.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = if (selected) PurpleAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
  }
}
