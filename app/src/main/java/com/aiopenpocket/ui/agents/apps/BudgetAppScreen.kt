package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import com.aiopenpocket.ui.components.glass.GlassCard
import dev.zacsweers.metrox.viewmodel.metroViewModel
import java.time.Instant
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

  Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("budget_list"),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item { MonthSwitcher(state.month, state.canGoForward, onPreviousMonth, onNextMonth) }
      item { TotalCard(state, onEditLimit = { showLimit = true }) }
      if (state.expenses.isEmpty()) {
        item { EmptyHint() }
      } else {
        item { CategoryDonut(state.byCategory, state.total, state.currency) }
        item { CategoryBars(state.byCategory, state.currency) }
        item {
          Column {
            Text(
              tr("Траты", "Expenses"),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              tr("Смахните трату влево, чтобы удалить", "Swipe an expense left to delete it"),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        items(state.expenses, key = { it.id }) { expense -> ExpenseRow(expense, onDelete = { onDelete(expense.id) }) }
      }
    }
    ExtendedFloatingActionButton(
      onClick = { showAdd = true },
      modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).testTag("budget_add"),
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      icon = { Icon(Icons.Filled.Add, contentDescription = null) },
      text = { Text(tr("Добавить", "Add")) },
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

@Composable
private fun MonthSwitcher(month: YearMonth, canGoForward: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    IconButton(onClick = onPrevious) {
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = tr("Предыдущий месяц", "Previous month"))
    }
    Text(
      monthTitle(month),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    IconButton(onClick = onNext, enabled = canGoForward) {
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = tr("Следующий месяц", "Next month"))
    }
  }
}

@Composable
private fun TotalCard(state: BudgetUiState, onEditLimit: () -> Unit) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        tr("Потрачено за месяц", "Spent this month"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "${ExpenseLogic.money(state.total)} ${state.currency}",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("budget_total"),
      )
      val limit = state.monthlyBudget
      if (limit != null) {
        val ratio = (state.total / limit).toFloat()
        val over = state.total > limit
        LinearProgressIndicator(
          progress = { ratio.coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth().height(8.dp),
          color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
          strokeCap = StrokeCap.Round,
        )
        val left = limit - state.total
        Text(
          if (over) tr("Лимит ${ExpenseLogic.money(limit)} превышен на ${ExpenseLogic.money(-left)}", "Limit ${ExpenseLogic.money(limit)} exceeded by ${ExpenseLogic.money(-left)}")
          else tr("Лимит ${ExpenseLogic.money(limit)}, осталось ${ExpenseLogic.money(left)}", "Limit ${ExpenseLogic.money(limit)}, ${ExpenseLogic.money(left)} left"),
          style = MaterialTheme.typography.bodySmall,
          color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onEditLimit, contentPadding = PaddingValues(0.dp)) {
        Text(
          if (limit == null) tr("Задать лимит на месяц", "Set a monthly limit") else tr("Изменить лимит", "Change the limit"),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun EmptyHint() {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        tr("В этом месяце трат пока нет.", "No expenses this month yet."),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        tr(
          "Скажите агенту «Бюджет» в чате: потратил 12 на обед",
          "Tell the Budget agent in chat: spent 12 on lunch",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
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
  SwipeToDismissBox(
    state = dismissState,
    enableDismissFromStartToEnd = false,
    backgroundContent = {
      Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f), RoundedCornerShape(20.dp)).padding(end = 20.dp),
        contentAlignment = Alignment.CenterEnd,
      ) {
        Icon(Icons.Filled.Delete, contentDescription = tr("Удалить", "Delete"), tint = MaterialTheme.colorScheme.onError)
      }
    },
  ) {
    val category = expense.categoryEnum
    GlassCard(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(Modifier.size(36.dp).background(category.color().copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
          Icon(category.icon(), contentDescription = null, tint = category.color(), modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            expense.note.ifBlank { category.label() },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
          )
          Text(
            category.label() + " · " + dayLabel(expense.date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          "${ExpenseLogic.money(expense.amount)} ${expense.currency}",
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

private fun dayLabel(millis: Long): String =
  DateTimeFormatter.ofPattern("d MMM", appLocale()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

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
