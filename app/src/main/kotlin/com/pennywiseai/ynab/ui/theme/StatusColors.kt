package com.pennywiseai.ynab.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colors M3 has no role for (success / warning). Error stays on the
 * built-in `colorScheme.error`. These are FIXED accessible green/amber tones chosen to stay
 * legible over both dynamic light and dynamic dark surfaces — NOT the illustrative mockup
 * hexes. Two palettes; [statusColors] picks by theme. Provided via [LocalStatusColors].
 */
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

// Green ~ M3 tonal palette tones 40/100/90/10 (light) and 80/20/30/90 (dark).
// Amber/brown-orange chosen with the same contrast targets.
private val LightStatusColors = StatusColors(
    success = Color(0xFF2E6B33),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFB2F0B4),
    onSuccessContainer = Color(0xFF00210A),
    warning = Color(0xFF7A5900),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF261A00),
)

private val DarkStatusColors = StatusColors(
    success = Color(0xFF97D89A),
    onSuccess = Color(0xFF003914),
    successContainer = Color(0xFF14531F),
    onSuccessContainer = Color(0xFFB2F0B4),
    warning = Color(0xFFF4BD48),
    onWarning = Color(0xFF412D00),
    warningContainer = Color(0xFF5D4200),
    onWarningContainer = Color(0xFFFFDEA6),
)

fun statusColors(darkTheme: Boolean): StatusColors =
    if (darkTheme) DarkStatusColors else LightStatusColors

/** Absent an explicit provider, default to the light palette (theme always provides one). */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
