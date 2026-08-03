package com.aiopenpocket.agent.core.prompt

// @trace heartbeat-napominalki-agenta-5f0442

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.plan.context.PlanContextProvider
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.aiopenpocket.testing.testDataStore

/**
 * Модель обязана видеть, КОТОРЫЙ СЕЙЧАС ЧАС.
 *
 * Живой баг: агент на любую просьбу «напомни в 19:00» ставил СИСТЕМНЫЙ БУДИЛЬНИК, никогда — пуш.
 * Причина оказалась механической, а не в формулировках промпта: `heartbeat` принимает
 * `delay_minutes` (относительное время), `alarm` — `hour`/`minute` (абсолютное). Не зная текущего
 * времени, вычесть «19:00 минус сейчас» невозможно в принципе, поэтому единственным ТЕХНИЧЕСКИ
 * вызываемым инструментом оставался будильник. Никакая инструкция в промпте этого не чинит —
 * чинит только само время в контексте.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PromptCurrentTimeTest {

  private lateinit var db: AppDatabase
  private lateinit var assembler: PrePromptAssembler

  /** Суббота, 25 июля 2026, 14:37 — фиксируем всё до минуты, чтобы проверять точный текст. */
  private val fixedNow: Long = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow")).apply {
    set(2026, Calendar.JULY, 25, 14, 37, 0)
    set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  @Before
  fun setUp() {
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    assembler = PrePromptAssembler(
      assets = AssetSource { null },
      memoryRepository = MemoryRepository(db.factDao(), db.episodeDao()),
      conversationRepository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao()),
      skillRegistry = SkillRegistry(emptySet(), UserSkillRepository(db.userSkillDao())),
      userPreferencesRepository = UserPreferencesRepository(testDataStore(context)),
      agentResolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao(), AgentHookRepository(db.agentHookDao()))),
      planContextProvider = PlanContextProvider(PlanRepository(db, db.planDao())),
      clock = { fixedNow },
    )
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun `the system prompt states the current time`() = runTest {
    val prompt = assembler.assemble("c1", "напомни в 19:00 позвонить маме").systemPrompt

    assertTrue("в промпте нет текущего времени:\n$prompt", prompt.contains("14:37"))
  }

  @Test
  fun `the system prompt states the current date`() = runTest {
    val prompt = assembler.assemble("c1", "привет").systemPrompt

    assertTrue("нет числа месяца: $prompt", prompt.contains("25"))
    assertTrue("нет месяца: $prompt", prompt.contains("июля", ignoreCase = true))
    assertTrue("нет года: $prompt", prompt.contains("2026"))
  }

  @Test
  // Имя без кавычек намеренно: Kotlin делает из имени бэктик-теста имя .class-файла, а кавычки в нём
  // недопустимы на Windows — сборка падает на записи класса, а не на самом тесте.
  fun `the system prompt states the weekday - needed for phrases like v pyatnitsu`() = runTest {
    val prompt = assembler.assemble("c1", "привет").systemPrompt

    assertTrue("нет дня недели: $prompt", prompt.contains("суббот", ignoreCase = true))
  }

  @Test
  fun `the system prompt states the timezone`() = runTest {
    val prompt = assembler.assemble("c1", "привет").systemPrompt

    assertTrue("нет часового пояса: $prompt", prompt.contains("Moscow") || prompt.contains("UTC"))
  }

  @Test
  fun `the time sits in the trusted zone, above user_data`() = runTest {
    db.factDao().insert(
      com.aiopenpocket.agent.core.memory.entity.FactEntity(
        id = "f1", key = "user.name", value = "Иван", category = "profile",
      ),
    )

    val prompt = assembler.assemble("c1", "привет").systemPrompt

    // Время генерирует приложение, а не пользователь — оно обязано стоять ВЫШЕ ограды недоверенных
    // данных, иначе окажется внутри <user_data> и будет прочитано как справочные данные.
    val timeAt = prompt.indexOf("14:37")
    val fenceAt = prompt.indexOf("<user_data>")
    assertTrue("время не найдено", timeAt >= 0)
    if (fenceAt >= 0) assertTrue("время попало внутрь <user_data>", timeAt < fenceAt)
  }
}
