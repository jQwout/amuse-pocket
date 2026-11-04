package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.tasks.DEFAULT_LIST_NAME
import com.aiopenpocket.agent.core.tasks.INBOX_ID
import com.aiopenpocket.agent.core.tasks.TaskData
import com.aiopenpocket.agent.core.tasks.TaskItem
import com.aiopenpocket.agent.core.tasks.TaskPriority
import com.aiopenpocket.agent.core.tasks.TaskStore
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Task list app's brain: no state of its own, only the shared [TaskStore] (the agent writes to the
 * same one through the `task_list` tool), so the chat and the screen cannot disagree.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class TasksViewModel(private val store: TaskStore) : ViewModel() {

  val data: StateFlow<TaskData> = store.data

  fun add(title: String, listId: Long) {
    val list = store.data.value.lists.firstOrNull { it.id == listId }?.name
    viewModelScope.launch { store.addTask(title, list) }
  }

  fun setDone(id: Long, done: Boolean) {
    viewModelScope.launch { store.setDone(id, done) }
  }

  fun delete(id: Long) {
    viewModelScope.launch { store.deleteTask(id) }
  }

  fun addList(name: String, onCreated: (Long) -> Unit) {
    viewModelScope.launch { onCreated(store.addList(name)) }
  }

  fun deleteList(id: Long) {
    viewModelScope.launch { store.deleteList(id) }
  }
}

/** The Tasks agent's app: lists with counts on top, a quick-add field, the tasks, a "Done" fold. */
@Composable
fun TasksApp(modifier: Modifier = Modifier) {
  val viewModel: TasksViewModel = metroViewModel()
  val data by viewModel.data.collectAsState()
  TasksContent(
    data = data,
    modifier = modifier,
    onAdd = viewModel::add,
    onSetDone = viewModel::setDone,
    onDelete = viewModel::delete,
    onAddList = viewModel::addList,
    onDeleteList = viewModel::deleteList,
  )
}

