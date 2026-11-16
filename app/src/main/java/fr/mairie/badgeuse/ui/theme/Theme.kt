package fr.mairie.badgeuse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Thème clair uniquement et sans couleurs dynamiques : l'identité de la mairie doit rester la même
// quelle que soit la tablette. Les rôles « container » donnent les fonds teintés (cartes, pastilles).
private val Couleurs = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealClair,
    onPrimaryContainer = TealFonce,
    secondary = VertSucces,
    onSecondary = Color.White,
    secondaryContainer = VertClair,
    onSecondaryContainer = VertSuccesFonce,
    tertiary = Orange,
    onTertiary = BleuNuit,
    tertiaryContainer = OrangeClair,
    onTertiaryContainer = OrangeFonce,
    background = Color.White,
    onBackground = BleuNuit,
    surface = Color.White,
    onSurface = BleuNuit,
    surfaceVariant = FondBleute,
    onSurfaceVariant = BleuNuit,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = FondTresClair,
    surfaceContainer = FondBleute,
    surfaceContainerHigh = BleuClair,
    outline = Contour,
    outlineVariant = Contour,
    error = Erreur,
    onError = Color.White,
    errorContainer = ErreurClair,
    onErrorContainer = Erreur,
)

// Coins plus arrondis que Material par défaut : plus doux à l'œil et cohérent avec la pastille du logo.
private val Formes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BadgeuseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Couleurs, typography = Typography, shapes = Formes, content = content)
}
