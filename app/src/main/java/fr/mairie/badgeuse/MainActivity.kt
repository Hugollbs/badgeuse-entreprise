package fr.mairie.badgeuse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import fr.mairie.badgeuse.ui.DUREE_COURTE
import fr.mairie.badgeuse.ui.DUREE_MOYENNE
import fr.mairie.badgeuse.ui.bloquerToucher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import fr.mairie.badgeuse.kiosque.Kiosque
import fr.mairie.badgeuse.taches.PlanificationTaches
import fr.mairie.badgeuse.ui.ActionsKiosque
import fr.mairie.badgeuse.ui.LocalKiosque
import fr.mairie.badgeuse.ui.EcranAccueil
import fr.mairie.badgeuse.ui.EcranCode
import fr.mairie.badgeuse.ui.HoteAdmin
import fr.mairie.badgeuse.ui.LocalActivite
import fr.mairie.badgeuse.ui.LocalSelecteurFichier
import fr.mairie.badgeuse.ui.observerActivite
import fr.mairie.badgeuse.ui.EcranEspace
import fr.mairie.badgeuse.ui.theme.BadgeuseTheme
import fr.mairie.badgeuse.viewmodel.AdminViewModel
import fr.mairie.badgeuse.viewmodel.BadgeuseViewModel
import fr.mairie.badgeuse.viewmodel.EcranAdmin
import fr.mairie.badgeuse.viewmodel.Ecran
import fr.mairie.badgeuse.viewmodel.RaisonPause

class MainActivity : ComponentActivity() {
    private val viewModel: BadgeuseViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BadgeuseViewModel((application as BadgeuseApp).depot) as T
        }
    }

    private val adminViewModel: AdminViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                (application as BadgeuseApp).let {
                    AdminViewModel(
                        it.depotAdmin, it.depotSauvegarde, it.reglagesSauvegarde, it.serviceSauvegarde,
                        planifier = { frequence -> PlanificationTaches.planifierSauvegarde(it, frequence) },
                    )
                } as T
        }
    }

    private val kiosque by lazy { Kiosque(applicationContext) }
    private var proprietaire by mutableStateOf(false)
    private var verrouille by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Une borne de badgage ne doit pas s'éteindre toute seule, ni demander de déverrouillage à chaque agent.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            BadgeuseTheme {
                val agents by viewModel.agents.collectAsState()
                val ecran by viewModel.ecran.collectAsState()
                val ecranAdmin by adminViewModel.ecran.collectAsState()
                // Le code de secours n'apparaît qu'une fois : pas de retour automatique pendant qu'on le recopie.
                LaunchedEffect(ecranAdmin, ecran) {
                    viewModel.suspendreMinuteur(
                        RaisonPause.CODE_SECOURS, ecran is Ecran.Admin && ecranAdmin is EcranAdmin.CodeSecoursAffiche,
                    )
                }
                // Dès qu'on quitte l'espace admin (bouton ou minuteur), on efface ses données :
                // sinon, à la réouverture, l'ancien espace connecté s'afficherait avant la connexion.
                LaunchedEffect(ecran) {
                    if (ecran !is Ecran.Admin) adminViewModel.fermer()
                }
                CompositionLocalProvider(
                    LocalActivite provides viewModel::activite,
                    LocalSelecteurFichier provides { ouvert -> viewModel.suspendreMinuteur(RaisonPause.SELECTEUR_FICHIER, ouvert) },
                    LocalKiosque provides ActionsKiosque(
                        proprietaire, verrouille,
                        quitterTemporairement = { kiosque.quitterTemporairement(this@MainActivity); actualiserKiosque() },
                        retirerDroits = { kiosque.retirerDroits(this@MainActivity); actualiserKiosque() },
                    ),
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .observerActivite(viewModel::activite),
                        ) {
                            AnimatedContent(
                                targetState = ecran,
                                // Clé = le type d'écran : un message ou un décompte qui change dans le même écran
                                // ne doit pas relancer la transition, seul un changement d'écran le fait.
                                contentKey = { it::class },
                                transitionSpec = { transitionEntreEcrans(initialState, targetState) },
                                label = "ecrans",
                            ) { e ->
                                // L'écran qui sort reste visible un instant : il ne doit plus recevoir de toucher.
                                val sortant = transition.targetState == EnterExitState.PostExit
                                Box(Modifier.fillMaxSize().bloquerToucher(sortant)) {
                                    when (e) {
                                        Ecran.Accueil -> EcranAccueil(agents, viewModel::choisirAgent, viewModel::ouvrirAdmin)
                                        Ecran.Admin -> {
                                            LaunchedEffect(Unit) { adminViewModel.demarrer() }
                                            HoteAdmin(adminViewModel, ecranAdmin, viewModel::retourAccueil)
                                        }
                                        is Ecran.Code -> EcranCode(e, viewModel::validerCode, viewModel::retourAccueil)
                                        is Ecran.Espace -> EcranEspace(
                                            e, viewModel::pointer, viewModel::annulerDernier, viewModel::retourAccueil,
                                            viewModel::voirTousMesPointages, viewModel::fermerTousMesPointages,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reprend le verrouillage à chaque retour au premier plan (démarrage, retour du sélecteur de fichiers...).
        kiosque.appliquer(this)
        actualiserKiosque()
        // Le verrouillage est asynchrone : on relit l'état un instant plus tard pour l'afficher.
        window.decorView.postDelayed({ actualiserKiosque() }, 700)
    }

    private fun actualiserKiosque() {
        proprietaire = kiosque.estProprietaire()
        verrouille = kiosque.estVerrouille()
    }
}

/** Profondeur de chaque écran dans le parcours : sert à savoir si l'on avance ou si l'on revient. */
private fun Ecran.profondeur() = when (this) {
    Ecran.Accueil -> 0
    is Ecran.Code, Ecran.Admin -> 1
    is Ecran.Espace -> 2
}

/**
 * Glissement léger et fondu : le nouvel écran arrive de la droite quand on avance dans le parcours,
 * de la gauche quand on revient (retour à l'accueil). Le déplacement est limité à un huitième de
 * la largeur pour rester discret.
 */
private fun transitionEntreEcrans(depart: Ecran, arrivee: Ecran): ContentTransform {
    val sens = if (arrivee.profondeur() >= depart.profondeur()) 1 else -1
    val entree = slideInHorizontally(tween(DUREE_MOYENNE, easing = FastOutSlowInEasing)) { sens * it / 8 } +
        fadeIn(tween(DUREE_MOYENNE, delayMillis = 60))
    val sortie = slideOutHorizontally(tween(DUREE_MOYENNE, easing = FastOutSlowInEasing)) { -sens * it / 8 } +
        fadeOut(tween(DUREE_COURTE))
    return entree togetherWith sortie
}
