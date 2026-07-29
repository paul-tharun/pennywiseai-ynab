package com.pennywiseai.ynab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The app's single Material 3 theme. Uses dynamic color on API 31+ (personal tool —
 * matching the device wallpaper is the cheapest way to look native) and falls back to
 * the M3 baseline scheme below 31. Also provides [LocalStatusColors] — the success/warning
 * semantic colors M3's scheme lacks — selected for the active light/dark mode.
 */
@Composable
fun PennyWiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalStatusColors provides statusColors(darkTheme)) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
