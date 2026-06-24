package com.aiopenpocket.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.PurpleAccent

enum class AcademyTab {
  Chats,
  Path,
  Library,
}

@Composable
fun AcademyBottomNav(
  selectedTab: AcademyTab,
  onTabSelected: (AcademyTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(CardDark)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AcademyNavItem(
      icon = Icons.AutoMirrored.Filled.Chat,
      label = "Чаты",
      selected = selectedTab == AcademyTab.Chats,
      onClick = { onTabSelected(AcademyTab.Chats) },
    )
    AcademyNavItem(
      icon = Icons.Default.Timeline,
      label = "Путь",
      selected = selectedTab == AcademyTab.Path,
      onClick = { onTabSelected(AcademyTab.Path) },
    )
    AcademyNavItem(
      icon = Icons.Default.MenuBook,
      label = "Библиотека",
      selected = selectedTab == AcademyTab.Library,
      onClick = { onTabSelected(AcademyTab.Library) },
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
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
      .then(
        if (selected) {
          Modifier
            .background(PurpleAccent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 8.dp)
        } else {
          Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AcademyBottomNavPreview() {
  MyApplicationTheme {
    AcademyBottomNav(selectedTab = AcademyTab.Path, onTabSelected = {})
  }
}
