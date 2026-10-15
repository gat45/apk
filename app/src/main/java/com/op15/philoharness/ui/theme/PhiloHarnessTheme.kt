package com.op15.philoharness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Theme Compose/Material3 -- migration demandee explicitement ("l'interface de chat
 * ressemble pas a tes autres apk OP15") pour rapprocher le style de GovernorActivity.kt
 * (op15-unified), qui utilise deja Compose/Material3 (darkColorScheme/lightColorScheme,
 * meme pattern que ci-dessous). Valeurs HEX reprises EXACTEMENT de colors.xml/values-night
 * -- deja verifiees WCAG 2.2 reel (contrast.py, pas une estimation visuelle, cf. commentaire
 * d'origine dans values-night/colors.xml) -- aucune nouvelle palette inventee.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFBDBDBD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5C6BC0),
    onPrimary = Color.White,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF3A3A3A),
)

/** Couleurs de statut epistemique -- pas de slot dedie dans ColorScheme Material3, constantes a part (meme approche que GovernorActivity.stateColor()). */
object PhiloStatusColors {
    val supportedLight = Color(0xFF388E3C)
    val supportedDark = Color(0xFF81C784)
    val refutedLight = Color(0xFFD32F2F)
    val refutedDark = Color(0xFFE57373)
    val pendingLight = Color(0xFFF57C00)
    val pendingDark = Color(0xFFFFB74D)
}

@Composable
fun PhiloHarnessTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
