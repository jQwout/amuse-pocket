package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.expense.Expense
import com.aiopenpocket.agent.core.expense.ExpenseCategory
import com.aiopenpocket.agent.core.expense.ExpenseLogic
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metrox.viewmodel.metroViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private fun appLocale(): Locale = if (AppLanguage.isEnglish) Locale.ENGLISH else Locale("ru")

private fun monthTitle(month: YearMonth): String =
  month.month.getDisplayName(TextStyle.FULL_STANDALONE, appLocale()).replaceFirstChar { it.uppercase() } + " " + month.year

/** The Budget agent's app: the same expense book the agent writes through its `expenses` tool. */
@Composable
fun BudgetApp(modifier: Modifier = Modifier) {
  val viewModel: BudgetViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  BudgetContent(
    state = state,
    onPreviousMonth = viewModel::previousMonth,
    onNextMonth = viewModel::nextMonth,
    onAdd = viewModel::add,
    onDelete = viewModel::delete,
    onSetBudget = viewModel::setBudget,
    modifier = modifier,
  )
}

/** One day of the list: its date, its total, its records (newest first). */
private data class ExpenseDay(val date: LocalDate, val total: Double, val expenses: List<Expense>)

private fun groupByDay(expenses: List<Expense>, zone: ZoneId): List<ExpenseDay> =
  expenses.groupBy { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
    .map { (date, items) -> ExpenseDay(date, items.sumOf { it.amount }, items) }
    .sortedByDescending { it.date }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetContent(
  state: BudgetUiState,
  onPreviousMonth: () -> Unit,
  onNextMonth: () -> Unit,
  onAdd: (Double, ExpenseCategory, String) -> Unit,
  onDelete: (String) -> Unit,
  onSetBudget: (Double?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showAdd by remember { mutableStateOf(false) }
  var showLimit by remember { mutableStateOf(false) }
  val days = remember(state.expenses) { groupByDay(state.expenses, ZoneId.systemDefault()) }

  Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("budget_list"),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 104.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item { Hero(state, onPreviousMonth, onNextMonth, onEditLimit = { showLimit = true }) }
      if (state.expenses.isEmpty()) {
        item { EmptyHint() }
      } else {
        item {
          CategoryBreakdown(state.byCategory, state.total, state.currency, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        }
        days.forEach { day ->
          item(key = "day-${day.date}") { DayHeader(day, state.currency) }
          items(day.expenses, key = { it.id }) { expense -> ExpenseRow(expense, onDelete = { onDelete(expense.id) }) }
        }
      }
    }
    ExtendedFloatingActionButton(
      onClick = { showAdd = true },
      modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).testTag("budget_add"),
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      icon = { Icon(Icons.Filled.Add, contentDescription = null) },
      text = { Text(tr("Трата", "Expense")) },
    )
  }

  if (showAdd) {
    AddExpenseSheet(
      currency = state.currency,
      onDismiss = { showAdd = false },
      onSave = { amount, category, note ->
        onAdd(amount, category, note)
        showAdd = false
      },
    )
  }
  if (showLimit) {
    LimitDialog(
      current = state.monthlyBudget,
      currency = state.currency,
      onDismiss = { showLimit = false },
      onSave = {
        onSetBudget(it)
        showLimit = false
      },
    )
  }
}

/**
 * The top of the screen, straight on the background (no card): the month with arrows, the month's
 * total big, how many records, and the limit - a thin bar with what is left, or a small button to set one.
 */
