package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the app knows about one agent's forged screen. Exactly one of these per agent, persisted as
 * JSON by [ForgedScreenStore]; the job's `request` rides along in every case so "Try again" can
 * resend it after a restart, and `previousJobId` so a retried UPDATE still carries the screen it
 * changes (without it the change text would be sent as a request for a brand-new screen).
 */
@Serializable
sealed interface ForgedScreenState {

  /** The job the state refers to, when there is one. */
  val jobId: String?
    get() = null

  /** The screen this job changes, for an "Update"; null for a screen built from scratch. */
  val previousJobId: String?
    get() = null

  /** No screen asked for, or the last one removed. */
  @Serializable
  @SerialName("none")
  data object None : ForgedScreenState

  /** Sent to the gateway; the worker has not taken it yet. */
  @Serializable
  @SerialName("requested")
  data class Requested(
    override val jobId: String,
    val request: String,
    override val previousJobId: String? = null,
  ) : ForgedScreenState

  /** The agent needs an answer before it can go on. */
  @Serializable
  @SerialName("needs_info")
  data class NeedsInfo(
    override val jobId: String,
    val request: String,
    val question: String,
    override val previousJobId: String? = null,
  ) : ForgedScreenState

  /** The worker is writing and compiling; [message] is its latest progress line, if any. */
  @Serializable
  @SerialName("working")
  data class Working(
    override val jobId: String,
    val request: String,
    val message: String? = null,
    override val previousJobId: String? = null,
  ) : ForgedScreenState

  /** A bundle is on disk ([fileName] under the agent's folder) and renders [entryPoint]. */
  @Serializable
  @SerialName("ready")
  data class Ready(
    override val jobId: String,
    val request: String,
    val entryPoint: String,
    val version: Int,
    val fileName: String,
  ) : ForgedScreenState

  /** The job ended without a screen; [message] says why in the person's language. */
  @Serializable
  @SerialName("failed")
  data class Failed(
    override val jobId: String? = null,
    val request: String,
    val message: String,
    override val previousJobId: String? = null,
  ) : ForgedScreenState

  /** True while the gateway should be polled. */
  val isInFlight: Boolean
    get() = this is Requested || this is Working

  /** The last request text, for prefilling and retrying. */
  val lastRequest: String
    get() = when (this) {
      None -> ""
      is Requested -> request
      is NeedsInfo -> request
      is Working -> request
      is Ready -> request
      is Failed -> request
    }
}
