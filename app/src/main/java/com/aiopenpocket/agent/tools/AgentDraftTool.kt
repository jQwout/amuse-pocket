package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153
// @trace driven-ui-cards-0418d0

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.BuilderAgent
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.ValidationIssue
import com.aiopenpocket.agent.core.llm.AgentModel
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.boolean
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.stringList
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The one tool the hidden Builder agent writes with (owner's decision, 31 Oct 2026: building an agent is
 * a chat). It edits ONE draft per builder conversation and can do nothing else: no action publishes, and
 * a draft that is no longer a [AgentStatus.DRAFT] is refused — publishing stays the person's tap on the
 * review screen, which is the whole point of putting a human between the model and a live agent.
 *
 * ### Draft <-> conversation binding
 *
 * The draft's id is DERIVED from the conversation id ([draftIdFor]), not stored in a side table. The
 * model never names an id (so it cannot reach another agent's row, same isolation as `storage_tool`), a
 * retried or repeated call lands on the same draft, and there is nothing to migrate or keep in sync. The
 * draft row is created by the first `update`; `get`/`validate` before that report an empty draft.
 *
 * ### Actions
 *
 * - `get` - the draft so far plus the allowed values.
 * - `update` - set any of name / description / instructions / tools / modes / model / include_shared_profile.
 *   All-or-nothing: if any tool, mode or model is not allowed, NOTHING is saved and the error lists the
 *   allowed values, so the model corrects the call instead of building on a half-applied one.
 * - `validate` - the draft's problems, from [AgentValidator] (the same rules publishing uses).
 * - `finish` - validate; on problems return them (the model asks about the missing parts), on success
 *   return the "Review your agent" card, which the chat draws from this result (see
 *   [isReviewCard]; `resolveCompletedTurn` picks it up).
 */
@Inject
@SingleIn(AppScope::class)
class AgentDraftTool(
  private val userAgents: UserAgentRepository,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  override val name = BuilderAgent.DRAFT_TOOL
  override val description =
    "Builds a draft of a new agent for the person you are talking to. Actions: get (read the draft), " +
    "update (set name, description, instructions, tools, modes, model, include_shared_profile - send only " +
    "what changed), validate (list what is still missing), finish (check the draft and show the person the " +
    "'Review your agent' card). The draft is private to this chat. You cannot publish: the person does that " +
    "on the review screen. Unknown tools, modes or models are rejected with the list of allowed values."
  override val schema = toolSchema(name, description) {
    string(
      "action", "get, update, validate or finish", required = true,
      enum = listOf(ACTION_GET, ACTION_UPDATE, ACTION_VALIDATE, ACTION_FINISH),
    )
    string("name", "Agent name, up to ${AgentValidator.MAX_AGENT_NAME_CHARS} characters (update)")
    string("description", "One-sentence description, up to ${AgentValidator.MAX_DESCRIPTION_CHARS} characters (update)")
    string("instructions", "The new agent's system prompt (update)")
    stringArray("tools", "Tool names the agent may use; replaces the current list (update)", enum = ALLOWED_TOOLS)
    stringArray(
      "modes", "Chat modes the agent allows: ${BuilderAgent.ALLOWED_MODES.joinToString(", ") { it.name }}; replaces the list (update)",
      enum = BuilderAgent.ALLOWED_MODES.map { it.name },
    )
    // No `enum` here on purpose: the central schema check would answer a wrong value with a NON-recoverable
    // error, while execute() answers with the allowed values and lets the model retry.
    string("model", "WONDER, SPARK or default (the app's own model) (update)")
    boolean("include_shared_profile", "Whether the agent may read the person's shared profile; false by default (update)")
  }

  override val riskClass = RiskClass.WRITE

  // Only `update` writes (a private draft row). get/validate/finish just read it. Anything unrecognized
  // fails closed to the ceiling, WRITE - execute() rejects it before anything runs either way.
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    ACTION_GET, ACTION_VALIDATE, ACTION_FINISH -> RiskClass.READ
    else -> RiskClass.WRITE
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    // Snapshot ONCE: the id of this conversation and of the bound agent cannot change under a suspension.
    val conversationId = turnContextHolder.conversationId
    val boundAgentId = turnContextHolder.agentId
    if (boundAgentId != BuilderAgent.ID || conversationId == null) {
      return ToolResult.Error("agent_draft works only inside the agent builder chat.", recoverable = false)
    }
    val draftId = draftIdFor(conversationId)
      ?: return ToolResult.Error("This conversation has no usable id for a draft.", recoverable = false)

    return when (val action = arguments.string("action") ?: return missingArg("action")) {
      ACTION_GET -> ToolResult.Success(snapshot(draftId, userAgents.get(draftId)).toString())
      ACTION_UPDATE -> update(draftId, arguments)
      ACTION_VALIDATE -> ToolResult.Success(snapshot(draftId, userAgents.get(draftId)).toString())
      ACTION_FINISH -> finish(draftId)
      else -> ToolResult.Error("Unknown action: $action. Use get, update, validate or finish.")
    }
  }

  private suspend fun update(draftId: String, args: Map<String, JsonElement>): ToolResult {
    val existing = userAgents.get(draftId)
    // A published/archived draft is out of the model's hands: saveDraft PRESERVES the status, so an edit to
    // a published agent would go live on the next run with no human having pressed Publish. The person edits
    // a published agent on its own screen.
    if (existing != null && existing.status != AgentStatus.DRAFT) {
      return ToolResult.Error(
        "This agent is already published, so it can't be changed from the chat. Tell the person to edit it on the review screen.",
        recoverable = false,
      )
    }

    val tools = args.stringList("tools")
    val modeNames = args.stringList("modes")
    val modelArg = args.string("model")

    val problems = mutableListOf<String>()
    val unknownTools = tools.orEmpty().filter { it !in AgentValidator.KNOWN_TOOL_NAMES }
    if (unknownTools.isNotEmpty()) {
      problems += "Unknown tools: ${unknownTools.joinToString(", ")}. Allowed tools: ${ALLOWED_TOOLS.joinToString(", ")}."
    }
    val unknownModes = modeNames.orEmpty().filter { raw -> BuilderAgent.ALLOWED_MODES.none { it.name.equals(raw, ignoreCase = true) } }
    if (unknownModes.isNotEmpty()) {
      problems += "Unknown modes: ${unknownModes.joinToString(", ")}. Allowed modes: ${BuilderAgent.ALLOWED_MODES.joinToString(", ") { it.name }}."
    }
    val modes = modeNames.orEmpty()
      .mapNotNull { raw -> BuilderAgent.ALLOWED_MODES.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
      .toSet()
    // "default" means the app's own model, which is stored as null; no model argument keeps the current one.
    val modelGiven = modelArg != null
    val model = AgentModel.entries.firstOrNull { it.key.equals(modelArg, ignoreCase = true) }?.key
    if (modelArg != null && model == null && !modelArg.equals(BuilderAgent.MODEL_DEFAULT, ignoreCase = true)) {
      problems += "Unknown model: $modelArg. Allowed models: ${BuilderAgent.ALLOWED_MODELS.joinToString(", ")}."
    }
    if (problems.isNotEmpty()) return ToolResult.Error(problems.joinToString(" "))

    val base = existing ?: newDraft(draftId)
    var next = base.copy(
      name = args.string("name")?.trim() ?: base.name,
      description = args.string("description")?.trim() ?: base.description,
      systemPrompt = args.string("instructions")?.trim() ?: base.systemPrompt,
      toolNames = tools?.toSet() ?: base.toolNames,
      model = if (modelGiven) model else base.model,
      memoryPolicy = base.memoryPolicy.copy(
        includeSharedProfile = args.boolean("include_shared_profile") ?: base.memoryPolicy.includeSharedProfile,
      ),
    )
    if (modes.isNotEmpty()) {
      next = next.copy(
        allowedModes = modes,
        // The default mode must be one of the allowed ones (AgentValidator). Chat when it is allowed, as
        // everywhere else; otherwise the first in the order the person sees them.
        defaultMode = if (AgentMode.CHAT in modes) AgentMode.CHAT else BuilderAgent.ALLOWED_MODES.first { it in modes },
      )
    }

    // saveDraft replaces the custom skills wholesale: hand the current set back so a skill the person
    // added on the review screen survives a chat edit.
    val saved = userAgents.saveDraft(next, skills = userAgents.skillsOf(draftId))
    return ToolResult.Success(snapshot(draftId, saved).toString())
  }

  private suspend fun finish(draftId: String): ToolResult {
    val draft = userAgents.get(draftId)
      ?: return ToolResult.Error(
        "There is no draft yet. Call update with the name, description and instructions first.",
      )
    if (draft.status != AgentStatus.DRAFT) {
      return ToolResult.Error("This agent is already published; the person can open it from the roster.", recoverable = false)
    }
    val issues = AgentValidator.validate(draft)
    if (issues.isNotEmpty()) {
      return ToolResult.Error(
        "The draft is not ready: " + issues.joinToString("; ") { it.describe() } +
          ". Ask the person only about the missing parts, update the draft, then call finish again.",
      )
    }
    return ToolResult.Success(BuilderAgent.reviewCardResult(draft))
  }

  /** What the model reads back after get/update/validate: the draft as it is stored and what is still wrong. */
  private fun snapshot(draftId: String, draft: AgentDefinition?): JsonObject = buildJsonObject {
    put("agent_id", draftId)
    if (draft == null) {
      put("draft", JsonNull)
      putJsonArray("problems") { add(JsonPrimitive("No draft yet: call update with the name, description and instructions.")) }
    } else {
      put(
        "draft",
        buildJsonObject {
          put("name", draft.name)
          put("description", draft.description)
          put("instructions", draft.systemPrompt)
          putJsonArray("tools") { draft.toolNames.sorted().forEach { add(JsonPrimitive(it)) } }
          putJsonArray("modes") { draft.allowedModes.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) } }
          put("model", draft.model ?: BuilderAgent.MODEL_DEFAULT)
          put("include_shared_profile", draft.memoryPolicy.includeSharedProfile)
        },
      )
      // Problems are normal while the draft is being built; they tell the model what is still missing.
      putJsonArray("problems") { AgentValidator.validate(draft).forEach { add(JsonPrimitive(it.describe())) } }
    }
    put("allowed_tools", ALLOWED_TOOLS.joinToString(", "))
    put("allowed_modes", BuilderAgent.ALLOWED_MODES.joinToString(", ") { it.name })
    put("allowed_models", BuilderAgent.ALLOWED_MODELS.joinToString(", "))
  }

  private fun ValidationIssue.describe(): String = if (field != null) "$field: $message" else message

  companion object {
    const val ACTION_GET = "get"
    const val ACTION_UPDATE = "update"
    const val ACTION_VALIDATE = "validate"
    const val ACTION_FINISH = "finish"

    /** Every tool a draft may hold - the validator's allowlist, so a draft this tool saves can always be published. */
    private val ALLOWED_TOOLS: List<String> = AgentValidator.KNOWN_TOOL_NAMES.sorted()

    /**
     * The draft id of builder conversation [conversationId]: lowercase letters and digits of the id behind a
     * fixed prefix, which always satisfies the agent id format (`AgentValidator.ID_REGEX`) and cannot collide
     * with a preset id (none starts with "my-agent-"). Null only for an id with no letters or digits at all.
     */
    fun draftIdFor(conversationId: String): String? {
      val core = conversationId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(40)
      return if (core.isEmpty()) null else "my-agent-$core"
    }

    /** An empty draft: no name yet, private memory, chat mode only and the general cards (`GENERAL_CARD_TEMPLATES`). */
    private fun newDraft(id: String) = AgentDefinition(
      id = id,
      source = AgentSource.USER,
      name = "",
      description = "",
      iconKey = "",
      systemPrompt = "",
      toolNames = emptySet(),
      skillIds = emptySet(),
      defaultMode = AgentMode.CHAT,
      allowedModes = setOf(AgentMode.CHAT),
      memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
      allowedWidgetTemplates = GENERAL_CARD_TEMPLATES,
      configVersion = 0L,
      status = AgentStatus.DRAFT,
    )
  }
}
