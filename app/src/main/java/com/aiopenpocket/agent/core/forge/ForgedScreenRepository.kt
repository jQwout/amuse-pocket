package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import android.util.Log
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.displayDescription
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything a screen shows about one agent's forged interface: the job, the bytes, the switch. */
data class ForgedAgentUi(
  val state: ForgedScreenState = ForgedScreenState.None,
  /** The downloaded bundle for [ForgedScreenState.Ready]; null until read from disk. */
  val bundle: ByteArray? = null,
  /** The person's choice: open this agent in its own interface rather than in the chat. */
  val customUiEnabled: Boolean = false,
  /** False until the first load from disk finished; screens show nothing forge-related before that. */
  val loaded: Boolean = false,
) {
  val isReady: Boolean get() = state is ForgedScreenState.Ready && bundle != null

  /** The agent opens in its own interface: the person switched it on and a bundle is on disk. */
  val showsCustomUi: Boolean get() = customUiEnabled && isReady

  override fun equals(other: Any?): Boolean =
    other is ForgedAgentUi && state == other.state && customUiEnabled == other.customUiEnabled &&
      loaded == other.loaded && bundle.contentEquals(other.bundle)

  override fun hashCode(): Int = state.hashCode() * 31 + customUiEnabled.hashCode()
}

/**
 * One source of truth for forged screens, shared by the builder (which asks for one), the chat (which
 * shows "your interface is being built"), the agent's card (the switch, regenerate, change) and the
 * full-screen interface itself. Lives in the app scope like [com.aiopenpocket.agent.turn.TurnManager]:
 * a job keeps being polled while the person moves between screens, and every screen observes the same
 * [StateFlow] per agent.
 *
 * Polling: every [pollIntervalMs] (twice that after [SLOW_POLL_AFTER] polls), gives up after
 * [MAX_POLL_FAILURES] transport failures in a row or [MAX_POLL_MILLIS]; a `done` job downloads the
 * bundle, verifies it and lands as [ForgedScreenState.Ready].
 */