/** Stateless body, so a preview or test can feed it any [TaskData]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksContent(
  data: TaskData,
  modifier: Modifier = Modifier,
  onAdd: (title: String, listId: Long) -> Unit,
  onSetDone: (id: Long, done: Boolean) -> Unit,
  onDelete: (id: Long) -> Unit,
  onAddList: (name: String, onCreated: (Long) -> Unit) -> Unit,
  onDeleteList: (id: Long) -> Unit,
) {
  // null = "All lists"
  var selected by remember { mutableStateOf<Long?>(null) }
  var doneOpen by remember { mutableStateOf(false) }
  var newListDialog by remember { mutableStateOf(false) }
  var deleteListDialog by remember { mutableStateOf(false) }
  var draft by remember { mutableStateOf("") }
  // A list the agent just removed must not leave the screen on an empty ghost tab.
  val activeId = selected?.takeIf { id -> data.lists.any { it.id == id } }
  val visible = data.tasks.filter { activeId == null || it.listId == activeId }
  val open = visible.filter { !it.done }
  val done = visible.filter { it.done }
  val listNames = data.lists.associate { it.id to it.name }

  fun submit() {
    val title = draft.trim()
    if (title.isEmpty()) return
    onAdd(title, activeId ?: INBOX_ID)
    draft = ""
  }

  Column(modifier = modifier.testTag("tasks_app")) {
    LazyRow(
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      item {
        ListChip(
          label = tr("Все", "All"),
          count = data.tasks.count { !it.done },
          selected = activeId == null,
          onClick = { selected = null },
        )
      }
      items(data.lists, key = { it.id }) { list ->
        ListChip(
          label = if (list.id == INBOX_ID) tr("Входящие", "Inbox") else list.name,
          count = data.tasks.count { it.listId == list.id && !it.done },
          selected = activeId == list.id,
          onClick = { selected = list.id },
        )
      }
      item {
        IconButton(onClick = { newListDialog = true }, modifier = Modifier.testTag("tasks_new_list")) {
          Icon(Icons.Default.Add, contentDescription = tr("Новый список", "New list"), tint = MaterialTheme.colorScheme.primary)
        }
      }
      if (activeId != null && activeId != INBOX_ID) {
        item {
          IconButton(onClick = { deleteListDialog = true }, modifier = Modifier.testTag("tasks_delete_list")) {
            Icon(Icons.Default.Delete, contentDescription = tr("Удалить список", "Delete list"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      singleLine = true,
      placeholder = { Text(tr("Новая задача", "New task"), color = MaterialTheme.colorScheme.onSurfaceVariant) },
      trailingIcon = {
        IconButton(onClick = ::submit, enabled = draft.isNotBlank(), modifier = Modifier.testTag("tasks_add")) {
          Icon(
            Icons.Default.Add,
            contentDescription = tr("Добавить", "Add"),
            tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { submit() }),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("tasks_input"),
    )

    if (open.isEmpty() && done.isEmpty()) {
      EmptyState(modifier = Modifier.fillMaxSize())
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (open.isEmpty()) {
          item {
            Text(
              tr("Всё сделано. Так держать.", "All done. Nice work."),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(vertical = 8.dp),
            )
          }
        }
        items(open, key = { it.id }) { task ->
          SwipeableTask(
            task = task,
            listName = if (activeId == null) listNames[task.listId]?.let { if (it == DEFAULT_LIST_NAME) tr("Входящие", "Inbox") else it } else null,
            onSetDone = onSetDone,
            onDelete = onDelete,
          )
        }
        if (done.isNotEmpty()) {
          item {
            Row(
              modifier = Modifier.fillMaxWidth().clickable { doneOpen = !doneOpen }.padding(vertical = 8.dp).testTag("tasks_done_fold"),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                tr("Сделано", "Done") + " (${done.size})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
              )
              Icon(
                if (doneOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          if (doneOpen) {
            items(done, key = { it.id }) { task ->
              SwipeableTask(task = task, listName = null, onSetDone = onSetDone, onDelete = onDelete)
            }
          }
        }
      }
    }
  }

  if (newListDialog) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { newListDialog = false },
      title = { Text(tr("Новый список", "New list")) },
      text = {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          singleLine = true,
          placeholder = { Text(tr("Например, Покупки", "For example, Shopping")) },
          modifier = Modifier.testTag("tasks_new_list_input"),
        )
      },
      confirmButton = {
        TextButton(
          enabled = name.isNotBlank(),
          onClick = {
            onAddList(name) { selected = it }
            newListDialog = false
          },
        ) { Text(tr("Создать", "Create")) }
      },
      dismissButton = { TextButton(onClick = { newListDialog = false }) { Text(tr("Отмена", "Cancel")) } },
    )
  }
  if (deleteListDialog && activeId != null) {
    AlertDialog(
      onDismissRequest = { deleteListDialog = false },
      title = { Text(tr("Удалить список?", "Delete this list?")) },
      text = { Text(tr("Вместе со списком удалятся все его задачи.", "Its tasks will be deleted with it.")) },
      confirmButton = {
        TextButton(onClick = {
          onDeleteList(activeId)
          selected = null
          deleteListDialog = false
        }) { Text(tr("Удалить", "Delete"), color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { deleteListDialog = false }) { Text(tr("Отмена", "Cancel")) } },
    )
  }
}

@Composable
private fun ListChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(if (count > 0) "$label  $count" else label) },
  )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.padding(32.dp).testTag("tasks_empty"),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      tr("Пока ни одной задачи.", "No tasks yet."),
      color = MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      tr("Напиши выше или скажи агенту Таск-лист в чате: добавь купить молоко.", "Type above, or tell the Task list agent in chat: add buy milk."),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTask(
  task: TaskItem,
  listName: String?,
  onSetDone: (Long, Boolean) -> Unit,
  onDelete: (Long) -> Unit,
) {
  val state = rememberSwipeToDismissBoxState(
    confirmValueChange = { value ->
      if (value == SwipeToDismissBoxValue.EndToStart) {
        onDelete(task.id)
        true
      } else {
        false
      }
    },
  )
  SwipeToDismissBox(
    state = state,
    enableDismissFromStartToEnd = false,
    backgroundContent = {
      // Only while the row is being dragged: at rest nothing shows behind it.
      if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
        Box(
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp),
          contentAlignment = Alignment.CenterEnd,
        ) {
          Icon(Icons.Default.Delete, contentDescription = tr("Удалить", "Delete"), tint = MaterialTheme.colorScheme.onError)
        }
      }
    },
  ) {
    TaskRow(task, listName, onSetDone)
  }
}

@Composable
private fun TaskRow(task: TaskItem, listName: String?, onSetDone: (Long, Boolean) -> Unit) {
  val overdue = !task.done && task.dueAt != null && task.dueAt < System.currentTimeMillis()
  AppPanel(modifier = Modifier.fillMaxWidth().animateContentSize().testTag("task_${task.id}")) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(end = 14.dp).alpha(if (task.done) 0.55f else 1f),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(
        checked = task.done,
        onCheckedChange = { onSetDone(task.id, it) },
        colors = CheckboxDefaults.colors(
          checkedColor = MaterialTheme.colorScheme.primary,
          uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
          checkmarkColor = MaterialTheme.colorScheme.onPrimary,
        ),
      )
      Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
        Text(
          task.title,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodyLarge,
          textDecoration = if (task.done) TextDecoration.LineThrough else null,
        )
        val meta = listOfNotNull(
          listName,
          task.dueAt?.let { tr("до ", "due ") + dueText(it) },
        )
        if (meta.isNotEmpty()) {
          Text(
            meta.joinToString("  ·  "),
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (overdue) FontWeight.SemiBold else null,
          )
        }
      }
      task.priority?.takeIf { it != TaskPriority.NORMAL }?.let { PriorityMark(it) }
    }
  }
}

@Composable
private fun PriorityMark(priority: TaskPriority) {
  val color = if (priority == TaskPriority.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
    Spacer(Modifier.width(6.dp))
    Text(
      if (priority == TaskPriority.HIGH) tr("срочно", "high") else tr("потом", "low"),
      color = color,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

private fun dueText(millis: Long): String =
  Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
