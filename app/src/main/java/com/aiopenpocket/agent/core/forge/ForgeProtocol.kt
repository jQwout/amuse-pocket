package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The app's mirror of the gateway protocol (`forge/contract/.../ForgeProtocol.kt`). Hand-written
 * because the forge is a separate Gradle build; the field names and the lowercase status strings
 * are the contract, so a change on either side is a change on both.
 */
@Serializable
enum class ForgeJobStatus {
  @SerialName("queued") QUEUED,
  @SerialName("accepted") ACCEPTED,
  @SerialName("needs_info") NEEDS_INFO,
  @SerialName("working") WORKING,
  @SerialName("done") DONE,
  @SerialName("failed") FAILED,
}

@Serializable
data class ForgeCreateJobRequest(
  val agentId: String,
  val agentName: String,
  val agentDescription: String,
  val request: String,
  val previousJobId: String? = null,
)

@Serializable
data class ForgeCreateJobResponse(val jobId: String)

@Serializable
data class ForgeAnswerRequest(val text: String)

@Serializable
data class ForgeArtifact(
  val url: String,
  val sha256: String,
  val entryPoint: String,
  val version: Int,
  val sizeBytes: Long = 0,
)

@Serializable
data class ForgeJobView(
  val jobId: String,
  val agentId: String = "",
  val status: ForgeJobStatus,
  val message: String? = null,
  val question: String? = null,
  val artifact: ForgeArtifact? = null,
  val createdAt: Long = 0,
  val updatedAt: Long = 0,
)
