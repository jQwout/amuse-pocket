package com.aiopenpocket.ui.components

// @trace ai-academy-onboarding-5d51bb
// @trace glass-design-system-21a3fe

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
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.ui.components.glass.rememberGlassState

enum class AcademyTab {
    Chat,
    Path,
    Tools,
}

/**
 * Плавающий нижний навбар: стеклянная «таблетка» с тенью, оторванная от краёв экрана.
 * Размещается в слоте Scaffold.bottomBar — прозрачный внешний Box резервирует высоту,
 * поэтому контент не перекрывается, а сама панель выглядит парящей над фоном.
 *
 * [glassState] по умолчанию — свежий несвязанный [rememberGlassState] (тонировка без
 * blur, т.к. не привязан ни к одному hazeSource); экраны подключают реальный blur,
 * передавая свой [GlassState].
 */
@Composable
fun AcademyBottomNav(
    selectedTab: AcademyTab,
    onTabSelected: (AcademyTab) -> Unit,
    modifier: Modifier = Modifier,
    glassState: GlassState = rememberGlassState(),
) {
    val pillShape = RoundedCornerShape(28.dp)
    GlassSurface(
        state = glassState,
        shape = pillShape,
        modifier = modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(top = 8.dp, bottom = 20.dp)
          .shadow(16.dp, pillShape, clip = false),
    ) {
        Row(
            modifier = Modifier
              .fillMaxWidth()
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
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 8.dp)
            } else {
              Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
            },
          ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}
