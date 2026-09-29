package io.github.olbartek.agentctl.examples.agentshop.app.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens from the Figma "Authentication flow UI" file (colors, type, metrics), as the reference's
 * `DesignSystem/Theme.swift` has them. Names follow the file's styles where it has them.
 */
object Palette {
    /** Buttons and links. `#1443C3` */
    val brand = Color(0xFF1443C3)

    /** Titles and body text ("Neutral / 800"). `#191D23` */
    val textPrimary = Color(0xFF191D23)

    /** Strong text on light surfaces ("Text/Black"). `#131212` */
    val textBlack = Color(0xFF131212)

    /** Secondary text ("BG/Grey"). `#6C6F72` */
    val textSecondary = Color(0xFF6C6F72)

    /** Muted helper text ("foreground/muted"). `#77707F` */
    val textMuted = Color(0xFF77707F)

    /** Divider captions ("Neutral / 600"). `#4B5768` */
    val textSubtle = Color(0xFF4B5768)

    /** Field labels: black at 75 %. */
    val label = Color.Black.copy(alpha = 0.75f)

    /** Placeholders. `#BABABA` */
    val placeholder = Color(0xFFBABABA)

    /** Field borders and dividers. `#CBD2E0` */
    val border = Color(0xFFCBD2E0)

    /** Tinted surfaces: the Google button and the back button. `#F4F7FF` */
    val surfaceTint = Color(0xFFF4F7FF)

    /** Error text and icons. `#EA2A2A` */
    val error = Color(0xFFEA2A2A)

    /** A checked checkbox. `#59CDBE` */
    val checkboxOn = Color(0xFF59CDBE)

    /** Text on the brand color ("BG/white"). `#FEFEFE` */
    val onBrand = Color(0xFFFEFEFE)

    /** Screen background. */
    val background = Color.White
}

/** Type styles from the design. The reference's SF Pro Display is the system font; here it is Roboto. */
object Typography {
    val screenTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.textPrimary)
    val pageTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.72.sp, color = Palette.textPrimary)
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.textBlack)
    val navTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.textBlack)
    val body = TextStyle(fontSize = 14.sp, color = Palette.textPrimary)
    val bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.textPrimary)
    val input = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.textPrimary)
    val button = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.onBrand)
    val sectionLabel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.textPrimary)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.textBlack)
    val caption = TextStyle(fontSize = 12.sp, color = Palette.textPrimary)
    val link = TextStyle(fontSize = 16.sp, color = Palette.brand)
}

/** Spacing and sizes from the design (a 428 pt wide frame with a 372 pt content column). */
object Metrics {
    val screenPadding = 28.dp
    val fieldHeight = 44.dp
    val fieldRadius = 6.dp
    val buttonHeight = 50.dp
    val socialButtonHeight = 51.dp
    val socialButtonRadius = 7.88.dp

    /** Between sections (social sign-in, the form, the footer). */
    val sectionSpacing = 40.dp

    /** Between fields. */
    val fieldSpacing = 24.dp
}

/** Material 3 in the design's colors, light only, as the reference's screens force the light scheme. */
@Composable
fun AgentShopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.brand,
            onPrimary = Palette.onBrand,
            secondary = Palette.brand,
            background = Palette.background,
            onBackground = Palette.textPrimary,
            surface = Palette.background,
            onSurface = Palette.textPrimary,
            surfaceContainer = Palette.surfaceTint,
            secondaryContainer = Palette.surfaceTint,
            error = Palette.error,
        ),
        content = content,
    )
}
