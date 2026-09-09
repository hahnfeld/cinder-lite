package app.cinder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Cinder's palette: near-black ground, an ember accent, muted chrome.
// Internal rather than private-to-a-file so the renderers can paint in the same colours.
internal val Ink = Color(0xFF15130F)
internal val Panel = Color(0xFF1D1A15)
internal val Line = Color(0xFF2E2A23)
internal val Text0 = Color(0xFFE8E3D9)
internal val Dim = Color(0xFF8A8377)
internal val Orange = Color(0xFFE0603A)   // ember
internal val Green = Color(0xFF7FB069)
internal val Red = Color(0xFFD9534F)
internal val Blue = Color(0xFF6C9BD1)
internal val Purple = Color(0xFFBD93F9)   // ultracode

internal val mono = FontFamily.Monospace
