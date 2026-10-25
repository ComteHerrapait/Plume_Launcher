package dev.plume

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WCAG contrast for the secondary-text colours Plume actually ships.
 *
 * The colours are literals because MainActivity keeps them private:
 * - #111214 is the light theme's `fg` in applyTheme().
 * - 0.6 is the light alpha passed to secondary() by sectionHeader() and settingsRow().
 * - #F3F5F7 is the light `ground` in applyTheme().
 * - #F7F8FA is the light settings panel in showPanel().
 * - #FFFFFF is the focused light settings row in settingsRow().
 * - #8A8A8A is DARK_TEXT_2. #000000 and #0B0B0B are DARK_BG and DARK_SURFACE;
 *   #101010 is the top of the focused lit-card gradient.
 */
class ContrastTest {
    @Test fun lightSecondaryTextMeetsAa() {
        assertAa("light secondary on #F3F5F7", 0xFF111214.toInt(), 0.6, 0xFFF3F5F7.toInt())
        assertAa("light secondary on #F7F8FA", 0xFF111214.toInt(), 0.6, 0xFFF7F8FA.toInt())
        assertAa("light secondary on #FFFFFF", 0xFF111214.toInt(), 0.6, 0xFFFFFFFF.toInt())
    }

    @Test fun darkSecondaryTextMeetsAa() {
        assertAa("dark secondary on #000000", 0xFF8A8A8A.toInt(), 1.0, 0xFF000000.toInt())
        assertAa("dark secondary on #0B0B0B", 0xFF8A8A8A.toInt(), 1.0, 0xFF0B0B0B.toInt())
        assertAa("dark secondary on #101010", 0xFF8A8A8A.toInt(), 1.0, 0xFF101010.toInt())
    }

    private fun assertAa(label: String, foreground: Int, alpha: Double, background: Int) {
        val shown = composite(foreground, alpha, background)
        val ratio = contrastRatio(shown, background)
        assertTrue("$label: $ratio:1", ratio >= 4.5)
    }

    /** Alpha-blend in sRGB, as Android's TextView alpha does. */
    private fun composite(foreground: Int, alpha: Double, background: Int): Int {
        fun channel(shift: Int): Int {
            val f = (foreground shr shift) and 0xFF
            val b = (background shr shift) and 0xFF
            return (alpha * f + (1 - alpha) * b).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG relative luminance: linearise each sRGB channel, then weight them. */
    private fun relativeLuminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}
