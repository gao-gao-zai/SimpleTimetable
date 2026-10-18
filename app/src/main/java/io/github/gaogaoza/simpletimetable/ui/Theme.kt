@file:android.annotation.SuppressLint("RestrictedApi")
package io.github.gaogaoza.simpletimetable.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.google.android.material.color.utilities.*
import io.github.gaogaoza.simpletimetable.data.Settings

object ThemeColors {
    fun colors(settings: Settings, dark: Boolean): ColorScheme {
        val source = Hct.fromInt(runCatching { AndroidColor.parseColor(settings.seed) }.getOrDefault(0xff355e45.toInt()))
        val tone = SchemeTonalSpot(source, dark, if (settings.highContrast) 1.0 else 0.0)
        val scheme = DynamicScheme(source, Variant.TONAL_SPOT, dark, if (settings.highContrast) 1.0 else 0.0, tone.primaryPalette, tone.secondaryPalette, tone.tertiaryPalette,
            TonalPalette.fromHueAndChroma(source.hue, minOf(32.0, source.chroma * .85)), TonalPalette.fromHueAndChroma(source.hue, minOf(36.0, source.chroma * .9)))
        val roles = MaterialDynamicColors()
        fun role(c: DynamicColor) = Color(c.getArgb(scheme))
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(primary = role(roles.primary()), onPrimary = role(roles.onPrimary()), primaryContainer = role(roles.primaryContainer()), onPrimaryContainer = role(roles.onPrimaryContainer()),
            secondary = role(roles.secondary()), onSecondary = role(roles.onSecondary()), secondaryContainer = role(roles.secondaryContainer()), onSecondaryContainer = role(roles.onSecondaryContainer()),
            tertiary = role(roles.tertiary()), onTertiary = role(roles.onTertiary()), tertiaryContainer = role(roles.tertiaryContainer()), onTertiaryContainer = role(roles.onTertiaryContainer()),
            background = role(roles.surfaceContainerLow()), onBackground = role(roles.onSurface()), surface = role(roles.surface()), onSurface = role(roles.onSurface()),
            surfaceVariant = role(roles.surfaceContainerHighest()), onSurfaceVariant = role(roles.onSurfaceVariant()), surfaceTint = role(roles.primary()),
            surfaceContainerLowest = role(roles.surfaceContainerLowest()), surfaceContainerLow = role(roles.surfaceContainerLow()), surfaceContainer = role(roles.surfaceContainer()), surfaceContainerHigh = role(roles.surfaceContainerHigh()), surfaceContainerHighest = role(roles.surfaceContainerHighest()),
            outline = role(roles.outline()), outlineVariant = role(roles.outlineVariant()), error = role(roles.error()), onError = role(roles.onError()), errorContainer = role(roles.errorContainer()), onErrorContainer = role(roles.onErrorContainer()), inverseSurface = role(roles.inverseSurface()), inverseOnSurface = role(roles.inverseOnSurface()), inversePrimary = role(roles.inversePrimary()))
    }
}

@Composable fun TimetableTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = if (settings.theme == "system") isSystemInDarkTheme() else settings.theme == "dark"
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    val colors = remember(settings.seed, settings.highContrast, dark) { ThemeColors.colors(settings, dark) }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * if (settings.largeText) 1.2f else 1f)) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
