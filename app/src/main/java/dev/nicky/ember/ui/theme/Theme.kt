package dev.nicky.ember.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFFB0532C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCD),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF5E7264),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE9DF),
    onSecondaryContainer = Color(0xFF1A2B1F),
    tertiary = Color(0xFF8A5A00),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF221A16),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF221A16),
    surfaceVariant = Color(0xFFF4DED5),
    onSurfaceVariant = Color(0xFF52443D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE2),
    surfaceContainerHigh = Color(0xFFF6E4DC),
    surfaceContainerHighest = Color(0xFFF0DFD7),
    outline = Color(0xFF85736B),
    outlineVariant = Color(0xFFD8C2B9),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB597),
    onPrimary = Color(0xFF5A1B00),
    primaryContainer = Color(0xFF8B3A17),
    onPrimaryContainer = Color(0xFFFFDBCD),
    secondary = Color(0xFFB4CCB9),
    onSecondary = Color(0xFF203527),
    secondaryContainer = Color(0xFF364B3C),
    onSecondaryContainer = Color(0xFFD0E8D5),
    tertiary = Color(0xFFFFB955),
    tertiaryContainer = Color(0xFF684300),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF1A120F),
    onBackground = Color(0xFFF1DFD8),
    surface = Color(0xFF1A120F),
    onSurface = Color(0xFFF1DFD8),
    surfaceVariant = Color(0xFF52443D),
    onSurfaceVariant = Color(0xFFD8C2B9),
    surfaceContainerLowest = Color(0xFF140D0A),
    surfaceContainerLow = Color(0xFF231A16),
    surfaceContainer = Color(0xFF271E1A),
    surfaceContainerHigh = Color(0xFF322824),
    surfaceContainerHighest = Color(0xFF3D332E),
    outline = Color(0xFFA08D85),
    outlineVariant = Color(0xFF52443D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val EmberTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val EmberShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun EmberTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = EmberTypography,
        shapes = EmberShapes,
        content = content,
    )
}
