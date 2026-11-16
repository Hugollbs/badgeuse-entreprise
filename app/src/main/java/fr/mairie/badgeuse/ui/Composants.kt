package fr.mairie.badgeuse.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.mairie.badgeuse.R
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.ui.theme.BleuClair
import fr.mairie.badgeuse.ui.theme.BleuNuit
import fr.mairie.badgeuse.ui.theme.Orange
import fr.mairie.badgeuse.ui.theme.OrangeClair
import fr.mairie.badgeuse.ui.theme.OrangeFonce
import fr.mairie.badgeuse.ui.theme.PommeClair
import fr.mairie.badgeuse.ui.theme.Teal
import fr.mairie.badgeuse.ui.theme.TealClair
import fr.mairie.badgeuse.ui.theme.TealFonce
import fr.mairie.badgeuse.ui.theme.VertClair
import fr.mairie.badgeuse.ui.theme.VertLogo
import fr.mairie.badgeuse.ui.theme.VertPomme
import fr.mairie.badgeuse.ui.theme.VertSucces
import fr.mairie.badgeuse.ui.theme.VertSuccesFonce
import kotlinx.coroutines.delay

// Durées courtes : la borne doit rester vive même sur une tablette d'entrée de gamme. Toutes les
// animations Compose suivent aussi le réglage « échelle d'animation » d'Android (0 = aucune animation).
const val DUREE_COURTE = 180
const val DUREE_MOYENNE = 300

/** Le liseré aux couleurs du logo, sous l'en-tête. */
private val LISERE_LOGO = Brush.horizontalGradient(listOf(Teal, VertLogo, VertPomme, Orange))

@Composable
fun EnTete(titre: String) {
    // Fond blanc : le logo a lui-même un fond blanc, un en-tête teinté le ferait apparaître comme une boîte.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.logo_mairie),
                contentDescription = "Mairie d'Exemple-sur-Loire",
                modifier = Modifier.height(56.dp),
            )
            Spacer(Modifier.width(20.dp))
            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(VertLogo))
            Spacer(Modifier.width(20.dp))
            Text(titre, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(LISERE_LOGO))
    }
}

/**
 * Échelle à appliquer à un élément pendant qu'on appuie dessus : un léger enfoncement confirme le
 * toucher avant même la réponse de l'application. On renvoie un State plutôt qu'un Modifier pour
 * le lire dans `graphicsLayer` : seule la couche est redessinée, sans recomposer le bouton.
 */
@Composable
fun echelleAppui(source: InteractionSource, enfoncement: Float = 0.95f): State<Float> {
    val appuye by source.collectIsPressedAsState()
    return animateFloatAsState(
        if (appuye) enfoncement else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "appui",
    )
}

fun Modifier.echelle(etat: State<Float>): Modifier = graphicsLayer {
    scaleX = etat.value
    scaleY = etat.value
}

/**
 * Bloque les touchers, pour l'écran qui est en train de sortir pendant une transition : sans cela,
 * un deuxième appui rapide atteindrait encore l'ancien écran.
 */
fun Modifier.bloquerToucher(actif: Boolean): Modifier = if (!actif) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * Fait apparaître un élément de liste en glissant vers le haut, avec un léger retard selon sa
 * position : les cartes arrivent en cascade. Le retard est plafonné pour qu'une longue liste
 * ne fasse pas attendre.
 */
@Composable
fun Apparition(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progres = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 40L)
        progres.animateTo(1f, tween(DUREE_MOYENNE + 40, easing = FastOutSlowInEasing))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progres.value
            translationY = (1f - progres.value) * 28.dp.toPx()
        },
    ) { content() }
}

enum class Ton { INFO, ERREUR, SUCCES }

