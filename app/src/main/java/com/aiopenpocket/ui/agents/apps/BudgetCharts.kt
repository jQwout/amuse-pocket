package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.expense.ExpenseCategory
import com.aiopenpocket.agent.core.expense.ExpenseLogic
import com.aiopenpocket.i18n.tr

/**
 * Category colors: muted earth tones that sit next to the app's lime/olive accent instead of fighting it,
 * and read on both the light and the dark cards.
 */
internal fun ExpenseCategory.color(): Color = when (this) {
  ExpenseCategory.FOOD -> Color(0xFF8DA43A)
  ExpenseCategory.TRANSPORT -> Color(0xFF5E8C9A)
  ExpenseCategory.HOME -> Color(0xFFC0965A)
  ExpenseCategory.FUN -> Color(0xFF9A7BA8)
  ExpenseCategory.HEALTH -> Color(0xFF5FA488)
  ExpenseCategory.SHOPPING -> Color(0xFFC77F66)
  ExpenseCategory.OTHER -> Color(0xFF8E948A)
}

internal fun ExpenseCategory.icon(): ImageVector = when (this) {
  ExpenseCategory.FOOD -> Icons.Filled.Restaurant
  ExpenseCategory.TRANSPORT -> Icons.Filled.DirectionsCar
  ExpenseCategory.HOME -> Icons.Filled.Home
  ExpenseCategory.FUN -> Icons.Filled.SportsEsports
  ExpenseCategory.HEALTH -> Icons.Filled.Favorite
  ExpenseCategory.SHOPPING -> Icons.Filled.ShoppingBag
  ExpenseCategory.OTHER -> Icons.Filled.MoreHoriz
}

/** Called at show time, not stored: the language can change while the app runs. */
internal fun ExpenseCategory.label(): String = when (this) {
  ExpenseCategory.FOOD -> tr("Еда", "Food")
  ExpenseCategory.TRANSPORT -> tr("Транспорт", "Transport")
  ExpenseCategory.HOME -> tr("Дом", "Home")
  ExpenseCategory.FUN -> tr("Развлечения", "Fun")
  ExpenseCategory.HEALTH -> tr("Здоровье", "Health")
  ExpenseCategory.SHOPPING -> tr("Покупки", "Shopping")
  ExpenseCategory.OTHER -> tr("Прочее", "Other")
}

/** The category's icon on a soft disc of its color: the same mark in the breakdown and in the list. */
@Composable
internal fun CategoryMark(category: ExpenseCategory, size: Int = 36) {
  Box(
    Modifier.size(size.dp).background(category.color().copy(alpha = 0.18f), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(category.icon(), contentDescription = null, tint = category.color(), modifier = Modifier.size((size * 0.52f).dp))
  }
}

/**
 * Where the month's money went, in one card: a single segmented bar (each category its share of the
 * width) and under it one row per category with its amount and share. One picture instead of a donut
 * plus separate bars that repeated the same numbers.
 */
@Composable
internal fun CategoryBreakdown(
  byCategory: List<Pair<ExpenseCategory, Double>>,
  total: Double,
  currency: String,
  modifier: Modifier = Modifier,
) {
  if (total <= 0 || byCategory.isEmpty()) return
  AppPanel(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Text(
        tr("По категориям", "By category"),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Row(
        modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        byCategory.forEach { (cat, amount) ->
          Box(Modifier.weight((amount / total).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(cat.color()))
        }
      }
      byCategory.forEach { (cat, amount) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          CategoryMark(cat, size = 32)
          Spacer(Modifier.width(12.dp))
          Text(
            cat.label(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
          Text(
            "${(amount / total * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.width(12.dp))
          Text(
            "${ExpenseLogic.money(amount)} $currency",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}
