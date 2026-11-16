package fr.mairie.badgeuse.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.mairie.badgeuse.donnees.AgentAdmin
import fr.mairie.badgeuse.donnees.PointageAdmin
import fr.mairie.badgeuse.donnees.SauvegardeEntite
import fr.mairie.badgeuse.domaine.ConfigurationEmail
import fr.mairie.badgeuse.domaine.FrequenceSauvegarde
import fr.mairie.badgeuse.domaine.SecuriteSmtp
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypeAnomalie
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.domaine.DUREE_CONSERVATION_MAX_MOIS
import fr.mairie.badgeuse.domaine.DUREE_CONSERVATION_MIN_MOIS
import fr.mairie.badgeuse.domaine.formaterDuree
import fr.mairie.badgeuse.viewmodel.TempsAgent
import org.json.JSONException
import org.json.JSONObject
import fr.mairie.badgeuse.ui.theme.Orange
import fr.mairie.badgeuse.ui.theme.VertSucces
import fr.mairie.badgeuse.viewmodel.AdminViewModel
import fr.mairie.badgeuse.viewmodel.EcranAdmin
import fr.mairie.badgeuse.viewmodel.Onglet
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FORMAT_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)

@Composable
fun HoteAdmin(vm: AdminViewModel, ecran: EcranAdmin, onQuitter: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sur un écran bas (téléphone en paysage), le grand en-tête avec logo laisse une seule ligne
        // aux listes : dans l'espace connecté, on le retire. Les formulaires défilent, ils le gardent.
        val masquerEnTete = ecran is EcranAdmin.Espace && maxHeight < HAUTEUR_COMPACTE
        Column(Modifier.fillMaxSize()) {
            if (!masquerEnTete) EnTete("Administration")
            // Fondu entre les étapes (connexion, espace...) ; la clé est l'étape, pas son contenu,
            // pour qu'une mise à jour des listes ne relance pas l'animation.
            AnimatedContent(
                targetState = ecran,
                modifier = Modifier.weight(1f),
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(DUREE_MOYENNE)) togetherWith fadeOut(tween(DUREE_COURTE)) },
                label = "etapesAdmin",
            ) { etape ->
                when (etape) {
                    EcranAdmin.Chargement -> Box(Modifier.fillMaxSize())
                    is EcranAdmin.Configuration -> FormulaireConfiguration(etape, vm::configurer, onQuitter)
                    is EcranAdmin.CodeSecoursAffiche -> AffichageCodeSecours(etape.code, vm::codeSecoursNote)
                    is EcranAdmin.Connexion -> FormulaireConnexion(etape, vm::connecter, vm::ouvrirReinitialisation, onQuitter)
                    is EcranAdmin.Reinitialisation -> FormulaireReinitialisation(etape, vm::reinitialiser, vm::annulerReinitialisation)
                    is EcranAdmin.Espace -> EspaceAdmin(vm, etape, onQuitter)
                }
            }
        }
    }
}

@Composable
private fun Champ(
    libelle: String, valeur: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, secret: Boolean = false, chiffres: Boolean = false,
) {
    // La saisie au clavier n'est pas un toucher de l'écran : on la signale au minuteur d'inactivité.
    val activite = LocalActivite.current
    OutlinedTextField(
        value = valeur, onValueChange = { activite(); onChange(it) }, label = { Text(libelle) }, singleLine = true, modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                secret -> KeyboardType.Password
                chiffres -> KeyboardType.NumberPassword
                else -> KeyboardType.Text
            },
        ),
    )
}

@Composable
private fun Message(texte: String?) {
    BandeauMessage(texte, Ton.ERREUR)
}

/** Les formulaires (configuration, connexion...) : une carte centrée, pas plus large qu'il ne faut sur tablette. */
@Composable
private fun CarteFormulaire(titre: String, contenu: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(titre, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                contenu()
            }
        }
    }
}

@Composable
private fun BoutonPrincipal(texte: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(texte) }
}

@Composable
private fun FormulaireConfiguration(ecran: EcranAdmin.Configuration, onValider: (String, String) -> Unit, onRetour: () -> Unit) {
    var mdp by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    CarteFormulaire("Première configuration") {
        Text("Choisissez le mot de passe de l'espace administrateur (8 caractères minimum). Il doit être connu d'une seule personne.")
        Champ("Mot de passe", mdp, { mdp = it }, secret = true)
        Champ("Confirmation", confirmation, { confirmation = it }, secret = true)
        Message(ecran.message)
        BoutonPrincipal("Enregistrer") { onValider(mdp, confirmation) }
        OutlinedButton(onClick = onRetour, modifier = Modifier.fillMaxWidth()) { Text("Retour") }
    }
}

