package fr.mairie.badgeuse.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.mairie.badgeuse.donnees.AgentAffiche
import fr.mairie.badgeuse.domaine.DELAI_ANNULATION
import fr.mairie.badgeuse.domaine.EtatAgent
import fr.mairie.badgeuse.domaine.Pointage
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.domaine.actionsAutorisees
import fr.mairie.badgeuse.ui.theme.Orange
import fr.mairie.badgeuse.ui.theme.VertSucces
import fr.mairie.badgeuse.viewmodel.Ecran
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
fun EcranAccueil(agents: List<AgentAffiche>, onChoix: (AgentAffiche) -> Unit, onAdmin: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        EnTete("Qui êtes-vous ?")
        if (agents.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Aucun agent n'est enregistré.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(agents, key = { _, agent -> agent.id }) { index, agent ->
                    Apparition(index) { CarteAgent(agent) { onChoix(agent) } }
                }
            }
        }
        TextButton(onClick = onAdmin, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Administration", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun CarteAgent(agent: AgentAffiche, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val appui = echelleAppui(source, 0.96f)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(110.dp).echelle(appui),
        interactionSource = source,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(agent.prenom, agent.nom, agent.pole, 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${agent.prenom} ${agent.nom}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    agent.pole, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/**
 * Sous cette hauteur utile, on rapetisse touches et boutons : un téléphone en paysage n'offre
 * qu'environ 300 dp sous l'en-tête, un pavé de 4 lignes à 64 dp n'y tient pas.
 */
internal val HAUTEUR_COMPACTE = 400.dp

@Composable
fun EcranCode(ecran: Ecran.Code, onCode: (String) -> Unit, onRetour: () -> Unit) {
    // `essai` sert de clé : quand le code est refusé, les chiffres tapés repartent à zéro.
    var chiffres by remember(ecran.essai) { mutableStateOf("") }
    val saisir = { c: Char ->
        if (chiffres.length < 4) {
            chiffres += c
            if (chiffres.length == 4) onCode(chiffres)
        }
    }
    val effacer = { chiffres = chiffres.dropLast(1) }

    // Code refusé : la consigne tremble de gauche à droite, comme un « non » de la tête.
    val tremblement = remember { Animatable(0f) }
    LaunchedEffect(ecran.essai) {
        if (ecran.essai > 0) {
            tremblement.snapTo(0f)
            tremblement.animateTo(
                0f,
                keyframes {
                    durationMillis = 420
                    -18f at 50
                    16f at 120
                    -12f at 190
                    8f at 260
                    -4f at 330
                },
            )
        }
    }
    val consigne = Modifier.graphicsLayer { translationX = tremblement.value.dp.toPx() }

    Column(Modifier.fillMaxSize()) {
        EnTete("Bonjour ${ecran.agent.prenom}")
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = maxHeight < HAUTEUR_COMPACTE
            if (maxWidth > maxHeight) {
                // Paysage : la consigne à gauche, le pavé à droite, pour que le pavé garde toute la hauteur.
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Consigne(chiffres, ecran.message, consigne)
                        BoutonSecondaire("Retour", onRetour)
                    }
                    // Défilement en dernier recours : aucune touche ne doit jamais être hors d'atteinte.
                    Column(Modifier.verticalScroll(rememberScrollState())) { Pave(compact, saisir, effacer) }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Consigne(chiffres, ecran.message, consigne)
                    Pave(compact, saisir, effacer)
                    BoutonSecondaire("Retour", onRetour)
                }
            }
        }
    }
}

/** Le texte d'invite, les 4 points qui se remplissent et le message d'erreur éventuel. */
@Composable
private fun Consigne(chiffres: String, message: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Saisissez votre code à 4 chiffres", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i -> PointCode(rempli = i < chiffres.length) }
        }
        BandeauMessage(message, Ton.ERREUR, Modifier.widthIn(max = 480.dp))
    }
}

