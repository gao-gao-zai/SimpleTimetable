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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.google.android.material.color.utilities.*
import io.github.gaogaoza.simpletimetable.data.Settings

object ThemeColors {
    fun colors(settings: Settings, dark: Boolean): ColorScheme {
        val source = Hct.fromInt(runCatching { AndroidColor.parseColor(settings.seed) }.getOrDefault(0xff355e45.toInt()))
        val tone = SchemeTonalSpot(source, dark, if (settings.highContrast) 1.0 else 0.35)
        val scheme = DynamicScheme(source, Variant.TONAL_SPOT, dark, if (settings.highContrast) 1.0 else 0.35, tone.primaryPalette, tone.secondaryPalette, tone.tertiaryPalette,
            TonalPalette.fromHueAndChroma(source.hue, minOf(10.0, source.chroma * .3)), TonalPalette.fromHueAndChroma(source.hue, minOf(16.0, source.chroma * .4)))
        val roles = MaterialDynamicColors()
        fun role(c: DynamicColor) = Color(c.getArgb(scheme))
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val palette = base.copy(primary = role(roles.primary()), onPrimary = role(roles.onPrimary()), primaryContainer = Color(scheme.primaryPalette.tone(if (dark) 25 else 90)), onPrimaryContainer = role(roles.onPrimaryContainer()),
            secondary = role(roles.secondary()), onSecondary = role(roles.onSecondary()), secondaryContainer = Color(scheme.secondaryPalette.tone(if (dark) 25 else 90)), onSecondaryContainer = role(roles.onSecondaryContainer()),
            tertiary = role(roles.tertiary()), onTertiary = role(roles.onTertiary()), tertiaryContainer = Color(scheme.tertiaryPalette.tone(if (dark) 25 else 90)), onTertiaryContainer = role(roles.onTertiaryContainer()),
            background = role(roles.surfaceContainerLow()), onBackground = role(roles.onSurface()), surface = role(roles.surface()), onSurface = role(roles.onSurface()),
            surfaceVariant = role(roles.surfaceContainerHighest()), onSurfaceVariant = role(roles.onSurfaceVariant()), surfaceTint = role(roles.primary()),
            surfaceContainerLowest = role(roles.surfaceContainerLowest()), surfaceContainerLow = role(roles.surfaceContainerLow()), surfaceContainer = role(roles.surfaceContainer()), surfaceContainerHigh = role(roles.surfaceContainerHigh()), surfaceContainerHighest = role(roles.surfaceContainerHighest()),
            outline = role(roles.outline()), outlineVariant = role(roles.outlineVariant()), error = role(roles.error()), onError = role(roles.onError()), errorContainer = role(roles.errorContainer()), onErrorContainer = role(roles.onErrorContainer()), inverseSurface = role(roles.inverseSurface()), inverseOnSurface = role(roles.inverseOnSurface()), inversePrimary = role(roles.inversePrimary()))
        // Surface roles share text colors. Check every surface that can contain
        // body text, including the darker filled-field and card backgrounds.
        val surfaces = listOf(palette.background, palette.surface, palette.surfaceContainerLowest,
            palette.surfaceContainerLow, palette.surfaceContainer, palette.surfaceContainerHigh,
            palette.surfaceContainerHighest)
        return palette.copy(
            onSurface = readable(palette.onSurface, surfaces, if (settings.highContrast) 10.0 else 7.0),
            onBackground = readable(palette.onSurface, surfaces, if (settings.highContrast) 10.0 else 7.0),
            onSurfaceVariant = readable(palette.onSurfaceVariant, surfaces, if (settings.highContrast) 7.0 else 4.5),
            primary = readable(palette.primary, surfaces, 4.5),
            onPrimary = readable(palette.onPrimary, listOf(readable(palette.primary, surfaces, 4.5)), 4.5),
            onPrimaryContainer = readable(palette.onPrimaryContainer, listOf(palette.primaryContainer), 7.0),
            onSecondaryContainer = readable(palette.onSecondaryContainer, listOf(palette.secondaryContainer), 7.0),
            onTertiaryContainer = readable(palette.onTertiaryContainer, listOf(palette.tertiaryContainer), 7.0),
            tertiary = readable(palette.tertiary, surfaces, 4.5),
            onTertiary = readable(palette.onTertiary, listOf(readable(palette.tertiary, surfaces, 4.5)), 4.5),
            error = readable(palette.error, surfaces, 4.5),
            onError = readable(palette.onError, listOf(readable(palette.error, surfaces, 4.5)), 4.5),
            outline = readable(palette.outline, surfaces, 3.0))
    }

    /** Preserve the Material HCT hue; move only tone until all backgrounds pass. */
    fun readable(foreground: Color, backgrounds: List<Color>, minimum: Double): Color {
        fun passes(color: Color) = backgrounds.all {
            androidx.core.graphics.ColorUtils.calculateContrast(color.toArgb(), it.toArgb()) >= minimum
        }
        if (passes(foreground)) return foreground
        val hct = Hct.fromInt(foreground.toArgb())
        return (0..100).map { Color(Hct.from(hct.hue, hct.chroma, it.toDouble()).toInt()) }
            .filter(::passes).minByOrNull { kotlin.math.abs(Hct.fromInt(it.toArgb()).tone - hct.tone) }
            ?: listOf(Color.Black, Color.White).maxBy { color -> backgrounds.minOf {
                androidx.core.graphics.ColorUtils.calculateContrast(color.toArgb(), it.toArgb())
            } }

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
        MaterialTheme(colorScheme = colors) { CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content) }
    }
}
