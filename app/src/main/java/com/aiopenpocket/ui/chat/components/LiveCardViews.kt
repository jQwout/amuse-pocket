package com.aiopenpocket.ui.chat.components

// @trace driven-ui-cards-0418d0
//
// Cards that reflect live state instead of a fixed spec: a reminder (a Room heartbeat row) and the
// notification permission request (the system's real permission state).

import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.formatSchedule
import com.aiopenpocket.ui.chat.formatStatus
import android.content.Intent
import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.flow.Flow

/**
 * A live heartbeat card: it subscribes to the Room row by [DrivenUiCard.ReminderCard.heartbeatId] and
 * reflects it (schedule/status/last result) in real time — without re-rendering the whole message. The
 * cancel button is shown only while the reminder is still "scheduled".
 */
@Composable
fun ReminderCardView(
    card: DrivenUiCard.ReminderCard,
    observeHeartbeat: (String) -> Flow<HeartbeatEntity?>,
    onCancel: (String) -> Unit,
) {
    val hb by remember(card.heartbeatId) { observeHeartbeat(card.heartbeatId) }
        .collectAsState(initial = null)
    val current = hb ?: run {
        Text(
            text = tr("Напоминание не найдено", "Reminder not found"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    val cancelled = current.status == "cancelled"
    val contentAlpha = if (cancelled) 0.5f else 1f

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().alpha(contentAlpha),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (current.mode == "agent") Icons.Default.SmartToy else Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "${formatSchedule(current)} · ${formatStatus(current)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        current.lastResult?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (current.status == "scheduled") {
            TextButton(onClick = { onCancel(card.heartbeatId) }) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(tr("Отменить", "Cancel"))
            }
        }
    }
}

/**
 * A self-contained notification permission request card: it holds its own launcher and reflects the REAL
 * state (areNotificationsEnabled), refreshing it on ON_RESUME — no external action routing is needed.
 */
@Composable
fun NotificationPermissionCardView(card: DrivenUiCard.NotificationPermissionCard) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Coming back from the system dialog/settings → the state is recomputed so the card does not lie.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (card.description.isNotBlank()) {
            Text(
                text = card.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.width(6.dp))
                Text(tr("Уведомления включены", "Notifications are on"), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // < 33: there is no runtime permission — lead to the system notification settings.
                        openAppNotificationSettings(context)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(card.buttonLabel)
            }
        }
    }
}

private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
