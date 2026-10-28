package com.aiopenpocket.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand accent (lime) ──
val LimeSignal   = Color(0xFFC4E85C) // the dark theme's accent
val LimeOnDark   = Color(0xFF0E1400) // text/icon on lime (dark)
val OliveAccent  = Color(0xFF4E6B0C) // the light theme's accent (text/icons)
val OliveOnLight = Color(0xFF1B2600) // text on a lime button (light)

// ── Second and third accents ──
// Material's own secondary/tertiary are purple, and everything drawn through those roles (filter chips, the
// tool-trace panel, the badges of a second category) fell out of the palette while they were left unset — see
// the note in Theme.kt. Sage is the lime gone quiet; sand is the warm third voice, far enough from the lime
// that "a different kind of thing" still reads where tertiary means exactly that.
val SageSignal   = Color(0xFFB5CC8E) // dark: the muted companion of the lime
val SageOnDark   = Color(0xFF1B2600)
val SageAccent   = Color(0xFF47593A) // light: readable as text on paper (7.5:1 on white)
val SandSignal   = Color(0xFFE3C98C) // dark: the warm third accent
val SandOnDark   = Color(0xFF2A1F00)
val SandAccent   = Color(0xFF7A5A1E) // light: readable as text on paper (6.3:1 on white)

// ── Dark neutrals ──
val InkBg        = Color(0xFF0A0A0A) // background
val InkSurface   = Color(0xFF131313) // cards/panels
val InkSurface2  = Color(0xFF1C1C1C) // raised surfaces / the user bubble
val InkText      = Color(0xFFEDEDED)
// The secondary text tone. It used to be #A0A0A0, which on a translucent card over the aurora left
// «Разреши уведомления…» and the heartbeat's «06:59 · Сработало» hanging at the edge of legibility on the
// device. Raised to ~11:1 on the background while staying clearly quieter than InkText.
val InkTextSub   = Color(0xFFC0C0C0)
val InkOutline   = Color(0x1AFFFFFF) // rgba(255,255,255,0.10)

// ── Light neutrals ──
val PaperBg      = Color(0xFFFAFAF8) // warm paper (not pure white)
val PaperSurface = Color(0xFFFFFFFF)
val PaperSurface2= Color(0xFFF0F0EB)
val PaperText    = Color(0xFF1A1A1A)
val PaperTextSub = Color(0xFF474747) // was #5C5C5C — the same legibility complaint, the light half of it
val PaperOutline = Color(0x1F000000) // rgba(0,0,0,0.12)
