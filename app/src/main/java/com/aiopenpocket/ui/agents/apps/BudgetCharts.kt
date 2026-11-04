package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.expense.ExpenseCategory
import com.aiopenpocket.agent.core.expense.ExpenseLogic
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.GlassCard

/** Category colors: a fixed muted palette picked to read on both the light and the dark glass cards. */
internal fun ExpenseCategory.color(): Color = when (this) {
  ExpenseCategory.FOOD -> Color(0xFFE9A15B)
  ExpenseCategory.TRANSPORT -> Color(0xFF5B8DB8)
  ExpenseCategory.HOME -> Color(0xFF6BA89A)
  ExpenseCategory.FUN -> Color(0xFFB565A7)
  ExpenseCategory.HEALTH -> Color(0xFF4FAE8C)
  ExpenseCategory.SHOPPING -> Color(0xFFD9745C)
  ExpenseCategory.OTHER -> Color(0xFF9AA5B1)
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

/** Donut by category with the total in the middle and a legend beside it (idea from the old openQwoutt app). */
@Composable
internal fun CategoryDonut(
  byCategory: List<Pair<ExpenseCategory, Double>>,
  total: Double,
  currency: String,
  modifier: Modifier = Modifier,
) {
  GlassCard(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val stroke = 22.dp.toPx()
          val inset = stroke / 2
          val arcSize = Size(size.width - stroke, size.height - stroke)
          var start = -90f
          byCategory.forEach { (cat, amount) ->
            val sweep = 360f * (amount / total).toFloat()
            // A hairline gap between slices; a lone slice keeps its full circle.
            val gap = if (byCategory.size > 1 && sweep > 3f) 1.5f else 0f
            drawArc(
              color = cat.color(),
              startAngle = start + gap / 2,
              sweepAngle = sweep - gap,
              useCenter = false,
              topLeft = Offset(inset, inset),
              size = arcSize,
              style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            start += sweep
          }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            tr("Всего", "Total"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            ExpenseLogic.money(total),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(currency, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        byCategory.forEach { (cat, amount) ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(cat.color(), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
              cat.label(),
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
            )
            Text(
              "${(amount / total * 100).toInt()}%",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/** One bar per category, scaled to the biggest one, with the amount on the right. */
@Composable
internal fun CategoryBars(
  byCategory: List<Pair<ExpenseCategory, Double>>,
  currency: String,
  modifier: Modifier = Modifier,
) {
  val max = byCategory.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: return
  GlassCard(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      byCategory.forEach { (cat, amount) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              cat.label(),
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              "${ExpenseLogic.money(amount)} $currency",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Box(
            Modifier.fillMaxWidth().height(8.dp)
              .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(4.dp)),
          ) {
            Box(
              Modifier.fillMaxWidth((amount / max).toFloat().coerceIn(0.02f, 1f)).height(8.dp)
                .background(cat.color(), RoundedCornerShape(4.dp)),
            )
          }
        }
      }
    }
  }
}
