package dev.amuse.forge.gateway

import dev.amuse.forge.contract.CreateJobRequest

// @trace cloud-forged-screens-40d702

/**
 * Abuse brakes on the forge side. Every queued job spends paid model calls on the worker, so the
 * app side is held to small bodies and a job rate. Every JSON body is capped at
 * [maxClientBodyBytes] except the worker's artifact upload, which carries a bundle.
 * Field limits follow the app's own (`AgentValidator`: name 60, description 240).
 */
data class ForgeLimits(
    val maxClientBodyBytes: Long = 64L * 1024,
    val maxWorkerBodyBytes: Long = 32L * 1024 * 1024,
    val maxRequestChars: Int = 4_000,
    val maxAnswerChars: Int = 2_000,
    val maxAgentNameChars: Int = 60,
    val maxAgentDescriptionChars: Int = 240,
    val jobsPerHour: Int = 20,
    val trustFlyClientIp: Boolean = false,
) {
    /** Problems with a new job, all at once; empty means it may be queued. */
    fun issuesOf(request: CreateJobRequest): List<String> = buildList {
        if (!JobStore.isValidId(request.agentId)) add("agentId must match [A-Za-z0-9_-]{1,64}")
        if (request.request.isBlank()) add("request is required")
        if (request.request.length > maxRequestChars) add("request is longer than $maxRequestChars characters")
        if (request.agentName.length > maxAgentNameChars) add("agentName is longer than $maxAgentNameChars characters")
        if (request.agentDescription.length > maxAgentDescriptionChars) {
            add("agentDescription is longer than $maxAgentDescriptionChars characters")
        }
        request.previousJobId?.let { if (!JobStore.isValidId(it)) add("previousJobId is not a job id") }
    }
}
