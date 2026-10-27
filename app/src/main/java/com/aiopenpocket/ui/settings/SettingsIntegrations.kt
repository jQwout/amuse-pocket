package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
//
// The "Integrations" section of the settings screen: one row per system app the tools work through
// (calendar, alarm clock, mail), with its live permission state and a request button where one is needed.

import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.glassCardSurface

@Composable
internal fun IntegrationsSection(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // The permission status is read from the system live; refreshTick forces a recomputation
  // after returning from the system dialog / settings (ON_RESUME).
  var refreshTick by remember { mutableIntStateOf(0) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) refreshTick++
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  val calendarGranted = remember(refreshTick) {
    isGranted(Manifest.permission.READ_CALENDAR) && isGranted(Manifest.permission.WRITE_CALENDAR)
  }
  val calendarLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) { refreshTick++ }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    IntegrationRow(
      icon = Icons.Default.Event,
      title = tr("Календарь", "Calendar"),
      subtitle = tr("Чтение и создание событий", "Reading and creating events"),
      granted = calendarGranted,
      onRequest = {
        calendarLauncher.launch(
          arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        )
      },
    )
    IntegrationRow(
      icon = Icons.Default.Alarm,
      title = tr("Будильник", "Alarm clock"),
      subtitle = tr("Установка через системные часы", "Set through the system clock"),
      granted = true,
      grantedLabel = tr("Активно", "Active"),
      onRequest = null,
    )
    IntegrationRow(
      icon = Icons.Default.Email,
      title = tr("Почта", "Mail"),
      subtitle = tr("Составление и отправка письма", "Composing and sending an email"),
      granted = true,
      grantedLabel = tr("Без разрешения", "No permission needed"),
      onRequest = null,
    )
  }
}

@Composable
private fun IntegrationRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  granted: Boolean,
  onRequest: (() -> Unit)?,
  grantedLabel: String = tr("Разрешено", "Allowed"),
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .glassCardSurface(SettingsCardShape)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.onPrimary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 16.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    when {
      granted -> StatusChip(text = grantedLabel, granted = true)
      onRequest != null -> Button(
        onClick = onRequest,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
      ) {
        Text(text = tr("Разрешить", "Allow"), style = MaterialTheme.typography.labelLarge)
      }
      else -> StatusChip(text = tr("Не разрешено", "Not allowed"), granted = false)
    }
  }
}

@Composable
private fun StatusChip(text: String, granted: Boolean) {
  val color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = color,
    )
  }
}
