package com.portalhacks.frame

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Portal design tokens for the Compose UI — mirrors the view-based {@link Ui}
 * palette (dark theme, platform blue, warm neutrals) so the migrated screens look
 * identical. Inter is loaded from assets (no GMS downloadable-font provider).
 */
object PortalColors {
    val Blue = Color(0xFF1990FF)
    val BluePressed = Color(0xFF1877F2)
    val Green = Color(0xFF6CD64F)
    val Red = Color(0xFFFA484E)
    val Bg = Color(0xFF1A1A1A)
    val Surface = Color(0xFF2B2B2B)
    val Field = Color(0xFF202020)
    val OnPrimary = Color(0xFFF0F0F0)
    val Text = Color(0xFFEDEDED)
    val TextBody = Color(0xFFDADADA)
    val TextMuted = Color(0xFFBEC6DC)
    val Hairline = Color(0x22FFFFFF)
}

/** Inter font family loaded from bundled assets (cached). */
object PortalFont {
    @Volatile private var family: FontFamily? = null

    fun inter(context: Context): FontFamily {
        family?.let { return it }
        val am = context.assets
        val f = try {
            FontFamily(
                Font("fonts/inter_regular.ttf", am, weight = FontWeight.Normal),
                Font("fonts/inter_medium.ttf", am, weight = FontWeight.Medium),
                Font("fonts/inter_bold.ttf", am, weight = FontWeight.Bold),
            )
        } catch (t: Throwable) {
            FontFamily.SansSerif
        }
        family = f
        return f
    }
}

@Composable
fun rememberInterTypography(context: Context): Typography {
    val inter = PortalFont.inter(context)
    val base = Typography()
    return base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = inter, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = inter, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = inter, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = inter, fontWeight = FontWeight.Medium),
    )
}