class ForgedScreenRepository(
  private val store: ForgedScreenStore,
  private val client: ScreenForgeClient,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  var pollIntervalMs: Long = 3_000L,
) {

  private val flows = HashMap<String, MutableStateFlow<ForgedAgentUi>>()
  private val polls = HashMap<String, Job>()
  private val lock = Mutex()

  /** The agent's forged interface, loaded from disk on first use and kept current afterwards. */
  fun observe(agentId: String): StateFlow<ForgedAgentUi> = flowFor(agentId).asStateFlow()

  /** Loads from disk, applies the crash sentinel and resumes polling; idempotent. */
  suspend fun load(agentId: String) {
    val flow = flowFor(agentId)
    var state = store.load(agentId)
    // A rendering flag left behind means the last render of this bundle took the process down (see
    // ForgedScreenStore.markRendering). Take the screen down instead of composing it again: the person
    // gets an honest card with "Try again" and "Remove", not a crash loop.
    val crashedJob = store.renderingFlag(agentId)
    if (crashedJob != null) {
      store.clearRendering(agentId)
      if (state is ForgedScreenState.Ready && state.jobId == crashedJob) {
        state = ForgedScreenState.Failed(
          jobId = state.jobId,
          request = state.request,
          message = tr(
            "Этот экран уронил приложение при прошлом открытии и снят с показа. Попроси другой вариант.",
            "This screen crashed the app the last time it opened and was taken down. Ask for another version.",
          ),
        )
        store.save(agentId, state)
      }
    }
    val bundle = (state as? ForgedScreenState.Ready)?.let { store.readBundle(agentId, it.fileName) }
    flow.value = ForgedAgentUi(
      state = state,
      bundle = bundle,
      customUiEnabled = store.isCustomUiEnabled(agentId),
      loaded = true,
    )
    if (state.isInFlight) startPolling(agentId, state.jobId!!)
  }

  suspend fun isConfigured(): Boolean = client.isConfigured()

  /** The person's switch. It only chooses chat or the screen; building one is [requestFromBrief]. */
  fun setCustomUiEnabled(agentId: String, enabled: Boolean, definition: AgentDefinition?) {
    Log.d(TAG, "setCustomUiEnabled($agentId, $enabled, definition=${definition?.id})")
    scope.launch {
      store.setCustomUiEnabled(agentId, enabled)
      Log.d(TAG, "switch persisted for $agentId")
      flowFor(agentId).update { it.copy(customUiEnabled = enabled) }
      // @trace screen-brief-and-mockup-before-the-forge-builds-82cfb2
      // Switching on no longer builds a screen by itself: the forge guessed from the agent's name and the
      // person got a screen they never described. The card offers "Build the screen", which opens the brief.
    }
  }

  /** Builds the screen the person described in a [ScreenBrief] (the brief sheet's "Build"). */
  fun requestFromBrief(definition: AgentDefinition, brief: ScreenBrief) {
    submit(definition, AgentUiRequest.fromBrief(definition, brief), previousJobId = null)
  }

  /** The person asks for a change of the current screen; the worker gets the old source back. */
  fun requestChange(agentId: String, definition: AgentDefinition, change: String) {
    val current = flowFor(agentId).value.state as? ForgedScreenState.Ready ?: return
    if (change.isBlank()) return
    submit(definition, change.trim(), previousJobId = current.jobId)
  }

  /** Sends the failed job's request again, with the screen it was changing, if any. */
  fun retry(agentId: String, definition: AgentDefinition) {
    val failed = flowFor(agentId).value.state as? ForgedScreenState.Failed ?: return
    if (failed.request.isBlank()) return
    submit(definition, failed.request, previousJobId = failed.previousJobId)
  }

  private fun submit(definition: AgentDefinition, request: String, previousJobId: String?) {
    val agentId = definition.id
    Log.d(TAG, "submit for $agentId, previous=$previousJobId")
    scope.launch {
      val result = client.submit(
        ForgeCreateJobRequest(
          agentId = agentId,
          agentName = definition.displayName,
          agentDescription = definition.displayDescription,
          request = request,
          previousJobId = previousJobId,
        ),
      )
      when (result) {
        is ForgeResult.Ok -> {
          setState(agentId, ForgedScreenState.Requested(result.value, request, previousJobId), bundle = null)
          startPolling(agentId, result.value)
        }
        is ForgeResult.Failed ->
          setState(agentId, ForgedScreenState.Failed(null, request, result.message, previousJobId), bundle = null)
      }
    }
  }

  /** The person answers the agent's question; the job queues again and polling resumes. */
  fun answer(agentId: String, text: String, onFailed: (String) -> Unit = {}) {
    val current = flowFor(agentId).value.state as? ForgedScreenState.NeedsInfo ?: return
    if (text.isBlank()) return
    scope.launch {
      when (val result = client.answer(current.jobId, text.trim())) {
        is ForgeResult.Ok -> {
          setState(agentId, ForgedScreenState.Requested(current.jobId, current.request, current.previousJobId), bundle = null)
          startPolling(agentId, current.jobId)
        }
        is ForgeResult.Failed -> onFailed(result.message)
      }
    }
  }

  /** Forgets the screen, the job, the bundle and the switch. Also what "Cancel" does while a job runs. */
  fun remove(agentId: String) {
    scope.launch {
      lock.withLock { polls.remove(agentId)?.cancel() }
      store.clear(agentId)
      flowFor(agentId).value = ForgedAgentUi(loaded = true)
    }
  }

  /** Debug builds: shows [bytes] as a ready screen without persisting anything. */
  fun showSample(agentId: String, bytes: ByteArray, request: String) {
    flowFor(agentId).update {
      it.copy(
        state = ForgedScreenState.Ready(SAMPLE_JOB_ID, request, SAMPLE_ENTRY_POINT, 0, "sample.ktx"),
        bundle = bytes,
      )
    }
  }

  /** Crash sentinel hooks for the composable that renders the bundle, see [ForgedScreenStore.markRendering]. */
  fun markRendering(agentId: String, jobId: String) = store.markRendering(agentId, jobId)

  fun clearRendering(agentId: String) = store.clearRendering(agentId)

  private fun flowFor(agentId: String): MutableStateFlow<ForgedAgentUi> {
    synchronized(flows) {
      flows[agentId]?.let { return it }
      val flow = MutableStateFlow(ForgedAgentUi())
      flows[agentId] = flow
      scope.launch { load(agentId) }
      return flow
    }
  }

  private suspend fun setState(agentId: String, state: ForgedScreenState, bundle: ByteArray?) {
    store.save(agentId, state)
    flowFor(agentId).update { it.copy(state = state, bundle = bundle, loaded = true) }
  }

  private fun startPolling(agentId: String, jobId: String) {
    val job = scope.launch {
      var failures = 0
      var polls = 0
      val startedAt = System.currentTimeMillis()
      while (isActive) {
        val current = flowFor(agentId).value.state
        if (current.jobId != jobId || !current.isInFlight) return@launch
        val request = current.lastRequest
        val previous = current.previousJobId
        when (val result = client.status(jobId)) {
          is ForgeResult.Failed -> {
            failures++
            if (failures >= MAX_POLL_FAILURES) {
              setState(agentId, ForgedScreenState.Failed(jobId, request, result.message, previous), bundle = null)
              return@launch
            }
          }
          is ForgeResult.Ok -> {
            failures = 0
            val view = result.value
            when (view.status) {
              ForgeJobStatus.QUEUED, ForgeJobStatus.ACCEPTED -> Unit
              ForgeJobStatus.WORKING -> {
                val next = ForgedScreenState.Working(jobId, request, view.message, previous)
                // Persisted only when something changed: the same line every three seconds is not news.
                if (next != current) setState(agentId, next, bundle = null)
              }
              ForgeJobStatus.NEEDS_INFO -> {
                setState(agentId, ForgedScreenState.NeedsInfo(jobId, request, view.question.orEmpty(), previous), bundle = null)
                return@launch
              }
              ForgeJobStatus.FAILED -> {
                val message = view.message ?: tr("Кузница не справилась с этим экраном.", "The forge could not make this screen.")
                setState(agentId, ForgedScreenState.Failed(jobId, request, message, previous), bundle = null)
                return@launch
              }
              ForgeJobStatus.DONE -> {
                val artifact = view.artifact
                if (artifact == null) {
                  setState(agentId, ForgedScreenState.Failed(jobId, request, tr("Кузница отчиталась без бандла.", "The forge reported done without a bundle."), previous), bundle = null)
                  return@launch
                }
                when (val download = client.downloadBundle(artifact)) {
                  is ForgeResult.Failed ->
                    setState(agentId, ForgedScreenState.Failed(jobId, request, download.message, previous), bundle = null)
                  is ForgeResult.Ok -> {
                    val fileName = store.writeBundle(agentId, jobId, download.value)
                    setState(
                      agentId,
                      ForgedScreenState.Ready(jobId, request, artifact.entryPoint, artifact.version, fileName),
                      bundle = download.value,
                    )
                  }
                }
                return@launch
              }
            }
          }
        }
        polls++
        if (System.currentTimeMillis() - startedAt > MAX_POLL_MILLIS) {
          setState(agentId, ForgedScreenState.Failed(jobId, request, tr("Кузница молчит уже 15 минут. Попробуй ещё раз.", "The forge has been silent for 15 minutes. Try again."), previous), bundle = null)
          return@launch
        }
        delay(if (polls > SLOW_POLL_AFTER) pollIntervalMs * 2 else pollIntervalMs)
      }
    }
    scope.launch {
      lock.withLock {
        polls.remove(agentId)?.takeIf { it !== job }?.cancel()
        polls[agentId] = job
      }
    }
  }

  companion object {
    private const val TAG = "ForgeUi"
    const val SAMPLE_JOB_ID = "sample"
    const val SAMPLE_ENTRY_POINT = "HelloForgeScreen"
    private const val MAX_POLL_FAILURES = 5
    private const val MAX_POLL_MILLIS = 15 * 60 * 1000L
    private const val SLOW_POLL_AFTER = 20
  }
}