@Composable
private fun Hero(state: BudgetUiState, onPrevious: () -> Unit, onNext: () -> Unit, onEditLimit: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
        Icon(
          Icons.AutoMirrored.Filled.KeyboardArrowLeft,
          contentDescription = tr("Предыдущий месяц", "Previous month"),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        monthTitle(state.month),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      IconButton(onClick = onNext, enabled = state.canGoForward, modifier = Modifier.size(36.dp)) {
        Icon(
          Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = tr("Следующий месяц", "Next month"),
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (state.canGoForward) 1f else 0.3f),
        )
      }
    }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
      Text(
        ExpenseLogic.money(state.total),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("budget_total"),
      )
      Spacer(Modifier.width(8.dp))
      Text(
        state.currency,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
      )
    }
    val count = state.expenses.size
    Text(
      when {
        count == 0 -> tr("пока ничего не потрачено", "nothing spent yet")
        AppLanguage.isEnglish -> if (count == 1) "spent · 1 expense" else "spent · $count expenses"
        else -> tr("потрачено · трат: $count", "spent · $count expenses")
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 8.dp),
    )
    Spacer(Modifier.height(14.dp))
    val limit = state.monthlyBudget
    if (limit != null) {
      val over = state.total > limit
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onEditLimit),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        LinearProgressIndicator(
          progress = { (state.total / limit).toFloat().coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth().height(6.dp),
          color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
          strokeCap = StrokeCap.Round,
          gapSize = 0.dp,
          drawStopIndicator = {},
        )
        val left = limit - state.total
        Text(
          if (over) {
            tr("Лимит ${ExpenseLogic.money(limit)} · сверху ${ExpenseLogic.money(-left)}", "Limit ${ExpenseLogic.money(limit)} · ${ExpenseLogic.money(-left)} over")
          } else {
            tr("Лимит ${ExpenseLogic.money(limit)} · осталось ${ExpenseLogic.money(left)}", "Limit ${ExpenseLogic.money(limit)} · ${ExpenseLogic.money(left)} left")
          },
          style = MaterialTheme.typography.bodySmall,
          color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Row(
        modifier = Modifier
          .padding(start = 8.dp)
          .clip(RoundedCornerShape(50))
          .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(50))
          .clickable(onClick = onEditLimit)
          .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
          tr("Задать лимит на месяц", "Set a monthly limit"),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

@Composable
private fun EmptyHint() {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      tr("В этом месяце трат пока нет", "No expenses this month yet"),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      tr(
        "Скажите агенту в чате «потратил 12 на обед» или нажмите «Трата».",
        "Tell the agent in chat \"spent 12 on lunch\", or tap Expense.",
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private fun dayTitle(date: LocalDate): String {
  val today = LocalDate.now()
  return when (date) {
    today -> tr("Сегодня", "Today")
    today.minusDays(1) -> tr("Вчера", "Yesterday")
    else -> DateTimeFormatter.ofPattern("EEEE, d MMMM", appLocale()).format(date).replaceFirstChar { it.uppercase() }
  }
}

@Composable
private fun DayHeader(day: ExpenseDay, currency: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      dayTitle(day.date),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      "${ExpenseLogic.money(day.total)} $currency",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseRow(expense: Expense, onDelete: () -> Unit) {
  val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { value ->
      if (value == SwipeToDismissBoxValue.EndToStart) {
        onDelete()
        true
      } else {
        false
      }
    },
  )
  val shape = RoundedCornerShape(18.dp)
  SwipeToDismissBox(
    state = dismissState,
    enableDismissFromStartToEnd = false,
    backgroundContent = {
      // Only while the row is being dragged: at rest nothing shows behind it.
      if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
        Box(
          Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.error).padding(end = 20.dp),
          contentAlignment = Alignment.CenterEnd,
        ) {
          Icon(Icons.Filled.Delete, contentDescription = tr("Удалить", "Delete"), tint = MaterialTheme.colorScheme.onError)
        }
      }
    },
  ) {
    val category = expense.categoryEnum
    // An opaque surface, not glass: the swipe background must not show through a row at rest.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      CategoryMark(category)
      Column(modifier = Modifier.weight(1f)) {
        Text(
          expense.note.ifBlank { category.label() }.replaceFirstChar { it.uppercase() },
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
        Text(
          // A record without a note already shows the category as its title: just the time then.
          if (expense.note.isBlank()) timeLabel(expense.date) else category.label() + " · " + timeLabel(expense.date),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        "−${ExpenseLogic.money(expense.amount)} ${expense.currency}",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

private fun timeLabel(millis: Long): String =
  DateTimeFormatter.ofPattern("HH:mm", appLocale()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddExpenseSheet(
  currency: String,
  onDismiss: () -> Unit,
  onSave: (Double, ExpenseCategory, String) -> Unit,
) {
  var amountText by remember { mutableStateOf("") }
  var category by remember { mutableStateOf(ExpenseCategory.FOOD) }
  var note by remember { mutableStateOf("") }
  val amount = amountText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(tr("Новая трата", "New expense"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
      OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it.take(12) },
        label = { Text(tr("Сумма, $currency", "Amount, $currency")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("budget_amount"),
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExpenseCategory.entries.forEach { cat ->
          FilterChip(
            selected = cat == category,
            onClick = { category = cat },
            label = { Text(cat.label()) },
            leadingIcon = { Icon(cat.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
          )
        }
      }
      OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(80) },
        label = { Text(tr("Заметка", "Note")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Button(
        onClick = { amount?.let { onSave(it, category, note) } },
        enabled = amount != null,
        modifier = Modifier.fillMaxWidth().testTag("budget_save"),
      ) { Text(tr("Сохранить", "Save")) }
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Composable
private fun LimitDialog(current: Double?, currency: String, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
  var text by remember { mutableStateOf(current?.let { ExpenseLogic.money(it) }.orEmpty()) }
  val value = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(tr("Лимит на месяц", "Monthly limit")) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it.take(12) },
          label = { Text(tr("Сумма, $currency", "Amount, $currency")) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(
          tr("Пустое поле или 0 снимает лимит.", "An empty field or 0 removes the limit."),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onSave(if (text.isBlank()) null else value) },
        enabled = text.isBlank() || value != null,
      ) { Text(tr("Сохранить", "Save")) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
  )
}