/** Message en bandeau coloré, qui apparaît et disparaît en fondu au lieu de faire sauter la page. */
@Composable
fun BandeauMessage(texte: String?, ton: Ton, modifier: Modifier = Modifier) {
    val couleurs = MaterialTheme.colorScheme
    val (fond, encre, accent) = when (ton) {
        Ton.INFO -> Triple(couleurs.primaryContainer, couleurs.onPrimaryContainer, couleurs.primary)
        Ton.ERREUR -> Triple(couleurs.errorContainer, couleurs.onErrorContainer, couleurs.error)
        Ton.SUCCES -> Triple(couleurs.secondaryContainer, couleurs.onSecondaryContainer, couleurs.secondary)
    }
    AnimatedContent(
        targetState = texte,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(DUREE_MOYENNE)) togetherWith fadeOut(tween(DUREE_COURTE)) using
                SizeTransform(clip = false) { _, _ -> tween(DUREE_MOYENNE, easing = FastOutSlowInEasing) }
        },
        label = "bandeau",
    ) { t ->
        if (t != null) {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = fond, contentColor = encre) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(12.dp))
                    Text(t, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Petite étiquette arrondie (état, « annulé », « bloqué »...), avec un point de couleur optionnel. */
@Composable
fun Pastille(texte: String, fond: Color, encre: Color, point: Color? = null, grand: Boolean = false) {
    Surface(shape = CircleShape, color = fond, contentColor = encre) {
        Row(
            Modifier.padding(horizontal = if (grand) 18.dp else 12.dp, vertical = if (grand) 10.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (point != null) {
                Box(Modifier.size(if (grand) 12.dp else 8.dp).clip(CircleShape).background(point))
                Spacer(Modifier.width(if (grand) 12.dp else 8.dp))
            }
            Text(texte, style = if (grand) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
        }
    }
}

/** Bloc de l'espace administrateur : un titre et son contenu dans une carte claire. */
@Composable
fun SectionCarte(titre: String, modifier: Modifier = Modifier, contenu: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(titre, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            contenu()
        }
    }
}

// Une teinte par pôle, toujours la même pour un même nom : les agents d'un pôle se repèrent d'un coup d'œil.
private val TEINTES_POLE = listOf(
    TealClair to TealFonce,
    VertClair to VertSuccesFonce,
    OrangeClair to OrangeFonce,
    PommeClair to Color(0xFF3B4A10),
    BleuClair to BleuNuit,
)

fun teintePole(pole: String): Pair<Color, Color> =
    TEINTES_POLE[Math.floorMod(pole.trim().lowercase().hashCode(), TEINTES_POLE.size)]

fun initiales(prenom: String, nom: String): String =
    listOfNotNull(prenom.trim().firstOrNull(), nom.trim().firstOrNull()).joinToString("").uppercase()

/** Rond aux initiales de l'agent. Purement décoratif : le lecteur d'écran lit déjà le nom à côté. */
@Composable
fun Avatar(prenom: String, nom: String, pole: String, taille: Dp = 56.dp) {
    val (fond, encre) = teintePole(pole)
    Box(
        Modifier.size(taille).clip(CircleShape).background(fond).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(initiales(prenom, nom), color = encre, fontWeight = FontWeight.Bold, fontSize = (taille.value * 0.36f).sp)
    }
}

/** Couleur de fond et couleur du texte associées à chaque type de pointage. */
fun couleursType(type: TypePointage): Pair<Color, Color> = when (type) {
    TypePointage.ENTREE -> VertSucces to Color.White
    TypePointage.DEBUT_PAUSE -> Orange to BleuNuit
    TypePointage.FIN_PAUSE -> VertPomme to BleuNuit
    TypePointage.SORTIE -> BleuNuit to Color.White
}

/** Coche qui se dessine trait par trait dans un rond, pour confirmer qu'un pointage est enregistré. */
@Composable
fun CocheAnimee(fond: Color, trait: Color, modifier: Modifier = Modifier) {
    val progres = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progres.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    val chemin = remember { Path() }
    val segment = remember { Path() }
    val mesure = remember { PathMeasure() }
    Canvas(modifier.clearAndSetSemantics {}) {
        val p = progres.value
        val rayon = size.minDimension / 2
        // Le rond grossit pendant le premier tiers, la coche se trace ensuite.
        drawCircle(fond, rayon * (0.55f + 0.45f * (p * 3f).coerceAtMost(1f)))
        chemin.reset()
        chemin.moveTo(size.width * 0.29f, size.height * 0.52f)
        chemin.lineTo(size.width * 0.44f, size.height * 0.67f)
        chemin.lineTo(size.width * 0.72f, size.height * 0.37f)
        mesure.setPath(chemin, false)
        segment.reset()
        val trace = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
        mesure.getSegment(0f, mesure.length * trace, segment, true)
        drawPath(segment, trait, style = Stroke(width = rayon * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
