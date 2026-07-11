package com.aiopenpocket.ui.agents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Скриншот-эталон read-mostly инспектора агента. Рендерит [AgentInspectorContent] напрямую (НЕ
 * через [AgentInspectorSheet]/`ModalBottomSheet` — тот капризен под Robolectric), поверх того же
 * `Box(Modifier.fillMaxSize().auroraBackground())`, что использует сам контент внутри себя (см.
 * KDoc [AgentInspectorContent] — он рисует аврору сам, т.к. ModalBottomSheet открывается в
 * отдельном Popup-слое; здесь этот же паттерн просто даёт тесту предсказуемый Pixel8-канвас).
 * Агент — `budget` из [MiniAgentCatalog], состояние — фейковое (2 heartbeat'а + 2 файла), тон
 * задаём через `initialTab`, без клика по чипу.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AgentInspectorSheetScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val agent = MiniAgentCatalog.get("budget")!!

  // Фиксированные (не relative-to-now) таймстемпы: formatRelativeDate рендерит "Сегодня, HH:MM"
  // для недавних значений, а System.currentTimeMillis() даёт разную минуту при каждом прогоне —
  // record и verify выполняются в отдельных JVM-процессах в разное время, и такой скриншот не
  // проходит собственную верификацию. Малые epoch-значения гарантированно старше 7 дней (попадают
  // в ветку formatRelativeDate с литеральной "dd.MM.yyyy", без времени суток) — тот же приём, что
  // HomeScreen.previewConversation (updatedAt = 3_000L и т.п.), только с более читаемыми именами.
  private val fixedLastRunAt = 1_700_000_000_000L // 14.11.2023 — стабильно «дальше 7 дней» навсегда
  private val fixedFireAt = 1_700_100_000_000L
  private val fixedOlderFileDate = 1_690_000_000_000L
  private val fixedNewerFileDate = 1_700_000_000_000L

  private val fakeState = AgentInspectorState(
    activeHeartbeats = listOf(
      HeartbeatEntity(
        id = "hb1",
        title = "Проверка бюджета",
        mode = "agent",
        prompt = "проверь бюджет и предупреди о перерасходе",
        agentId = "budget",
        conversationId = "conv1",
        kind = "periodic",
        intervalMinutes = 60,
        status = "scheduled",
        lastRunAt = fixedLastRunAt,
      ),
      HeartbeatEntity(
        id = "hb2",
        title = "Итоги недели",
        mode = "push",
        message = "Итоги трат за неделю готовы",
        agentId = "budget",
        conversationId = "conv1",
        kind = "one_time",
        fireAt = fixedFireAt,
        status = "scheduled",
      ),
    ),
    files = listOf(
      AgentFile(
        name = "budget_report.txt",
        sizeBytes = 2_048,
        lastModified = fixedOlderFileDate,
      ),
      AgentFile(
        name = "notes.md",
        sizeBytes = 512,
        lastModified = fixedNewerFileDate,
      ),
    ),
  )

  @Test
  fun agent_inspector_overview_glass() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          AgentInspectorContent(
            agent = agent,
            state = fakeState,
            onOpenChat = {},
            initialTab = InspectorTab.Overview,
          )
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_inspector_overview_glass.png")
  }

  @Test
  fun agent_inspector_files_glass() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Box(Modifier.fillMaxSize().auroraBackground()) {
          AgentInspectorContent(
            agent = agent,
            state = fakeState,
            onOpenChat = {},
            initialTab = InspectorTab.Files,
          )
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/agent_inspector_files_glass.png")
  }
}
