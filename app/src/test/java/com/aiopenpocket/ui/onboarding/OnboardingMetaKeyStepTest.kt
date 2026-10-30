package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.profile.ExtractUserProfileUseCase
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.testing.testDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The key step of the onboarding wizard: the person's own Meta Model API key, entered before the first
 * step that calls a model.
 *
 * What matters here is not that a screen shows a field, but that the key lands where the app will read it
 * back — `SettingsRepository.setApiKey("META", …)`, the same slot Settings writes and `LlmModule` reads —
 * and trimmed, and that "Skip" really writes nothing and still lets the person through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingMetaKeyStepTest {

  private val application = ApplicationProvider.getApplicationContext<Context>()
  private lateinit var db: AppDatabase
  private lateinit var settings: FakeSettingsRepository
  private lateinit var viewModel: OnboardingViewModel

  /** An in-memory [SettingsRepository]: the real one brings up the Android Keystore, which the JVM does not have. */
  private class FakeSettingsRepository : SettingsRepository {
    val values = mutableMapOf<String, String>()
    var writes = 0

    override fun getApiKey(provider: String): String = values["key_$provider"].orEmpty()

    override fun setApiKey(provider: String, key: String) {
      writes++
      values["key_$provider"] = key
    }

    override fun getSelectedModel(provider: String): String = "AUTO"

    override fun setSelectedModel(model: String, provider: String) = Unit
  }

  /** The profile parsing is not under test here: it never gets a model. */
  private object SilentLlm : LlmClient {
    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> = emptyFlow()
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).allowMainThreadQueries().build()
    settings = FakeSettingsRepository()
    viewModel = OnboardingViewModel(
      userPreferencesRepository = UserPreferencesRepository(testDataStore(application)),
      memoryRepository = MemoryRepository(db.factDao(), db.episodeDao()),
      extractUserProfile = ExtractUserProfileUseCase(SilentLlm) { LlmProvider.META },
      settingsRepository = settings,
    )
  }

  @After
  fun tearDown() {
    db.close()
    Dispatchers.resetMain()
  }

  /** Real-time wait: the key is written on `Dispatchers.IO` (the Keystore encrypts on the calling thread). */
  private suspend fun awaitState(predicate: (OnboardingUiState) -> Boolean): OnboardingUiState =
    withContext(Dispatchers.Default) { withTimeout(10_000) { viewModel.state.first(predicate) } }

  /** Walks LANGUAGE -> INTRO -> KEY the way a person does: the intro has to finish playing before "Next" works. */
  private suspend fun kotlinx.coroutines.test.TestScope.reachKeyStep() {
    viewModel.handleIntent(OnboardingIntent.NextStep) // LANGUAGE -> INTRO
    viewModel.handleIntent(OnboardingIntent.StartIntroStreaming)
    advanceUntilIdle() // the intro streams on a virtual clock
    assertTrue(viewModel.state.value.isIntroComplete)
    viewModel.handleIntent(OnboardingIntent.NextStep) // INTRO -> KEY
    assertEquals(OnboardingStep.KEY, viewModel.state.value.currentStep)
  }

  /** The key step sits between the intro and the first model call (the "about me" parsing), in that order. */
  @Test
  fun `the key step comes after the intro and before the profile step`() {
    val order = OnboardingStep.entries
    assertEquals(listOf(OnboardingStep.LANGUAGE, OnboardingStep.INTRO, OnboardingStep.KEY, OnboardingStep.PROFILE), order)
  }

  @Test
  fun `Next on the key step saves the key trimmed, exactly where Settings keeps it, and moves on`() = runTest {
    reachKeyStep()

    viewModel.handleIntent(OnboardingIntent.UpdateMetaKey("  meta-test-key\n"))
    viewModel.handleIntent(OnboardingIntent.NextStep)
    awaitState { it.currentStep == OnboardingStep.PROFILE }

    assertEquals("meta-test-key", settings.getApiKey("META"))
    assertEquals(1, settings.writes)
  }

  @Test
  fun `Skip goes on to the profile step without writing anything`() = runTest {
    reachKeyStep()

    viewModel.handleIntent(OnboardingIntent.SkipMetaKey)

    assertEquals(OnboardingStep.PROFILE, viewModel.state.value.currentStep)
    assertEquals("", settings.getApiKey("META"))
    assertEquals("Skip must not touch the key store", 0, settings.writes)
  }

  /** With nothing typed "Next" is dead (the way through is "Skip"): an empty save would wipe a saved key. */
  @Test
  fun `Next with an empty or blank key does nothing`() = runTest {
    reachKeyStep()
    assertFalse(viewModel.state.value.canProceed)

    viewModel.handleIntent(OnboardingIntent.NextStep)
    viewModel.handleIntent(OnboardingIntent.UpdateMetaKey("   "))
    viewModel.handleIntent(OnboardingIntent.NextStep)

    assertEquals(OnboardingStep.KEY, viewModel.state.value.currentStep)
    assertEquals(0, settings.writes)
  }

  /** The edit-mode re-entry from the roadmap shows the key that is already saved instead of an empty field. */
  @Test
  fun `a key saved earlier is shown when the wizard is reopened`() = runTest {
    settings.values["key_META"] = "meta-saved"

    viewModel.loadExistingProfile()
    val state = awaitState { it.metaKeyInput.isNotEmpty() }

    assertEquals("meta-saved", state.metaKeyInput)
    assertTrue(state.copy(currentStep = OnboardingStep.KEY).canProceed)
  }

  @Test
  fun `going Back from the profile step returns to the key step`() = runTest {
    reachKeyStep()
    viewModel.handleIntent(OnboardingIntent.SkipMetaKey)

    viewModel.handleIntent(OnboardingIntent.PreviousStep)

    assertEquals(OnboardingStep.KEY, viewModel.state.value.currentStep)
    assertNull(viewModel.state.value.error)
  }
}