@Composable
private fun AffichageCodeSecours(code: String, onNote: () -> Unit) {
    CarteFormulaire("Code de secours") {
        Text(
            "Notez ce code sur papier et rangez-le en lieu sûr. Il ne sera plus jamais affiché. " +
                "Il permet de choisir un nouveau mot de passe si vous l'oubliez, mais il ne donne accès à aucune donnée.",
        )
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            // Police à chasse fixe et lettres espacées : on recopie le code sans confondre ni sauter de caractère.
            Text(
                code, Modifier.padding(vertical = 24.dp, horizontal = 12.dp), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, textAlign = TextAlign.Center,
            )
        }
        BoutonPrincipal("J'ai noté le code", onNote)
    }
}

@Composable
private fun FormulaireConnexion(
    ecran: EcranAdmin.Connexion, onConnecter: (String) -> Unit, onPerdu: () -> Unit, onRetour: () -> Unit,
) {
    var mdp by remember(ecran.message) { mutableStateOf("") }
    CarteFormulaire("Mot de passe administrateur") {
        Champ("Mot de passe", mdp, { mdp = it }, secret = true)
        Message(ecran.message)
        BoutonPrincipal("Se connecter") { onConnecter(mdp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onRetour) { Text("Retour") }
            TextButton(onClick = onPerdu) { Text("Mot de passe perdu ?") }
        }
    }
}

@Composable
private fun FormulaireReinitialisation(
    ecran: EcranAdmin.Reinitialisation, onValider: (String, String, String) -> Unit, onRetour: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var mdp by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    CarteFormulaire("Réinitialiser le mot de passe") {
        Champ("Code de secours", code, { code = it })
        Champ("Nouveau mot de passe", mdp, { mdp = it }, secret = true)
        Champ("Confirmation", confirmation, { confirmation = it }, secret = true)
        Message(ecran.message)
        BoutonPrincipal("Réinitialiser") { onValider(code, mdp, confirmation) }
        OutlinedButton(onClick = onRetour, modifier = Modifier.fillMaxWidth()) { Text("Retour") }
    }
}

@Composable
private fun EspaceAdmin(vm: AdminViewModel, e: EcranAdmin.Espace, onQuitter: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Espace administrateur", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onQuitter) { Text("Quitter") }
        }
        // Barre défilante : sur un téléphone, 5 onglets à largeur fixe coupaient les mots en deux.
        PrimaryScrollableTabRow(
            selectedTabIndex = e.onglet.ordinal, containerColor = MaterialTheme.colorScheme.surface, edgePadding = 16.dp,
        ) {
            Onglet.entries.forEach { o ->
                Tab(
                    selected = o == e.onglet, onClick = { vm.choisirOnglet(o) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    text = {
                        Text(
                            when (o) {
                                Onglet.POINTAGES -> "Pointages"
                                Onglet.ANOMALIES -> "Anomalies (${e.anomalies.size})"
                                Onglet.AGENTS -> "Agents"
                                Onglet.JOURNAL -> "Journal"
                                Onglet.EXPORT -> "Export"
                            },
                        )
                    },
                )
            }
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.height(2.dp))
            BandeauMessage(e.message, Ton.INFO)
            // T-32 : un échec d'envoi reste visible sur tous les onglets tant qu'une sauvegarde n'a pas réussi.
            BandeauMessage(
                e.sauvegardes.firstOrNull()?.takeIf { it.statut == "ECHEC" }?.let {
                    "Attention : la dernière sauvegarde par e-mail a échoué (${it.messageErreur ?: "raison inconnue"})."
                },
                Ton.ERREUR,
            )
        }
        // L'onglet choisi glisse depuis le côté où il se trouve dans la barre.
        AnimatedContent(
            targetState = e.onglet,
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            transitionSpec = {
                val sens = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(DUREE_MOYENNE)) { sens * it / 10 } + fadeIn(tween(DUREE_MOYENNE))) togetherWith
                    (slideOutHorizontally(tween(DUREE_MOYENNE)) { -sens * it / 10 } + fadeOut(tween(DUREE_COURTE)))
            },
            label = "onglets",
        ) { onglet ->
            when (onglet) {
                Onglet.POINTAGES -> OngletPointages(vm, e)
                Onglet.ANOMALIES -> OngletAnomalies(vm, e)
                Onglet.AGENTS -> OngletAgents(vm, e)
                Onglet.JOURNAL -> OngletJournal(vm, e)
                Onglet.EXPORT -> OngletExport(vm, e)
            }
        }
    }
}

/** Ligne de liste de l'espace admin : une carte claire, avec un point de couleur à gauche. */
@Composable
private fun LigneCarte(point: Color?, modifier: Modifier = Modifier, contenu: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (point != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(point))
                Spacer(Modifier.width(14.dp))
            }
            contenu()
        }
    }
}

