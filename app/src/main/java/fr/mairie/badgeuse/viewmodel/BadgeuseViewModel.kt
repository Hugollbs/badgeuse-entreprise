package fr.mairie.badgeuse.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mairie.badgeuse.donnees.AgentAffiche
import fr.mairie.badgeuse.donnees.DepotBadgeuse
import fr.mairie.badgeuse.donnees.ResultatCode
import fr.mairie.badgeuse.domaine.EtatAgent
import fr.mairie.badgeuse.domaine.Pointage
import fr.mairie.badgeuse.domaine.TypePointage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

sealed interface Ecran {
    data object Accueil : Ecran

    /** Le contenu de l'espace administrateur est géré par AdminViewModel. */
    data object Admin : Ecran

    /** [essai] change à chaque saisie refusée, pour que l'écran vide les chiffres déjà tapés. */
    data class Code(val agent: AgentAffiche, val message: String? = null, val essai: Int = 0) : Ecran

    data class Espace(
        val agent: AgentAffiche,
        val etat: EtatAgent,
        /** Aperçu : les derniers pointages seulement. */
        val historique: List<Pointage>,
        /** Dernier pointage fait pendant cette session, affiché avec le bouton Annuler. */
        val confirmation: Pointage? = null,
        val message: String? = null,
        /** Tous les pointages de l'agent, quand il a demandé à les voir (EF-07) ; null sinon. */
        val tousLesPointages: List<Pointage>? = null,
    ) : Ecran
}

val DELAI_INACTIVITE: Duration = Duration.ofSeconds(30)

/** Ce qui peut suspendre le retour automatique à l'accueil. */
enum class RaisonPause { CODE_SECOURS, SELECTEUR_FICHIER }

/** Taille de l'aperçu affiché dans l'espace agent ; la liste complète est à un bouton de là. */
const val TAILLE_APERCU_HISTORIQUE = 10

private const val MESSAGE_ERREUR = "Une erreur technique est survenue. Réessayez ; si elle persiste, prévenez la DGS."

