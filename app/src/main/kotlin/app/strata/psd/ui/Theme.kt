package app.strata.psd.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.TextView

/** Material 3 color roles. */
class ColorScheme(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
    val surface: Int, val onSurface: Int, val onSurfaceVariant: Int,
    val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
    val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
    val outline: Int, val outlineVariant: Int,
    val inverseSurface: Int, val inverseOnSurface: Int, val inversePrimary: Int,
    val dark: Boolean,
)

/** Expressive type scale. Sizes in sp. */
class TextStyle(val size: Float, val weight: Int, val tracking: Float, val lineHeight: Float, val display: Boolean)

object Type {
    val displayLarge = TextStyle(57f, 500, -0.25f, 64f, true)
    val displayMedium = TextStyle(45f, 500, 0f, 52f, true)
    val displaySmall = TextStyle(36f, 500, 0f, 44f, true)
    val headlineLarge = TextStyle(32f, 500, 0f, 40f, true)
    val headlineMedium = TextStyle(28f, 500, 0f, 36f, true)
    val headlineSmall = TextStyle(24f, 500, 0f, 32f, true)
    val titleLarge = TextStyle(22f, 500, 0f, 28f, true)
    val titleMedium = TextStyle(16f, 600, 0.15f, 24f, false)
    val titleSmall = TextStyle(14f, 600, 0.1f, 20f, false)
    val bodyLarge = TextStyle(16f, 400, 0.5f, 24f, false)
    val bodyMedium = TextStyle(14f, 400, 0.25f, 20f, false)
    val bodySmall = TextStyle(12f, 400, 0.4f, 16f, false)
    val labelLarge = TextStyle(14f, 600, 0.1f, 20f, false)
    val labelMedium = TextStyle(12f, 600, 0.5f, 16f, false)
    val labelSmall = TextStyle(11f, 600, 0.5f, 16f, false)
}

