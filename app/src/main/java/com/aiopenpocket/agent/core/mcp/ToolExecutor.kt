package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject

@Inject
class ToolExecutor(
  private val registry: ToolRegistry,
) {
  suspend fun execute(name: String, arguments: Map<String, JsonElement>): ToolResult {
    val tool = registry.get(name)
      ?: return ToolResult.Error("Tool '$name' not found", recoverable = false)
    // Broken JSON from the model is reported as broken JSON — before the schema check, which would
    // otherwise say "missing required argument" about a map that was never the model's real input.
    MalformedArguments.excerptOf(arguments)?.let {
      return ToolResult.Error(MalformedArguments.message(name, it), recoverable = true)
    }
    // Validate against the declared schema once here, so tools don't each re-check required/enum.
    SchemaValidator.validate(tool.schema, arguments)?.let { return it }
    return try {
      // A time ceiling on EVERY tool call. Tools call ContentResolver, Room and system services — a stuck
      // call would hold the whole agent turn: the stream does not close, the status stays Running,
      // and only an external cancellation of the turn helps. The model must get an error and be able
      // to move on.
      //
      // withTimeoutOrNull, not withTimeout: the latter throws TimeoutCancellationException, which is
      // itself a CancellationException — the branch below would catch it and rethrow it as "turn
      // cancelled", so one tool's timeout would kill the whole turn instead of returning an Error.
      withTimeoutOrNull(TOOL_TIMEOUT_MS) { tool.execute(arguments) }
        ?: ToolResult.Error(
          "Tool '$name' timed out after ${TOOL_TIMEOUT_MS / 1000}s and was aborted.",
          recoverable = true,
        )
    } catch (e: CancellationException) {
      // Cancellation of the request (the user tapped Stop) is not swallowed, or the agent loop would go on.
      throw e
    } catch (e: Exception) {
      ToolResult.Error("Execution failed: ${e.message}", recoverable = true)
    }
  }

  companion object {
    /**
     * All tools here are local (DB, ContentResolver, system services) — they need seconds, not tens of
     * seconds. The ceiling is chosen so it surely does not cut an honest slow call, yet does not turn a
     * hung one into an endless wait from the user's point of view.
     *
     * A SHARP EDGE IN TESTS. `withTimeoutOrNull` counts by the coroutine context's clock, and `runTest`
     * provides VIRTUAL time. If a tool inside a test goes off to work on another thread (the typical
     * case is a suspend Room DAO on its own executor), the test dispatcher is left with no work and
     * skips virtual time forward: a tool that finishes in microseconds gets a timeout. The cure is NOT
     * changing this number but putting Room on a direct executor in the test itself:
     * `.setQueryExecutor(Runnable::run).setTransactionExecutor(Runnable::run)` — see
     * `CompositeSkillRegistryTest`, which ran into exactly this.
     */
    const val TOOL_TIMEOUT_MS = 30_000L
  }
}