class BadgeuseViewModel(
    private val depot: DepotBadgeuse,
    private val delaiInactivite: Duration = DELAI_INACTIVITE,
) : ViewModel() {
    private val _agents = MutableStateFlow<List<AgentAffiche>>(emptyList())
    val agents: StateFlow<List<AgentAffiche>> = _agents.asStateFlow()

    private val _ecran = MutableStateFlow<Ecran>(Ecran.Accueil)
    val ecran: StateFlow<Ecran> = _ecran.asStateFlow()

    private var minuteur: Job? = null
    private val raisonsPause = mutableSetOf<RaisonPause>()

    /** Instant (horloge monotone, en ms) où l'on reviendra à l'accueil sans nouveau toucher. */
    private var echeance = 0L

    /**
     * Vrai pendant l'enregistrement d'un pointage ou d'une annulation. Sans ce verrou, un double
     * appui lance deux requêtes : la seconde est refusée et son message d'erreur remplace la
     * confirmation de la première, bouton Annuler compris. Tout tourne sur le fil principal,
     * un simple booléen suffit (pas d'accès concurrent).
     */
    private var operationEnCours = false

    init {
        viewModelScope.launch { depot.agentsActifsEnContinu().collect { _agents.value = it } }
    }

    fun ouvrirAdmin() {
        _ecran.value = Ecran.Admin
        activite()
    }

    /**
     * Suspend le retour automatique à l'accueil pour une [raison] donnée (code de secours à recopier,
     * sélecteur de fichiers ouvert). Le minuteur ne reprend que quand plus aucune raison ne tient.
     */
    fun suspendreMinuteur(raison: RaisonPause, suspendre: Boolean) {
        if (suspendre) raisonsPause += raison else raisonsPause -= raison
        if (raisonsPause.isNotEmpty()) {
            minuteur?.cancel()
            minuteur = null
        } else {
            activite()
        }
    }

    fun choisirAgent(agent: AgentAffiche) {
        _ecran.value = Ecran.Code(agent)
        activite()
    }

    /*
     * Règle commune aux actions ci-dessous : le résultat d'une opération en base arrive un peu plus
     * tard. S'il arrive alors que l'écran a changé (Retour, Quitter, minuteur), on l'ignore : sinon
     * l'espace d'un agent se rouvrirait devant la personne suivante (EF-08). On compare avec `!==`,
     * l'identité de l'objet : chaque changement d'écran crée un nouvel objet.
     */

    fun validerCode(code: String) {
        val courant = _ecran.value as? Ecran.Code ?: return
        viewModelScope.launch {
            val suivant = try {
                when (val resultat = depot.verifierCode(courant.agent.id, code)) {
                    is ResultatCode.Valide -> espace(resultat.agent)
                    is ResultatCode.Incorrect -> courant.copy(
                        message = "Code incorrect. Il vous reste ${resultat.essaisRestants} essai(s).",
                        essai = courant.essai + 1,
                    )
                    is ResultatCode.Bloque -> courant.copy(
                        message = "Accès bloqué jusqu'à ${formaterHeure(resultat.jusqua)}. Voyez la DGS pour débloquer.",
                        essai = courant.essai + 1,
                    )
                    ResultatCode.AgentIntrouvable -> Ecran.Accueil
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Vérification du code impossible", e)
                courant.copy(message = MESSAGE_ERREUR, essai = courant.essai + 1)
            }
            if (_ecran.value !== courant) return@launch
            _ecran.value = suivant
            activite()
        }
    }

    fun pointer(type: TypePointage) {
        val courant = _ecran.value as? Ecran.Espace ?: return
        executerDansLEspace(courant) {
            val pointage = depot.pointer(courant.agent.id, type)
            rafraichir(
                courant,
                confirmation = pointage,
                message = if (pointage == null) "Action impossible dans votre état actuel." else null,
            )
        }
    }

    fun annulerDernier() {
        val courant = _ecran.value as? Ecran.Espace ?: return
        val pointage = courant.confirmation ?: return
        executerDansLEspace(courant) {
            val reussi = depot.annuler(courant.agent.id, pointage.id)
            rafraichir(
                courant,
                confirmation = null,
                message = if (reussi) "Pointage annulé." else "Le délai d'annulation est dépassé.",
            )
        }
    }

    /** EF-07 : l'agent voit l'aperçu, et peut demander la liste complète de ses propres pointages. */
    fun voirTousMesPointages() {
        val courant = _ecran.value as? Ecran.Espace ?: return
        executerDansLEspace(courant) { courant.copy(tousLesPointages = depot.tousLesPointages(courant.agent.id)) }
    }

    fun fermerTousMesPointages() {
        val courant = _ecran.value as? Ecran.Espace ?: return
        _ecran.value = courant.copy(tousLesPointages = null)
        activite()
    }

    fun retourAccueil() {
        raisonsPause.clear()
        minuteur?.cancel()
        minuteur = null
        _ecran.value = Ecran.Accueil
    }

    /**
     * À appeler à chaque toucher : repousse le retour automatique à l'accueil (30 s sans toucher).
     * Un toucher ne fait que reculer l'échéance ; une seule tâche attend, au lieu d'en recréer une
     * à chaque mouvement du doigt.
     */
    fun activite() {
        if (_ecran.value is Ecran.Accueil || raisonsPause.isNotEmpty()) {
            minuteur?.cancel()
            minuteur = null
            return
        }
        echeance = maintenantMs() + delaiInactivite.toMillis()
        if (minuteur?.isActive == true) return
        minuteur = viewModelScope.launch {
            while (true) {
                val reste = echeance - maintenantMs()
                if (reste <= 0) break
                delay(reste)
            }
            retourAccueil()
        }
    }

    /** Lance [action] avec le verrou anti double appui, et n'applique son résultat que si l'écran n'a pas changé. */
    private fun executerDansLEspace(courant: Ecran.Espace, action: suspend () -> Ecran.Espace) {
        if (operationEnCours) return
        operationEnCours = true
        viewModelScope.launch {
            try {
                val suivant = try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Opération de l'espace agent impossible", e)
                    courant.copy(message = MESSAGE_ERREUR)
                }
                if (_ecran.value !== courant) return@launch
                _ecran.value = suivant
                activite()
            } finally {
                operationEnCours = false
            }
        }
    }

    private suspend fun espace(agent: AgentAffiche) =
        Ecran.Espace(agent, depot.etatDeLAgent(agent.id), depot.historique(agent.id, TAILLE_APERCU_HISTORIQUE))

    private suspend fun rafraichir(courant: Ecran.Espace, confirmation: Pointage?, message: String?) =
        courant.copy(
            etat = depot.etatDeLAgent(courant.agent.id),
            historique = depot.historique(courant.agent.id, TAILLE_APERCU_HISTORIQUE),
            confirmation = confirmation,
            message = message,
            tousLesPointages = null,
        )

    // Horloge monotone : un changement de l'heure de la tablette ne doit pas dérégler le minuteur.
    private fun maintenantMs(): Long = System.nanoTime() / 1_000_000

    private fun formaterHeure(instant: Instant): String =
        instant.atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    private companion object {
        const val TAG = "BadgeuseViewModel"
    }
}
