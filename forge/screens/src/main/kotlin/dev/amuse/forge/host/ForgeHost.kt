package dev.amuse.forge.host

// @trace forge-host-capabilities-f648ae

import androidx.compose.runtime.Composable

// What the app lends a forged screen. These bodies never run: Ketoy compiles a call to any of these
// functions into a capability call (ids in ketoy-capabilities.json), and the app's own code answers
// it. Storage is the screen's agent's own; the photo picker is the app's, with the app's permissions.

/** The Int saved under [key] for this agent, or [default] when nothing was saved. */
fun loadInt(key: String, default: Int): Int = default

/** Saves [value] under [key] for this agent; it survives closing the screen and the app. */
fun saveInt(key: String, value: Int) {}

/** The text saved under [key] for this agent, or [default] when nothing was saved. */
fun loadText(key: String, default: String): String = default

/** Saves [value] under [key] for this agent. */
fun saveText(key: String, value: String) {}

/**
 * The app's photo slot: a button that opens the gallery, then the chosen photo. The photo is kept
 * under [key] for this agent, so it is still there next time. [label] is the button's text.
 */
@Composable
fun PhotoPicker(key: String, label: String) {}