object Theme {
    lateinit var c: ColorScheme
        private set
    private var displayFamily: Typeface = Typeface.DEFAULT
    private var textFamily: Typeface = Typeface.DEFAULT
    private val faceCache = HashMap<Long, Typeface>()
    var density = 1f
        private set

    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
        val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        c = when {
            Build.VERSION.SDK_INT >= 34 -> systemRoles(ctx, dark)
            Build.VERSION.SDK_INT >= 31 -> fromPalettes(ctx, dark)
            else -> baseline(dark)
        }
        // Prefer the device's brand/expressive families (Google Sans Flex / Google Sans on Pixels).
        displayFamily = firstFamily("google-sans-flex", "google-sans", "sans-serif")
        textFamily = firstFamily("google-sans-text", "google-sans-flex", "sans-serif")
        faceCache.clear()
    }

    private fun firstFamily(vararg names: String): Typeface {
        for (n in names) {
            val tf = Typeface.create(n, Typeface.NORMAL)
            if (tf != null && (tf !== Typeface.DEFAULT || n == "sans-serif")) return tf
        }
        return Typeface.DEFAULT
    }

    fun face(display: Boolean, weight: Int): Typeface {
        val key = (if (display) 1L shl 32 else 0L) or weight.toLong()
        return faceCache.getOrPut(key) { Typeface.create(if (display) displayFamily else textFamily, weight, false) }
    }

    fun style(tv: TextView, s: TextStyle, color: Int = c.onSurface, weight: Int = s.weight) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.size)
        tv.typeface = face(s.display, weight)
        tv.letterSpacing = s.tracking / s.size
        tv.setTextColor(color)
        tv.includeFontPadding = false
        if (Build.VERSION.SDK_INT >= 28) tv.lineHeight = sp(tv.context, s.lineHeight)
    }

    private fun sp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics).toInt()

    // ------------------------------------------------------------------ dynamic color

    private fun systemRoles(ctx: Context, dark: Boolean): ColorScheme {
        fun k(light: Int, night: Int) = ctx.getColor(if (dark) night else light)
        return ColorScheme(
            primary = k(android.R.color.system_primary_light, android.R.color.system_primary_dark),
            onPrimary = k(android.R.color.system_on_primary_light, android.R.color.system_on_primary_dark),
            primaryContainer = k(android.R.color.system_primary_container_light, android.R.color.system_primary_container_dark),
            onPrimaryContainer = k(android.R.color.system_on_primary_container_light, android.R.color.system_on_primary_container_dark),
            secondary = k(android.R.color.system_secondary_light, android.R.color.system_secondary_dark),
            onSecondary = k(android.R.color.system_on_secondary_light, android.R.color.system_on_secondary_dark),
            secondaryContainer = k(android.R.color.system_secondary_container_light, android.R.color.system_secondary_container_dark),
            onSecondaryContainer = k(android.R.color.system_on_secondary_container_light, android.R.color.system_on_secondary_container_dark),
            tertiary = k(android.R.color.system_tertiary_light, android.R.color.system_tertiary_dark),
            onTertiary = k(android.R.color.system_on_tertiary_light, android.R.color.system_on_tertiary_dark),
            tertiaryContainer = k(android.R.color.system_tertiary_container_light, android.R.color.system_tertiary_container_dark),
            onTertiaryContainer = k(android.R.color.system_on_tertiary_container_light, android.R.color.system_on_tertiary_container_dark),
            error = k(android.R.color.system_error_light, android.R.color.system_error_dark),
            onError = k(android.R.color.system_on_error_light, android.R.color.system_on_error_dark),
            errorContainer = k(android.R.color.system_error_container_light, android.R.color.system_error_container_dark),
            onErrorContainer = k(android.R.color.system_on_error_container_light, android.R.color.system_on_error_container_dark),
            surface = k(android.R.color.system_surface_light, android.R.color.system_surface_dark),
            onSurface = k(android.R.color.system_on_surface_light, android.R.color.system_on_surface_dark),
            onSurfaceVariant = k(android.R.color.system_on_surface_variant_light, android.R.color.system_on_surface_variant_dark),
            surfaceContainerLowest = k(android.R.color.system_surface_container_lowest_light, android.R.color.system_surface_container_lowest_dark),
            surfaceContainerLow = k(android.R.color.system_surface_container_low_light, android.R.color.system_surface_container_low_dark),
            surfaceContainer = k(android.R.color.system_surface_container_light, android.R.color.system_surface_container_dark),
            surfaceContainerHigh = k(android.R.color.system_surface_container_high_light, android.R.color.system_surface_container_high_dark),
            surfaceContainerHighest = k(android.R.color.system_surface_container_highest_light, android.R.color.system_surface_container_highest_dark),
            outline = k(android.R.color.system_outline_light, android.R.color.system_outline_dark),
            outlineVariant = k(android.R.color.system_outline_variant_light, android.R.color.system_outline_variant_dark),
            // Inverse roles are resolved by name: they aren't guaranteed on every API 34 build.
            inverseSurface = byName(ctx, "system_inverse_surface", dark, if (dark) 0xFFE6E0E9.toInt() else 0xFF322F35.toInt()),
            inverseOnSurface = byName(ctx, "system_inverse_on_surface", dark, if (dark) 0xFF322F35.toInt() else 0xFFF5EFF7.toInt()),
            inversePrimary = byName(ctx, "system_inverse_primary", dark, if (dark) 0xFF65558F.toInt() else 0xFFCFBDFE.toInt()),
            dark = dark,
        )
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private fun byName(ctx: Context, base: String, dark: Boolean, fallback: Int): Int {
        val id = ctx.resources.getIdentifier(base + if (dark) "_dark" else "_light", "color", "android")
        return if (id != 0) try { ctx.getColor(id) } catch (_: Exception) { fallback } else fallback
    }

    /** Android 12/13: build roles from the system tonal palettes (accent1-3, neutral1-2). */
    private fun fromPalettes(ctx: Context, dark: Boolean): ColorScheme {
        val a1 = palette(ctx, ACCENT1); val a2 = palette(ctx, ACCENT2); val a3 = palette(ctx, ACCENT3)
        val n1 = palette(ctx, NEUTRAL1); val n2 = palette(ctx, NEUTRAL2)
        val err = baseline(dark)
        return if (!dark) ColorScheme(
            primary = a1(40), onPrimary = a1(100), primaryContainer = a1(90), onPrimaryContainer = a1(10),
            secondary = a2(40), onSecondary = a2(100), secondaryContainer = a2(90), onSecondaryContainer = a2(10),
            tertiary = a3(40), onTertiary = a3(100), tertiaryContainer = a3(90), onTertiaryContainer = a3(10),
            error = err.error, onError = err.onError, errorContainer = err.errorContainer, onErrorContainer = err.onErrorContainer,
            surface = n1(98), onSurface = n1(10), onSurfaceVariant = n2(30),
            surfaceContainerLowest = n1(100), surfaceContainerLow = n1(96), surfaceContainer = n1(94),
            surfaceContainerHigh = n1(92), surfaceContainerHighest = n1(90),
            outline = n2(50), outlineVariant = n2(80),
            inverseSurface = n1(20), inverseOnSurface = n1(95), inversePrimary = a1(80), dark = false,
        ) else ColorScheme(
            primary = a1(80), onPrimary = a1(20), primaryContainer = a1(30), onPrimaryContainer = a1(90),
            secondary = a2(80), onSecondary = a2(20), secondaryContainer = a2(30), onSecondaryContainer = a2(90),
            tertiary = a3(80), onTertiary = a3(20), tertiaryContainer = a3(30), onTertiaryContainer = a3(90),
            error = err.error, onError = err.onError, errorContainer = err.errorContainer, onErrorContainer = err.onErrorContainer,
            surface = n1(6), onSurface = n1(90), onSurfaceVariant = n2(80),
            surfaceContainerLowest = n1(4), surfaceContainerLow = n1(10), surfaceContainer = n1(12),
            surfaceContainerHigh = n1(17), surfaceContainerHighest = n1(22),
            outline = n2(60), outlineVariant = n2(30),
            inverseSurface = n1(90), inverseOnSurface = n1(20), inversePrimary = a1(40), dark = true,
        )
    }

    private const val ACCENT1 = 0; private const val ACCENT2 = 1; private const val ACCENT3 = 2
    private const val NEUTRAL1 = 3; private const val NEUTRAL2 = 4

    /** Returns tone(0..100) -> color by interpolating the 13 system palette steps. */
    private fun palette(ctx: Context, which: Int): (Int) -> Int {
        val ids = when (which) {
            ACCENT1 -> intArrayOf(android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50, android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300, android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600, android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900, android.R.color.system_accent1_1000)
            ACCENT2 -> intArrayOf(android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50, android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300, android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600, android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900, android.R.color.system_accent2_1000)
            ACCENT3 -> intArrayOf(android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50, android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300, android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600, android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900, android.R.color.system_accent3_1000)
            NEUTRAL1 -> intArrayOf(android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50, android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300, android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600, android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900, android.R.color.system_neutral1_1000)
            else -> intArrayOf(android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50, android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300, android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600, android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900, android.R.color.system_neutral2_1000)
        }
        val colors = ids.map { ctx.getColor(it) }
        // Steps 0,10,50,100..1000 correspond to tones 100,99,95,90,80,...,10,0.
        val tones = floatArrayOf(100f, 99f, 95f, 90f, 80f, 70f, 60f, 50f, 40f, 30f, 20f, 10f, 0f)
        return { tone ->
            var i = 0
            while (i < tones.size - 1 && tones[i + 1] > tone) i++
            if (i >= tones.size - 1) colors.last() else {
                val t = (tones[i] - tone) / (tones[i] - tones[i + 1])
                lerpColor(colors[i], colors[i + 1], t.coerceIn(0f, 1f))
            }
        }
    }

    private fun baseline(dark: Boolean): ColorScheme = if (!dark) ColorScheme(
        primary = 0xFF65558F.toInt(), onPrimary = Color.WHITE, primaryContainer = 0xFFE9DDFF.toInt(), onPrimaryContainer = 0xFF4D3D75.toInt(),
        secondary = 0xFF625B71.toInt(), onSecondary = Color.WHITE, secondaryContainer = 0xFFE8DEF8.toInt(), onSecondaryContainer = 0xFF4A4458.toInt(),
        tertiary = 0xFF7E5260.toInt(), onTertiary = Color.WHITE, tertiaryContainer = 0xFFFFD9E3.toInt(), onTertiaryContainer = 0xFF633B48.toInt(),
        error = 0xFFBA1A1A.toInt(), onError = Color.WHITE, errorContainer = 0xFFFFDAD6.toInt(), onErrorContainer = 0xFF93000A.toInt(),
        surface = 0xFFFDF7FF.toInt(), onSurface = 0xFF1D1B20.toInt(), onSurfaceVariant = 0xFF49454F.toInt(),
        surfaceContainerLowest = Color.WHITE, surfaceContainerLow = 0xFFF8F2FA.toInt(), surfaceContainer = 0xFFF2ECF4.toInt(),
        surfaceContainerHigh = 0xFFECE6EE.toInt(), surfaceContainerHighest = 0xFFE6E0E9.toInt(),
        outline = 0xFF7A757F.toInt(), outlineVariant = 0xFFCAC4D0.toInt(),
        inverseSurface = 0xFF322F35.toInt(), inverseOnSurface = 0xFFF5EFF7.toInt(), inversePrimary = 0xFFCFBDFE.toInt(), dark = false,
    ) else ColorScheme(
        primary = 0xFFCFBDFE.toInt(), onPrimary = 0xFF36275D.toInt(), primaryContainer = 0xFF4D3D75.toInt(), onPrimaryContainer = 0xFFE9DDFF.toInt(),
        secondary = 0xFFCBC2DB.toInt(), onSecondary = 0xFF332D41.toInt(), secondaryContainer = 0xFF4A4458.toInt(), onSecondaryContainer = 0xFFE8DEF8.toInt(),
        tertiary = 0xFFEFB8C8.toInt(), onTertiary = 0xFF4A2532.toInt(), tertiaryContainer = 0xFF633B48.toInt(), onTertiaryContainer = 0xFFFFD9E3.toInt(),
        error = 0xFFFFB4AB.toInt(), onError = 0xFF690005.toInt(), errorContainer = 0xFF93000A.toInt(), onErrorContainer = 0xFFFFDAD6.toInt(),
        surface = 0xFF141218.toInt(), onSurface = 0xFFE6E0E9.toInt(), onSurfaceVariant = 0xFFCAC4D0.toInt(),
        surfaceContainerLowest = 0xFF0F0D13.toInt(), surfaceContainerLow = 0xFF1D1B20.toInt(), surfaceContainer = 0xFF211F26.toInt(),
        surfaceContainerHigh = 0xFF2B2930.toInt(), surfaceContainerHighest = 0xFF36343B.toInt(),
        outline = 0xFF948F99.toInt(), outlineVariant = 0xFF49454F.toInt(),
        inverseSurface = 0xFFE6E0E9.toInt(), inverseOnSurface = 0xFF322F35.toInt(), inversePrimary = 0xFF65558F.toInt(), dark = true,
    )
}

fun lerpColor(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt().coerceIn(0, 255)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun withAlpha(color: Int, alpha: Float): Int = (((color ushr 24) * alpha).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

/** dp -> px */
val Number.dp: Int get() = (this.toFloat() * Theme.density + 0.5f).toInt()
val Number.dpf: Float get() = this.toFloat() * Theme.density