@Composable
private fun TexteVide(texte: String) {
    Text(
        texte, Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

@Composable
private fun OngletPointages(vm: AdminViewModel, e: EcranAdmin.Espace) {
    // On garde l'identifiant (et non l'objet) : il survit à une rotation de l'écran.
    var editionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val edition = e.pointages.find { it.pointage.id == editionId }
    var ajout by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { vm.changerJour(-1) }) { Text("<") }
            // Le jour glisse dans le sens où l'on se déplace dans le calendrier.
            AnimatedContent(
                targetState = e.jour,
                transitionSpec = {
                    val sens = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(DUREE_MOYENNE)) { sens * it / 2 } + fadeIn(tween(DUREE_MOYENNE))) togetherWith
                        (slideOutHorizontally(tween(DUREE_MOYENNE)) { -sens * it / 2 } + fadeOut(tween(DUREE_COURTE)))
                },
                label = "jour",
            ) { jour -> Text(jour.format(FORMAT_DATE), style = MaterialTheme.typography.titleMedium) }
            FilledTonalButton(onClick = { vm.changerJour(1) }, enabled = e.jour < LocalDate.now()) { Text(">") }
        }
        Button(onClick = { ajout = true }) { Text("+ Ajouter un pointage") }
        if (e.pointages.isEmpty()) TexteVide("Aucun pointage ce jour-là.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (e.temps.isNotEmpty()) item(key = "temps") { CarteTempsDuJour(e.temps) }
            items(e.pointages, key = { it.pointage.id }) { p ->
                val annule = p.pointage.statut == StatutPointage.ANNULE
                LigneCarte(couleursType(p.pointage.type).first.copy(alpha = if (annule) 0.35f else 1f), Modifier.animateItem()) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.pointage.horodatage.heure()}  ${p.pointage.type.libelle()}", fontWeight = FontWeight.Bold)
                        val notes = listOfNotNull(
                            if (annule) "annulé" else null,
                            if (p.origine == "ADMIN") "ajouté par l'administrateur" else null,
                        ).joinToString(", ")
                        Text(
                            p.agent + if (notes.isNotEmpty()) " ($notes)" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                    TextButton(onClick = { editionId = p.pointage.id }) { Text("Modifier") }
                }
            }
        }
    }

    if (ajout) {
        DialogPointage(
            titre = "Ajouter un pointage", agents = e.agents.filter { it.actif }, agentInitial = null,
            typeInitial = TypePointage.ENTREE, heureInitiale = "", annuleInitial = null,
            onAnnuler = { ajout = false },
            onValider = { agentId, type, heure, _, motif -> vm.ajouterPointage(agentId, type, heure, motif); ajout = false },
        )
    }
    edition?.let { p ->
        DialogPointage(
            titre = "Modifier le pointage",
            // Réattribution possible à un agent actif (erreur de profil), ou laisser l'agent actuel.
            agents = e.agents.filter { it.actif || it.id == p.pointage.agentId }, agentInitial = p.pointage.agentId,
            typeInitial = p.pointage.type, heureInitiale = p.pointage.horodatage.heure(),
            annuleInitial = p.pointage.statut == StatutPointage.ANNULE,
            onAnnuler = { editionId = null },
            onValider = { agentId, type, heure, annule, motif ->
                vm.modifierPointage(p.pointage.id, agentId, type, heure, annule, motif)
                editionId = null
            },
        )
    }
}

/** Temps de travail du jour par agent (calcul : entrées et sorties, moins les pauses). */
@Composable
private fun CarteTempsDuJour(temps: List<TempsAgent>) {
    SectionCarte("Temps de travail du jour", Modifier.padding(bottom = 6.dp)) {
        temps.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.agent, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(formaterDuree(t.temps.travail), fontWeight = FontWeight.Bold)
                Text(
                    "pause ${formaterDuree(t.temps.pause)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
                if (!t.temps.complet) {
                    Pastille("incomplet", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        if (temps.any { !it.temps.complet }) {
            Text(
                "« incomplet » : une période est restée ouverte (oubli de pointage) ; elle n'est pas comptée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun DialogPointage(
    titre: String, agents: List<AgentAdmin>, agentInitial: Long?, typeInitial: TypePointage, heureInitiale: String,
    /** null pour un ajout ; vrai ou faux pour une modification (le pointage peut être annulé ou rétabli). */
    annuleInitial: Boolean?,
    onAnnuler: () -> Unit, onValider: (Long, TypePointage, String, Boolean, String) -> Unit,
) {
    var agentId by rememberSaveable { mutableStateOf(agentInitial) }
    var type by rememberSaveable { mutableStateOf(typeInitial) }
    var heure by rememberSaveable { mutableStateOf(heureInitiale) }
    var annule by rememberSaveable { mutableStateOf(annuleInitial ?: false) }
    var motif by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        // Une boîte de dialogue est une fenêtre à part : ses touchers n'arrivent pas à la fenêtre principale.
        modifier = Modifier.observerActivite(LocalActivite.current),
        onDismissRequest = onAnnuler,
        title = { Text(titre) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoixAgent(agents, agentId) { agentId = it }
                TypePointage.entries.chunked(2).forEach { ligne ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ligne.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.libelle()) }) }
                    }
                }
                Champ("Heure (HH:mm)", heure, { heure = it })
                if (annuleInitial != null) {
                    FilterChip(
                        selected = annule, onClick = { annule = !annule },
                        label = { Text(if (annule) "Pointage annulé (cliquer pour le rétablir)" else "Annuler ce pointage") },
                    )
                }
                Champ("Motif (conservé au journal)", motif, { motif = it })
            }
        },
        confirmButton = {
            Button(onClick = { agentId?.let { onValider(it, type, heure, annule, motif) } }, enabled = agentId != null) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun ChoixAgent(agents: List<AgentAdmin>, choisi: Long?, onChoix: (Long) -> Unit) {
    var ouvert by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { ouvert = true }) {
            Text(agents.find { it.id == choisi }?.let { "${it.prenom} ${it.nom}" } ?: "Choisir un agent")
        }
        DropdownMenu(
            expanded = ouvert, onDismissRequest = { ouvert = false },
            modifier = Modifier.observerActivite(LocalActivite.current),
        ) {
            agents.forEach { a ->
                DropdownMenuItem(text = { Text("${a.prenom} ${a.nom}") }, onClick = { onChoix(a.id); ouvert = false })
            }
        }
    }
}

