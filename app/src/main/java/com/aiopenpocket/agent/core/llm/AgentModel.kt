package com.aiopenpocket.agent.core.llm

// @trace user-agent-builder-848153
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The model a built agent asks to run on, named by FAMILY rather than by id.
 *
 * Why a family key and not a model id: the same model has different ids on the two routes
 * (`muse-wonder-1.0` on the direct Meta Model API, `meta/muse-wonder` on OpenRouter), and the user can
 * switch routes after building the agent. A stored id would be wrong on one of them half the time; a
 * family resolves against whichever catalog is active ([ProviderFallbackChain.resolve]) and follows the
 * catalog when Meta ships 1.4. Meta's guide (dev.meta.ai/docs/agent-frameworks) itself splits the work
 * this way: Wonder for the main, heavy loop, Spark for cost-sensitive work and sub-agents.
 *
 * The persisted/`AgentDefinition.model` value is [key] (a plain string, `null` = "the app's model"),
 * so an unknown key left by a newer app version degrades to the default instead of failing to decode.
 */
enum class AgentModel(val key: String, private val family: String) {
  WONDER("WONDER", "muse-wonder"),
  SPARK("SPARK", "muse-spark");

  /** Whether [modelId] (either route's spelling) belongs to this family. */
  fun matches(modelId: String): Boolean {
    val id = modelId.removePrefix("meta/")
    // muse-spark-open is the 816B open-weight model: it shares the prefix but not the promise the
    // builder makes for Spark ("faster and cheaper"), so a Spark pick must never land on it.
    return id.startsWith(family) && !id.startsWith("muse-spark-open")
  }

  companion object {
    val KEYS: Set<String> = entries.map { it.key }.toSet()

    /** `null` for null AND for a key this build does not know: both mean "use the app's model". */
    fun fromKey(key: String?): AgentModel? = entries.firstOrNull { it.key == key }
  }
}

/**
 * Carries the running agent's model choice through the coroutine context, like [ReasoningEffortContext]
 * and for the same reason: the model is called from many places (loop, reflection, plan steps,
 * heartbeat, planner), all of which already know the agent but none of which should grow a parameter.
 * [com.aiopenpocket.agent.loop.TurnContextHolder.contextElement] adds it wherever the agent is bound to
 * a turn. No element = the app's model, as before.
 */
class AgentModelContext(val model: String?) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<AgentModelContext>
}
