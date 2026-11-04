package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.expense.Expense
import com.aiopenpocket.agent.core.expense.ExpenseCategory
import com.aiopenpocket.agent.core.expense.ExpenseLogic
import com.aiopenpocket.agent.core.expense.ExpenseStore
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

data class BudgetUiState(
  val month: YearMonth = YearMonth.now(),
  /** The shown month's records, newest first. */
  val expenses: List<Expense> = emptyList(),
  val monthlyBudget: Double? = null,
  val currency: String = ExpenseLogic.DEFAULT_CURRENCY,
  /** The month switcher stops at the current month: there is nothing to see in the future. */
  val canGoForward: Boolean = false,
) {
  val total: Double get() = expenses.sumOf { it.amount }
  val byCategory: List<Pair<ExpenseCategory, Double>> get() = ExpenseLogic.byCategory(expenses)
}

/** The Budget screen over the [ExpenseStore] it shares with the agent's `expenses` tool. */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class BudgetViewModel(
  private val store: ExpenseStore,
) : ViewModel() {

  private val zone: ZoneId = ZoneId.systemDefault()
  private val month = MutableStateFlow(YearMonth.now(zone))
  private val _state = MutableStateFlow(BudgetUiState(month = month.value))
  val state: StateFlow<BudgetUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch { store.load() }
    viewModelScope.launch {
      combine(store.data, month) { data, m ->
        BudgetUiState(
          month = m,
          expenses = ExpenseLogic.inRange(data.expenses, ExpenseLogic.monthRange(m, zone)),
          monthlyBudget = data.monthlyBudget,
          currency = data.currency,
          canGoForward = m < YearMonth.now(zone),
        )
      }.collect { _state.value = it }
    }
  }

  fun previousMonth() {
    month.value = month.value.minusMonths(1)
  }

  fun nextMonth() {
    if (month.value < YearMonth.now(zone)) month.value = month.value.plusMonths(1)
  }

  /** A record added in the app lands on today, or on the first day of a past month being viewed. */
  fun add(amount: Double, category: ExpenseCategory, note: String) {
    val shown = month.value
    val date = if (shown == YearMonth.now(zone)) System.currentTimeMillis()
    else ExpenseLogic.monthRange(shown, zone).first
    viewModelScope.launch { store.add(amount, category, note, date) }
  }

  fun delete(id: String) {
    viewModelScope.launch { store.delete(id) }
  }

  fun setBudget(amount: Double?) {
    viewModelScope.launch { store.setBudget(amount) }
  }
}
