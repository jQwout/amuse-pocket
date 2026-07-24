package com.aiopenpocket.agent.core.mcp

/**
 * Runtime risk classification for a [Tool]. The harness — not the model — decides how each class is
 * gated. This is the code-level policy seam that best-practices calls for: safety that must be
 * enforced by the runtime should not rely on system-prompt text alone.
 *
 * - [READ]        — no side effects; reads local or device state.
 * - [WRITE]       — creates/modifies local or device state (calendar create, alarm, notification).
 * - [DESTRUCTIVE] — deletes or irreversibly changes state (calendar delete, file delete).
 * - [EXTERNAL]    — leaves the device / involves another party (email compose, future web/connector).
 */
enum class RiskClass { READ, WRITE, DESTRUCTIVE, EXTERNAL }