/** Un point du code : un anneau vide, qui se remplit avec un petit rebond quand on tape un chiffre. */
@Composable
private fun PointCode(rempli: Boolean) {
    val remplissage = animateFloatAsState(
        if (rempli) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "point",
    )
    val anneau by animateColorAsState(
        if (rempli) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, tween(DUREE_COURTE), label = "anneau",
    )
    Box(Modifier.size(24.dp).border(2.dp, anneau, CircleShape), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = remplissage.value
                    scaleY = remplissage.value
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun Pave(compact: Boolean, onChiffre: (Char) -> Unit, onEffacer: () -> Unit) {
    val hauteur = if (compact) 52.dp else 68.dp
    val lignes = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lignes.forEach { ligne ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ligne.forEach { c -> Touche(c.toString(), hauteur) { onChiffre(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(width = 112.dp, height = hauteur))
            Touche("0", hauteur) { onChiffre('0') }
            Touche("⌫", hauteur, description = "Effacer", secondaire = true, onClick = onEffacer)
        }
    }
}

@Composable
private fun Touche(
    texte: String, hauteur: Dp, description: String? = null, secondaire: Boolean = false, onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val appui = echelleAppui(source, 0.9f)
    val couleurs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = Modifier
            .size(width = 112.dp, height = hauteur)
            .echelle(appui)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        interactionSource = source,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondaire) couleurs.surfaceContainerHigh else couleurs.primaryContainer,
            contentColor = if (secondaire) couleurs.onSurface else couleurs.onPrimaryContainer,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Text(
            texte,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            // Le symbole ⌫ n'est pas lu correctement : c'est la description « Effacer » qui est annoncée.
            modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

@Composable
private fun BoutonSecondaire(texte: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(56.dp).widthIn(min = 160.dp)) { Text(texte) }
}

@Composable
fun EcranEspace(
    ecran: Ecran.Espace,
    onPointer: (TypePointage) -> Unit,
    onAnnuler: () -> Unit,
    onQuitter: () -> Unit,
    onVoirTout: () -> Unit,
    onFermerTout: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        EnTete("Bonjour ${ecran.agent.prenom}")
        val tous = ecran.tousLesPointages
        if (tous != null) {
            TousMesPointages(tous, onFermerTout, Modifier.weight(1f))
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = maxHeight < HAUTEUR_COMPACTE
            if (maxWidth > maxHeight) {
                // Paysage : les actions à gauche, l'historique à droite. Empilés, l'historique serait écrasé.
                Row(
                    Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    ActionsPointage(ecran, onPointer, onAnnuler, compact, Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Historique(ecran.historique, onVoirTout, Modifier.weight(1f))
                        BoutonQuitter(onQuitter)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ActionsPointage(ecran, onPointer, onAnnuler, compact, Modifier)
                    Historique(ecran.historique, onVoirTout, Modifier.weight(1f))
                    BoutonQuitter(onQuitter)
                }
            }
        }
    }
}

/** L'état de l'agent, les 4 boutons de pointage, la confirmation avec son bouton Annuler et les messages. */
@Composable
private fun ActionsPointage(
    ecran: Ecran.Espace,
    onPointer: (TypePointage) -> Unit,
    onAnnuler: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PastilleEtat(ecran.etat)

        val permises = actionsAutorisees(ecran.etat)
        // Grille 2 x 2 : reste lisible sur une tablette comme sur un écran étroit.
        TypePointage.entries.chunked(2).forEach { ligne ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ligne.forEach { type ->
                    BoutonPointage(type, type in permises, compact, Modifier.weight(1f)) { onPointer(type) }
                }
            }
        }

        AnimatedContent(
            targetState = ecran.confirmation,
            // Clé = le pointage : le décompte qui avance ne relance pas l'animation d'entrée.
            contentKey = { it?.id },
            transitionSpec = {
                (fadeIn(tween(DUREE_MOYENNE)) + slideInVertically(tween(DUREE_MOYENNE)) { it / 3 } +
                    scaleIn(tween(DUREE_MOYENNE), initialScale = 0.94f)) togetherWith fadeOut(tween(DUREE_COURTE)) using
                    SizeTransform(clip = false)
            },
            label = "confirmation",
        ) { confirmation ->
            if (confirmation != null) {
                Confirmation("${ecran.agent.prenom} ${ecran.agent.nom}", confirmation.type.libelle(), confirmation.horodatage, onAnnuler)
            }
        }
        BandeauMessage(ecran.message, Ton.INFO)
    }
}

@Composable
private fun PastilleEtat(etat: EtatAgent) {
    AnimatedContent(
        targetState = etat,
        transitionSpec = { fadeIn(tween(DUREE_MOYENNE)) togetherWith fadeOut(tween(DUREE_COURTE)) },
        label = "etat",
    ) { e ->
        val couleurs = MaterialTheme.colorScheme
        when (e) {
            EtatAgent.HORS_SERVICE -> Pastille(e.libelle(), couleurs.surfaceContainerHigh, couleurs.onSurface, couleurs.outline, grand = true)
            EtatAgent.EN_SERVICE -> Pastille(e.libelle(), couleurs.secondaryContainer, couleurs.onSecondaryContainer, VertSucces, grand = true)
            EtatAgent.EN_PAUSE -> Pastille(e.libelle(), couleurs.tertiaryContainer, couleurs.onTertiaryContainer, Orange, grand = true)
        }
    }
}

/**
 * Bouton de pointage à la couleur de son type. Quand il devient permis ou interdit (après un
 * pointage), sa couleur glisse vers l'autre état au lieu de changer d'un coup.
 */
@Composable
private fun BoutonPointage(type: TypePointage, permis: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (fondType, encreType) = couleursType(type)
    val fond by animateColorAsState(
        if (permis) fondType else MaterialTheme.colorScheme.surfaceContainer, tween(DUREE_MOYENNE), label = "fond",
    )
    val encre by animateColorAsState(
        if (permis) encreType else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f), tween(DUREE_MOYENNE), label = "encre",
    )
    val source = remember { MutableInteractionSource() }
    val appui = echelleAppui(source, 0.95f)
    Button(
        onClick = onClick,
        enabled = permis,
        modifier = modifier.height(if (compact) 56.dp else 84.dp).echelle(appui),
        interactionSource = source,
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = fond, contentColor = encre, disabledContainerColor = fond, disabledContentColor = encre,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (permis) 2.dp else 0.dp, pressedElevation = 0.dp),
    ) { Text(type.libelle(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
}

/** Aperçu des derniers pointages, avec l'accès à la liste complète (EF-07). */
@Composable
private fun Historique(historique: List<Pointage>, onVoirTout: () -> Unit, modifier: Modifier) {
    CarteListe(modifier) {
        Text("Mes derniers pointages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // Une liste garde sa position sur l'élément qu'elle montrait : un nouveau pointage, inséré en
        // tête, resterait caché au-dessus. On remonte donc en haut dès que le plus récent change.
        val defilement = rememberLazyListState()
        LaunchedEffect(historique.firstOrNull()?.id) { defilement.scrollToItem(0) }
        LazyColumn(Modifier.weight(1f), state = defilement) {
            items(historique, key = { it.id }) { p -> LigneHistorique(p, Modifier.animateItem()) }
        }
        if (historique.isNotEmpty()) {
            TextButton(onClick = onVoirTout, modifier = Modifier.align(Alignment.End)) { Text("Voir tous mes pointages") }
        }
    }
}

/** Tous les pointages de l'agent, regroupés par jour, du plus récent au plus ancien. */
@Composable
private fun TousMesPointages(pointages: List<Pointage>, onFermer: () -> Unit, modifier: Modifier) {
    val parJour = remember(pointages) { pointages.groupBy { it.horodatage.jourComplet() } }
    Column(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tous mes pointages (${pointages.size})", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            BoutonSecondaire("Fermer", onFermer)
        }
        CarteListe(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f)) {
                parJour.forEach { (jour, duJour) ->
                    item(key = "jour-$jour") {
                        Text(
                            jour, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(duJour, key = { it.id }) { p -> LigneHistorique(p, Modifier) }
                }
            }
        }
        Text(
            "Les pointages plus anciens que la durée de conservation ne sont plus sur la tablette : la DGS les a dans ses sauvegardes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun CarteListe(modifier: Modifier, contenu: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = contenu)
    }
}

@Composable
private fun LigneHistorique(p: Pointage, modifier: Modifier) {
    val annule = p.statut == StatutPointage.ANNULE
    val attenuation by animateFloatAsState(if (annule) 0.5f else 1f, tween(DUREE_MOYENNE), label = "annule")
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).graphicsLayer { alpha = attenuation }.clip(CircleShape).background(couleursType(p.type).first))
        Row(Modifier.weight(1f).graphicsLayer { alpha = attenuation }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val barre = if (annule) TextDecoration.LineThrough else TextDecoration.None
            Text(p.horodatage.jour(), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), textDecoration = barre)
            Text(p.horodatage.heure(), fontWeight = FontWeight.Bold, textDecoration = barre)
            Text(p.type.libelle(), textDecoration = barre)
        }
        if (annule) Pastille("Annulé", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun BoutonQuitter(onQuitter: () -> Unit) {
    BoutonSecondaire("Quitter", onQuitter, Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Confirmation(agent: String, libelle: String, horodatage: Instant, onAnnuler: () -> Unit) {
    // Décompte affiché ; la vraie règle des 15 s est appliquée par le dépôt, pas par ce texte.
    val maintenant by produceState(Instant.now()) {
        while (true) { value = Instant.now(); delay(500) }
    }
    val ecoule = Duration.between(horodatage, maintenant)
    val restant = (DELAI_ANNULATION - ecoule).seconds.coerceAtLeast(0)
    val fraction = 1f - ecoule.toMillis().toFloat() / DELAI_ANNULATION.toMillis()
    // La barre glisse en continu entre deux relevés de l'heure, au lieu d'avancer par à-coups.
    val barre = animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500, easing = LinearEasing), label = "decompte")
    val couleurBarre = MaterialTheme.colorScheme.secondary

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CocheAnimee(fond = VertSucces, trait = Color.White, modifier = Modifier.size(44.dp))
            // EF-06 : nom, type et heure. « Pointage enregistré » évite l'accord avec chaque type (« Début de pause »).
            Text(
                "Pointage enregistré : $agent, $libelle à ${horodatage.heure()}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onAnnuler, enabled = ecoule <= DELAI_ANNULATION) { Text("Annuler ($restant)") }
        }
        Box(
            Modifier.fillMaxWidth().height(4.dp).drawBehind {
                drawRect(couleurBarre, size = Size(size.width * barre.value, size.height))
            },
        )
    }
}