@Composable
private fun OngletAnomalies(vm: AdminViewModel, e: EcranAdmin.Espace) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (e.anomalies.isEmpty()) TexteVide("Aucune anomalie à corriger.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(e.anomalies, key = { it.id }) { a ->
                LigneCarte(Orange, Modifier.animateItem()) {
                    Column(Modifier.weight(1f)) {
                        Text(a.agent, fontWeight = FontWeight.Bold)
                        Text(
                            a.jour.format(FORMAT_DATE) + " : " + when (a.type) {
                                TypeAnomalie.SORTIE_MANQUANTE -> "sortie manquante"
                                TypeAnomalie.PAUSE_NON_TERMINEE -> "pause non terminée"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                    TextButton(onClick = { vm.marquerTraitee(a.id) }) { Text("Marquer traitée") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OngletAgents(vm: AdminViewModel, e: EcranAdmin.Espace) {
    var creation by rememberSaveable { mutableStateOf(false) }
    var codeDeId by rememberSaveable { mutableStateOf<Long?>(null) }
    val codeDe = e.agents.find { it.id == codeDeId }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { creation = true }) { Text("+ Nouvel agent") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(e.agents, key = { it.id }) { a ->
                Card(
                    modifier = Modifier.animateItem(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(a.prenom, a.nom, a.pole, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${a.prenom} ${a.nom}", fontWeight = FontWeight.Bold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(a.pole, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                                if (!a.actif) {
                                    Pastille("désactivé", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface)
                                }
                                if (a.bloque) {
                                    Pastille("accès bloqué", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { codeDeId = a.id }) { Text("Changer le code") }
                                if (a.bloque) TextButton(onClick = { vm.debloquer(a.id) }) { Text("Débloquer") }
                                TextButton(onClick = { vm.definirActif(a.id, !a.actif) }) { Text(if (a.actif) "Désactiver" else "Réactiver") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (creation) DialogNouvelAgent({ creation = false }) { n, p, po, c -> vm.creerAgent(n, p, po, c); creation = false }
    codeDe?.let { a -> DialogCode(a, { codeDeId = null }) { vm.changerCode(a.id, it); codeDeId = null } }
}

@Composable
private fun DialogNouvelAgent(onAnnuler: () -> Unit, onValider: (String, String, String, String) -> Unit) {
    var nom by rememberSaveable { mutableStateOf("") }
    var prenom by rememberSaveable { mutableStateOf("") }
    var pole by rememberSaveable { mutableStateOf("") }
    // Le code reste en simple mémoire : un secret n'a pas à être recopié dans l'état sauvegardé de l'écran.
    var code by remember { mutableStateOf("") }
    AlertDialog(
        // Une boîte de dialogue est une fenêtre à part : ses touchers n'arrivent pas à la fenêtre principale.
        modifier = Modifier.observerActivite(LocalActivite.current),
        onDismissRequest = onAnnuler,
        title = { Text("Nouvel agent") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Champ("Nom", nom, { nom = it })
                Champ("Prénom", prenom, { prenom = it })
                Champ("Pôle", pole, { pole = it })
                Champ("Code à 4 chiffres", code, { code = it.take(4) }, chiffres = true)
            }
        },
        confirmButton = { Button(onClick = { onValider(nom, prenom, pole, code) }) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun DialogCode(agent: AgentAdmin, onAnnuler: () -> Unit, onValider: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        // Une boîte de dialogue est une fenêtre à part : ses touchers n'arrivent pas à la fenêtre principale.
        modifier = Modifier.observerActivite(LocalActivite.current),
        onDismissRequest = onAnnuler,
        title = { Text("Nouveau code de ${agent.prenom}") },
        text = { Champ("Code à 4 chiffres", code, { code = it.take(4) }, chiffres = true) },
        confirmButton = { Button(onClick = { onValider(code) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun OngletJournal(vm: AdminViewModel, e: EcranAdmin.Espace) {
    val noms = remember(e.agents) { e.agents.associate { it.id to "${it.prenom} ${it.nom}" } }
    LazyColumn(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Journal des modifications (lecture seule)", style = MaterialTheme.typography.titleMedium) }
        if (e.journal.isEmpty()) item { TexteVide("Aucune modification enregistrée.") }
        items(e.journal) { j ->
            LigneCarte(MaterialTheme.colorScheme.primary) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${j.date.jour()} ${j.date.heure()}  ${libelleAction(j.action)}  (${j.auteur.lowercase()})", fontWeight = FontWeight.Bold)
                    val discret = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    resumerValeur(j.ancienneValeur, noms)?.let { Text("Avant : $it", color = discret) }
                    resumerValeur(j.nouvelleValeur, noms)?.let { Text("Après : $it", color = discret) }
                    j.motif?.let { Text("Motif : $it", color = discret) }
                }
            }
        }
        // La requête a renvoyé autant de lignes que demandé : il en reste peut-être d'autres.
        if (e.journal.size >= e.limiteJournal) {
            item { TextButton(onClick = vm::journalPlus) { Text("Afficher plus") } }
        }
        item {
            Text(
                "Événements de sécurité", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (e.evenements.isEmpty()) item { TexteVide("Aucun événement.") }
        items(e.evenements) { ev ->
            LigneCarte(if (ev.type.endsWith("ECHEC") || ev.type == "BLOCAGE_AGENT") MaterialTheme.colorScheme.error else Orange) {
                Text("${ev.date.jour()} ${ev.date.heure()}  ${libelleEvenement(ev.type, ev.detail, noms)}")
            }
        }
    }
}

private fun libelleAction(action: String) = when (action) {
    "CREATION_ADMIN" -> "Ajout par l'administrateur"
    "MODIFICATION" -> "Modification"
    "ANNULATION" -> "Annulation par l'agent"
    else -> action
}

private fun libelleEvenement(type: String, detail: String?, noms: Map<Long, String>) = when (type) {
    "CONNEXION_ADMIN_REUSSIE" -> "Connexion à l'espace administrateur"
    "CONNEXION_ADMIN_ECHEC" -> "Mot de passe administrateur erroné"
    "CODE_SECOURS_ECHEC" -> "Code de secours erroné"
    "CODE_SECOURS_UTILISE" -> "Code de secours utilisé : mot de passe réinitialisé, nouveau code généré"
    "BLOCAGE_AGENT" -> {
        // Le détail vaut « agent 12 » : on retrouve le nom si l'agent existe encore.
        val id = detail?.removePrefix("agent ")?.trim()?.toLongOrNull()
        "Accès bloqué après 3 codes erronés : ${id?.let { noms[it] } ?: detail.orEmpty()}"
    }
    "CONSERVATION_MODIFIEE" -> "Durée de conservation modifiée : ${detail.orEmpty()}"
    else -> listOfNotNull(type, detail).joinToString(" : ")
}

/** Traduit la valeur JSON d'un pointage écrite au journal (« agent, type, date heure, statut »). */
private fun resumerValeur(json: String?, noms: Map<Long, String>): String? {
    if (json == null) return null
    return try {
        val o = JSONObject(json)
        val quand = Instant.ofEpochMilli(o.getLong("horodatage"))
        listOfNotNull(
            if (o.has("agentId")) o.getLong("agentId").let { noms[it] ?: "agent $it" } else null,
            TypePointage.valueOf(o.getString("type")).libelle(),
            "${quand.jour()} ${quand.heure()}",
            if (o.optString("statut") == StatutPointage.ANNULE.name) "annulé" else null,
        ).joinToString(", ")
    } catch (e: JSONException) {
        json
    } catch (e: IllegalArgumentException) {
        json
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OngletExport(vm: AdminViewModel, e: EcranAdmin.Espace) {
    val resolveur = LocalContext.current.contentResolver
    val selecteur = LocalSelecteurFichier.current
    var agentsChoisis by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var debut by rememberSaveable { mutableStateOf(e.jour.withDayOfMonth(1).toString()) }
    var fin by rememberSaveable { mutableStateOf(e.jour.toString()) }
    var dialogEmail by rememberSaveable { mutableStateOf(false) }
    var dialogRestauration by rememberSaveable { mutableStateOf(false) }
    var motDePasseRestauration by remember { mutableStateOf("") }

    // Le sélecteur de fichiers est une autre application : pendant qu'il est ouvert, le retour
    // automatique à l'accueil est suspendu, et il reprend à son retour (voir LocalSelecteurFichier).
    val creerCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        selecteur(false)
        if (uri != null) vm.exporterCsv(agentsChoisis.toSet(), debut, fin) { resolveur.openOutputStream(uri) }
    }
    val creerPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        selecteur(false)
        if (uri != null) vm.exporterPdf(agentsChoisis.toSet(), debut, fin) { resolveur.openOutputStream(uri) }
    }
    val creerCopie = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        selecteur(false)
        if (uri != null) vm.enregistrerCopie { resolveur.openOutputStream(uri) }
    }
    val ouvrirCopie = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selecteur(false)
        if (uri != null) vm.restaurer(motDePasseRestauration) { resolveur.openInputStream(uri) }
        motDePasseRestauration = ""
    }
    /** Ouvre un sélecteur ; s'il n'existe pas sur la tablette, on le dit au lieu de planter. */
    fun <T> ouvrir(lanceur: androidx.activity.result.ActivityResultLauncher<T>, entree: T) {
        selecteur(true)
        try {
            lanceur.launch(entree)
        } catch (ex: ActivityNotFoundException) {
            selecteur(false)
            vm.signaler("Aucun sélecteur de fichiers n'est disponible sur cette tablette.")
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCarte("Exporter les pointages") {
            ChoixAgents(e.agents, agentsChoisis.toSet()) { agentsChoisis = it.toList() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Champ("Du (AAAA-MM-JJ)", debut, { debut = it }, modifier = Modifier.weight(1f))
                Champ("Au (AAAA-MM-JJ)", fin, { fin = it }, modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ouvrir(creerCsv, nomFichier(debut, fin, "csv")) }, modifier = Modifier.height(56.dp)) { Text("Exporter en CSV") }
                Button(onClick = { ouvrir(creerPdf, nomFichier(debut, fin, "pdf")) }, modifier = Modifier.height(56.dp)) { Text("Exporter en PDF") }
            }
        }

        SectionCarte("Copie et restauration") {
            Text("La copie, chiffrée avec le mot de passe de sauvegarde, contient tous les agents, pointages, journaux et anomalies. La restauration n'est possible que sur une application vide.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ouvrir(creerCopie, "sauvegarde-badgeuse.bdgs") }, modifier = Modifier.height(56.dp)) { Text("Enregistrer une copie") }
                OutlinedButton(onClick = { dialogRestauration = true }, modifier = Modifier.height(56.dp)) { Text("Restaurer une copie") }
            }
        }

        SectionCarte("Sauvegardes par e-mail") {
            val config = e.reglagesEmail
            Text(
                if (config == null) "L'envoi n'est pas configuré : aucune sauvegarde n'est envoyée."
                else "Envoi ${config.frequence.libelle()} vers ${config.destinataire}, par ${config.hote}.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialogEmail = true }, modifier = Modifier.height(56.dp)) { Text("Configurer l'envoi") }
                Button(onClick = { vm.envoyerSauvegardeMaintenant() }, enabled = config != null, modifier = Modifier.height(56.dp)) {
                    Text("Envoyer maintenant")
                }
            }
            if (e.sauvegardes.isEmpty()) Text("Aucune sauvegarde pour l'instant.", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            e.sauvegardes.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (s.statut == "REUSSIE") VertSucces else MaterialTheme.colorScheme.error),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(s.resume())
                }
            }
        }

        SectionConservation(e.conservationMois, vm::enregistrerConservation)

        SectionKiosque()
    }

    if (dialogEmail) {
        DialogEmail(
            actuel = e.reglagesEmail, aMotDePasseSmtp = e.aMotDePasseSmtp, aMotDePasseSauvegarde = e.aMotDePasseSauvegarde,
            onAnnuler = { dialogEmail = false },
            onValider = { config, mdpSmtp, mdpSauvegarde ->
                dialogEmail = false
                vm.enregistrerReglagesEmail(config, mdpSmtp, mdpSauvegarde)
            },
        )
    }
    if (dialogRestauration) {
        DialogMotDePasseRestauration(
            motDePasse = motDePasseRestauration, onChange = { motDePasseRestauration = it },
            onAnnuler = { dialogRestauration = false; motDePasseRestauration = "" },
            onChoisirFichier = {
                dialogRestauration = false
                ouvrir(ouvrirCopie, arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
            },
        )
    }
}

/** Durée pendant laquelle les pointages déjà sauvegardés restent sur la tablette ; réglable par l'administrateur. */
@Composable
private fun SectionConservation(mois: Long, onEnregistrer: (String) -> Unit) {
    var saisie by rememberSaveable(mois) { mutableStateOf(mois.toString()) }
    SectionCarte("Conservation des données") {
        Text(
            "Les pointages déjà sauvegardés par e-mail sont effacés de la tablette après $mois mois " +
                "(avec leurs lignes de journal). Les sauvegardes reçues par la mairie les conservent. " +
                "Un pointage jamais sauvegardé n'est jamais effacé.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Champ(
                "Durée en mois ($DUREE_CONSERVATION_MIN_MOIS à $DUREE_CONSERVATION_MAX_MOIS)", saisie,
                { saisie = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f), chiffres = true,
            )
            OutlinedButton(onClick = { onEnregistrer(saisie) }, modifier = Modifier.height(56.dp)) { Text("Enregistrer") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionKiosque() {
    val kiosque = LocalKiosque.current
    var confirmation by remember { mutableStateOf(false) }
    SectionCarte("Mode kiosque") {
        val couleurs = MaterialTheme.colorScheme
        when {
            !kiosque.proprietaire -> Pastille("Inactif", couleurs.surfaceContainerHigh, couleurs.onSurface, couleurs.outline)
            kiosque.verrouille -> Pastille("Actif", couleurs.secondaryContainer, couleurs.onSecondaryContainer, VertSucces)
            else -> Pastille("En attente", couleurs.tertiaryContainer, couleurs.onTertiaryContainer, Orange)
        }
        Text(
            when {
                !kiosque.proprietaire -> "Inactif : cette tablette n'est pas configurée comme propriétaire de l'appareil (voir INSTALLATION.md)."
                kiosque.verrouille -> "Actif : l'application est verrouillée à l'écran, sans accès au reste de la tablette."
                else -> "Tablette configurée, mais l'écran n'est pas verrouillé pour le moment. Il le sera au prochain démarrage de l'application."
            },
        )
        if (kiosque.proprietaire) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = kiosque.quitterTemporairement, enabled = kiosque.verrouille, modifier = Modifier.height(56.dp)) {
                    Text("Quitter le mode kiosque")
                }
                OutlinedButton(onClick = { confirmation = true }, modifier = Modifier.height(56.dp)) { Text("Retirer définitivement") }
            }
        }
    }
    if (confirmation) {
        AlertDialog(
            modifier = Modifier.observerActivite(LocalActivite.current),
            onDismissRequest = { confirmation = false },
            title = { Text("Retirer le mode kiosque ?") },
            text = {
                Text(
                    "L'écran sera déverrouillé et l'application ne sera plus propriétaire de la tablette. " +
                        "Pour remettre le kiosque, il faudra réinitialiser la tablette. " +
                        "« Quitter le mode kiosque » suffit pour une simple maintenance.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmation = false; kiosque.retirerDroits() }) { Text("Retirer") } },
            dismissButton = { TextButton(onClick = { confirmation = false }) { Text("Annuler") } },
        )
    }
}

private fun FrequenceSauvegarde.libelle() = if (this == FrequenceSauvegarde.QUOTIDIENNE) "quotidien" else "hebdomadaire"

@Composable
private fun DialogMotDePasseRestauration(
    motDePasse: String, onChange: (String) -> Unit, onAnnuler: () -> Unit, onChoisirFichier: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.observerActivite(LocalActivite.current),
        onDismissRequest = onAnnuler,
        title = { Text("Restaurer une copie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Si la copie est chiffrée, saisissez le mot de passe de sauvegarde (laissez vide pour une copie en clair).")
                Champ("Mot de passe de sauvegarde", motDePasse, onChange, secret = true)
            }
        },
        confirmButton = { Button(onClick = onChoisirFichier) { Text("Choisir le fichier") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun DialogEmail(
    actuel: ConfigurationEmail?, aMotDePasseSmtp: Boolean, aMotDePasseSauvegarde: Boolean,
    onAnnuler: () -> Unit, onValider: (ConfigurationEmail, String, String) -> Unit,
) {
    var hote by rememberSaveable { mutableStateOf(actuel?.hote ?: "") }
    var port by rememberSaveable { mutableStateOf((actuel?.port ?: 465).toString()) }
    // L'interface ne propose pas l'envoi en clair : seules SSL et STARTTLS existent ici.
    var securite by rememberSaveable { mutableStateOf(actuel?.securite?.takeIf { it != SecuriteSmtp.AUCUNE } ?: SecuriteSmtp.SSL) }
    var utilisateur by rememberSaveable { mutableStateOf(actuel?.utilisateur ?: "") }
    var expediteur by rememberSaveable { mutableStateOf(actuel?.expediteur ?: "") }
    var destinataire by rememberSaveable { mutableStateOf(actuel?.destinataire ?: "") }
    var frequence by rememberSaveable { mutableStateOf(actuel?.frequence ?: FrequenceSauvegarde.QUOTIDIENNE) }
    var motDePasseSmtp by remember { mutableStateOf("") }
    var motDePasseSauvegarde by remember { mutableStateOf("") }

    AlertDialog(
        modifier = Modifier.observerActivite(LocalActivite.current),
        onDismissRequest = onAnnuler,
        title = { Text("Envoi des sauvegardes par e-mail") },
        text = {
            // Défilement : en paysage ou avec le clavier ouvert, le formulaire dépasse l'écran.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Champ("Serveur SMTP (exemple : smtp.exemple.fr)", hote, { hote = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Champ("Port", port, { port = it.filter(Char::isDigit).take(5) }, chiffres = true, modifier = Modifier.weight(1f))
                    FilterChip(selected = securite == SecuriteSmtp.SSL, onClick = { securite = SecuriteSmtp.SSL; if (port == "587") port = "465" }, label = { Text("SSL") })
                    FilterChip(selected = securite == SecuriteSmtp.STARTTLS, onClick = { securite = SecuriteSmtp.STARTTLS; if (port == "465") port = "587" }, label = { Text("STARTTLS") })
                }
                Champ("Identifiant du compte d'envoi", utilisateur, { utilisateur = it })
                Champ(
                    if (aMotDePasseSmtp) "Mot de passe du compte (vide = inchangé)" else "Mot de passe du compte d'envoi",
                    motDePasseSmtp, { motDePasseSmtp = it }, secret = true,
                )
                Champ("Adresse d'envoi", expediteur, { expediteur = it })
                Champ("Adresse de réception", destinataire, { destinataire = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = frequence == FrequenceSauvegarde.QUOTIDIENNE, onClick = { frequence = FrequenceSauvegarde.QUOTIDIENNE }, label = { Text("Chaque jour") })
                    FilterChip(selected = frequence == FrequenceSauvegarde.HEBDOMADAIRE, onClick = { frequence = FrequenceSauvegarde.HEBDOMADAIRE }, label = { Text("Chaque semaine") })
                }
                Champ(
                    if (aMotDePasseSauvegarde) "Mot de passe de sauvegarde (vide = inchangé)" else "Mot de passe de sauvegarde (12 caractères min.)",
                    motDePasseSauvegarde, { motDePasseSauvegarde = it }, secret = true,
                )
                Text("Notez le mot de passe de sauvegarde hors de la tablette : sans lui, une sauvegarde ne peut pas être restaurée.")
            }
        },
        confirmButton = {
            Button(onClick = {
                onValider(
                    ConfigurationEmail(hote.trim(), port.toIntOrNull() ?: 0, securite, utilisateur.trim(), expediteur.trim(), destinataire.trim(), frequence),
                    motDePasseSmtp, motDePasseSauvegarde,
                )
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

/** Le nom proposé dans le sélecteur de fichiers : on ne garde que chiffres et tirets, pour qu'un texte saisi à la main ne casse pas le nom. */
private fun nomFichier(debut: String, fin: String, extension: String): String {
    fun propre(t: String) = t.filter { it.isDigit() || it == '-' }
    return "pointages_${propre(debut)}_${propre(fin)}.$extension"
}

private fun SauvegardeEntite.resume(): String {
    val quand = Instant.ofEpochMilli(date)
    val etat = if (statut == "REUSSIE") "réussie" else "ÉCHEC : ${messageErreur ?: "raison inconnue"}"
    return "${quand.jour()} ${quand.heure()}  Sauvegarde $etat"
}

/** Choix des agents à exporter : aucun coché = tous les agents (EF-17 : un, plusieurs ou tous). */
@Composable
private fun ChoixAgents(agents: List<AgentAdmin>, choisis: Set<Long>, onChange: (Set<Long>) -> Unit) {
    var ouvert by remember { mutableStateOf(false) }
    val resume = when (choisis.size) {
        0 -> "Tous les agents"
        1 -> agents.find { it.id in choisis }?.let { "${it.prenom} ${it.nom}" } ?: "1 agent"
        else -> "${choisis.size} agents"
    }
    Box {
        OutlinedButton(onClick = { ouvert = true }) { Text(resume) }
        DropdownMenu(
            expanded = ouvert, onDismissRequest = { ouvert = false },
            modifier = Modifier.observerActivite(LocalActivite.current),
        ) {
            DropdownMenuItem(
                text = { Text("Tous les agents") },
                leadingIcon = { Checkbox(checked = choisis.isEmpty(), onCheckedChange = null) },
                onClick = { onChange(emptySet()); ouvert = false },
            )
            agents.forEach { a ->
                // Le menu reste ouvert : on coche plusieurs agents d'affilée.
                DropdownMenuItem(
                    text = { Text("${a.prenom} ${a.nom}") },
                    leadingIcon = { Checkbox(checked = a.id in choisis, onCheckedChange = null) },
                    onClick = { onChange(if (a.id in choisis) choisis - a.id else choisis + a.id) },
                )
            }
        }
    }
}