/** Turns the person's [ScreenBrief] and the agent's identity into the request the forge gets. */
object AgentUiRequest {
  private const val PROMPT_EXCERPT = 700
  private const val PREFIX = "Design the main screen of the agent"

  /** True for a request the app built itself (nothing the person typed - the card does not quote it). */
  fun isAuto(request: String): Boolean = request.startsWith(PREFIX)

  // @trace screen-brief-and-mockup-before-the-forge-builds-82cfb2
  /**
   * The request from the person's [ScreenBrief]: their words first (the forge builds what they asked for,
   * not what it guesses from the agent's name), then the blocks and the layout spelled out, then the agent
   * as context. Starts with [PREFIX] too: the card still does not quote it back as a typed change.
   */
  fun fromBrief(definition: AgentDefinition, brief: ScreenBrief): String = buildString {
    append(PREFIX).append(" \"").append(definition.displayName).append("\" exactly as the person describes it.\n")
    append("The person's brief: ").append(brief.text.trim().take(ScreenBrief.TEXT_MAX)).append('\n')
    if (brief.blocks.isNotEmpty()) {
      append("It must contain, in this order:\n")
      ScreenBlock.entries.filter { it in brief.blocks }.forEach { append("- ").append(it.forForge).append('\n') }
    }
    append("Layout: ").append(brief.layout.forForge).append(".\n")
    agentContext(definition)
    append(
      "\nOne screen. Keep every record the person enters with the app's storage functions (saveText/loadText, " +
        "saveInt/loadInt; several records as lines of one text value) so it survives closing. Colors only " +
        "through themeColor names. Short English labels, generous spacing, no settings, no chat.",
    )
  }

  private fun StringBuilder.agentContext(definition: AgentDefinition) {
    val description = definition.displayDescription.trim()
    if (description.isNotBlank()) append("What the agent is for: ").append(description).append('\n')
    val prompt = definition.systemPrompt.trim()
    if (prompt.isNotBlank()) {
      append("Its instructions, for context only (do not print them on the screen):\n")
      append(prompt.take(PROMPT_EXCERPT))
      if (prompt.length > PROMPT_EXCERPT) append(" ...")
      append('\n')
    }
  }
}
