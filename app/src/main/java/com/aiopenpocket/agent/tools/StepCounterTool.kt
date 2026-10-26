package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/**
 * A thin sensor wrapper: one action — take a snapshot of the current cumulative step counter
 * (Sensor.TYPE_STEP_COUNTER, monotonic since the device's last reboot).
 *
 * Storing it and computing deltas per day/period is the fitness skill's job via memory_tool, so the
 * tool stays stateless and easy to test.
 */
@Inject
@SingleIn(AppScope::class)
class StepCounterTool(
  private val context: Context,
) : Tool {

  override val name = "step_counter"
  override val description =
    "Снимает текущий совокупный счётчик шагов с системного сенсора. Возвращает JSON " +
      "{cumulative, timestamp}. Совокупное значение монотонно растёт с момента перезагрузки " +
      "устройства; для дневной/недельной метрики храните baseline через memory_tool и считайте дельту."

  override val schema = toolSchema(name, description) {
    string(
      "action",
      "Действие: snapshot",
      required = true,
      enum = listOf("snapshot"),
    )
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    if (action != "snapshot") return ToolResult.Error("Unknown action: $action")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      context.requirePermission(
        Manifest.permission.ACTIVITY_RECOGNITION,
        "распознавание активности (шагомер)",
      )?.let { return it }
    }

    val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
      ?: return ToolResult.Error("Sensor service unavailable", recoverable = false)
    val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
      ?: return ToolResult.Error(
        "На устройстве нет аппаратного шагомера (Sensor.TYPE_STEP_COUNTER).",
        recoverable = false,
      )

    val cumulative = readOneEvent(manager, sensor)
      ?: return ToolResult.Error(
        "Сенсор не вернул значение за 3 с — возможно, устройство только что загрузилось.",
      )

    val iso = ISO.format(Date())
    val json = """{"cumulative":$cumulative,"timestamp":"$iso"}"""
    return ToolResult.Success(json)
  }

  private suspend fun readOneEvent(manager: SensorManager, sensor: Sensor): Long? =
    withTimeoutOrNull(TIMEOUT_MS) {
      suspendCancellableCoroutine<Long> { cont ->
        val listener = object : SensorEventListener {
          override fun onSensorChanged(event: SensorEvent?) {
            val value = event?.values?.firstOrNull() ?: return
            manager.unregisterListener(this)
            if (cont.isActive) cont.resume(value.toLong())
          }
          override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        val registered = manager.registerListener(
          listener,
          sensor,
          SensorManager.SENSOR_DELAY_FASTEST,
        )
        if (!registered && cont.isActive) {
          cont.resume(-1L)
        }
        cont.invokeOnCancellation { manager.unregisterListener(listener) }
      }.takeIf { it >= 0L }
    }

  companion object {
    private const val TIMEOUT_MS = 3_000L
    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
      timeZone = TimeZone.getDefault()
    }
  }
}
