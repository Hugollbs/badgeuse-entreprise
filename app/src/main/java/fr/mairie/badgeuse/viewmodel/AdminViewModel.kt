package fr.mairie.badgeuse.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.mairie.badgeuse.donnees.AgentAdmin
import fr.mairie.badgeuse.donnees.AnomalieAffichee
import fr.mairie.badgeuse.donnees.ChiffrementSauvegarde
import fr.mairie.badgeuse.donnees.DepotAdmin
import fr.mairie.badgeuse.donnees.DepotSauvegarde
import fr.mairie.badgeuse.donnees.EntreeJournal
import fr.mairie.badgeuse.donnees.EvenementSecurite
import fr.mairie.badgeuse.donnees.PointageAdmin
import fr.mairie.badgeuse.donnees.ReglagesSauvegarde
import fr.mairie.badgeuse.donnees.ResultatConnexion
import fr.mairie.badgeuse.donnees.ResultatReinitialisation
import fr.mairie.badgeuse.donnees.ResultatRestauration
import fr.mairie.badgeuse.donnees.ResultatSauvegarde
import fr.mairie.badgeuse.donnees.SauvegardeEntite
import fr.mairie.badgeuse.donnees.ServiceSauvegarde
import fr.mairie.badgeuse.domaine.ConfigurationEmail
import fr.mairie.badgeuse.domaine.FrequenceSauvegarde
import fr.mairie.badgeuse.domaine.DUREE_CONSERVATION_MAX_MOIS
import fr.mairie.badgeuse.domaine.DUREE_CONSERVATION_MIN_MOIS
import fr.mairie.badgeuse.domaine.DUREE_CONSERVATION_MOIS
import fr.mairie.badgeuse.domaine.LONGUEUR_MIN_MOT_DE_PASSE
import fr.mairie.badgeuse.domaine.LONGUEUR_MIN_MOT_DE_PASSE_SAUVEGARDE
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TempsJournee
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.domaine.erreurConfigurationEmail
import fr.mairie.badgeuse.domaine.motDePasseSauvegardeValide
import fr.mairie.badgeuse.domaine.tempsDeLaJournee
import fr.mairie.badgeuse.export.LigneExport
import fr.mairie.badgeuse.export.ecrirePdf
import fr.mairie.badgeuse.export.genererCsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

enum class Onglet { POINTAGES, ANOMALIES, AGENTS, JOURNAL, EXPORT }

/** Temps de travail du jour d'un agent, affiché dans l'onglet Pointages. */
data class TempsAgent(val agentId: Long, val agent: String, val temps: TempsJournee)

sealed interface EcranAdmin {
    data object Chargement : EcranAdmin
    data class Configuration(val message: String? = null) : EcranAdmin
    data class Connexion(val message: String? = null) : EcranAdmin
    data class Reinitialisation(val message: String? = null) : EcranAdmin

    /** Affiché une seule fois ; l'utilisateur confirme l'avoir noté avant de continuer. */
    data class CodeSecoursAffiche(val code: String) : EcranAdmin

    data class Espace(
        val onglet: Onglet = Onglet.POINTAGES,
        val jour: LocalDate,
        val pointages: List<PointageAdmin> = emptyList(),
        /** Temps de travail du [jour] par agent ayant pointé. */
        val temps: List<TempsAgent> = emptyList(),
        val anomalies: List<AnomalieAffichee> = emptyList(),
        val agents: List<AgentAdmin> = emptyList(),
        val journal: List<EntreeJournal> = emptyList(),
        /** Nombre de lignes de journal demandées ; « Afficher plus » l'augmente. */
        val limiteJournal: Int = TAILLE_PAGE_JOURNAL,
        val evenements: List<EvenementSecurite> = emptyList(),
        val sauvegardes: List<SauvegardeEntite> = emptyList(),
        /** Réglages d'envoi (sans mot de passe) ; null tant que l'envoi n'est pas configuré. */
        val reglagesEmail: ConfigurationEmail? = null,
        val aMotDePasseSmtp: Boolean = false,
        val aMotDePasseSauvegarde: Boolean = false,
        val conservationMois: Long = DUREE_CONSERVATION_MOIS,
        val message: String? = null,
    ) : EcranAdmin
}

const val TAILLE_PAGE_JOURNAL = 50

class AdminViewModel(
    private val depot: DepotAdmin,
    private val depotSauvegarde: DepotSauvegarde,
    private val reglages: ReglagesSauvegarde,
    private val service: ServiceSauvegarde,
    /** Programme la tâche d'envoi périodique ; fournie par l'activité, car il faut un Context. */
    private val planifier: (FrequenceSauvegarde) -> Unit,
    private val fuseau: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _ecran = MutableStateFlow<EcranAdmin>(EcranAdmin.Chargement)
    val ecran: StateFlow<EcranAdmin> = _ecran.asStateFlow()

    /**
     * Numéro de la visite en cours de l'espace admin. Chaque entrée et chaque sortie l'incrémentent.
     * Une opération lente (envoi d'e-mail, connexion) garde le numéro de la visite qui l'a lancée :
     * si l'administrateur est parti entre-temps, son résultat est jeté. Sans cela, le résultat d'un
     * envoi terminé après « Quitter » rouvrait l'espace connecté devant n'importe qui (audit B1).
     * L'opération elle-même n'est pas interrompue : un envoi commencé va au bout et reste tracé.
     */
    private var session = 0

    /** À appeler à chaque entrée dans l'espace admin : repart toujours de l'écran de connexion. */
    fun demarrer() {
        session++
        _ecran.value = EcranAdmin.Chargement
        lancer(enCasDErreur = { EcranAdmin.Connexion(it) }) {
            if (depot.estConfigure()) EcranAdmin.Connexion() else EcranAdmin.Configuration()
        }
    }

    /** À appeler en quittant l'espace admin : efface les données affichées et invalide les opérations en cours. */
    fun fermer() {
        session++
        _ecran.value = EcranAdmin.Chargement
    }

    fun configurer(motDePasse: String, confirmation: String) {
        if (motDePasse != confirmation) return ecrire(EcranAdmin.Configuration("Les deux mots de passe sont différents."))
        lancer(enCasDErreur = { EcranAdmin.Configuration(it) }) {
            depot.configurerPremiereFois(motDePasse)?.let { EcranAdmin.CodeSecoursAffiche(it) }
                ?: EcranAdmin.Configuration("Mot de passe refusé : il faut au moins $LONGUEUR_MIN_MOT_DE_PASSE caractères.")
        }
    }

    /** Après avoir noté le code de secours, on passe à la connexion. */
    fun codeSecoursNote() = ecrire(EcranAdmin.Connexion())

    fun connecter(motDePasse: String) {
        lancer(enCasDErreur = { EcranAdmin.Connexion(it) }) {
            when (val r = depot.connecter(motDePasse)) {
                ResultatConnexion.Ok -> charger(EcranAdmin.Espace(jour = LocalDate.now(fuseau)))
                is ResultatConnexion.Incorrect -> EcranAdmin.Connexion("Mot de passe incorrect. Il reste ${r.essaisRestants} essai(s).")
                is ResultatConnexion.Bloque -> EcranAdmin.Connexion("Trop d'essais. Réessayez plus tard.")
                ResultatConnexion.NonConfigure -> EcranAdmin.Configuration()
            }
        }
    }

    fun ouvrirReinitialisation() = ecrire(EcranAdmin.Reinitialisation())

    fun annulerReinitialisation() = ecrire(EcranAdmin.Connexion())

    fun reinitialiser(codeSecours: String, motDePasse: String, confirmation: String) {
        if (motDePasse != confirmation) return ecrire(EcranAdmin.Reinitialisation("Les deux mots de passe sont différents."))
        lancer(enCasDErreur = { EcranAdmin.Reinitialisation(it) }) {
            when (val r = depot.reinitialiserMotDePasse(codeSecours, motDePasse)) {
                is ResultatReinitialisation.Ok -> EcranAdmin.CodeSecoursAffiche(r.nouveauCodeSecours)
                is ResultatReinitialisation.CodeIncorrect ->
                    EcranAdmin.Reinitialisation("Code de secours incorrect. Il reste ${r.essaisRestants} essai(s).")
                is ResultatReinitialisation.Bloque -> EcranAdmin.Reinitialisation("Trop d'essais. Réessayez plus tard.")
                ResultatReinitialisation.MotDePasseInvalide ->
                    EcranAdmin.Reinitialisation("Mot de passe refusé : il faut au moins $LONGUEUR_MIN_MOT_DE_PASSE caractères.")
            }
        }
    }

    // --- Espace connecté -------------------------------------------------------------------

    fun choisirOnglet(onglet: Onglet) = modifierEspace { it.copy(onglet = onglet) }

    fun changerJour(decalage: Long) {
        val courant = _ecran.value as? EcranAdmin.Espace ?: return
        lancer(enCasDErreur = { courant.copy(message = it) }) {
            charger(courant.copy(jour = courant.jour.plusDays(decalage), message = null))
        }
    }

    fun journalPlus() = agir { modifierEspace { it.copy(limiteJournal = it.limiteJournal + TAILLE_PAGE_JOURNAL) }; null }

    fun creerAgent(nom: String, prenom: String, pole: String, code: String) = agir {
        if (depot.creerAgent(nom, prenom, pole, code)) "Agent créé."
        else "Agent non créé : nom, prénom et un code à 4 chiffres sont obligatoires."
    }

    fun definirActif(agentId: Long, actif: Boolean) = agir { depot.definirActif(agentId, actif); null }

    fun changerCode(agentId: Long, code: String) = agir {
        if (depot.changerCode(agentId, code)) "Code changé." else "Le code doit avoir exactement 4 chiffres."
    }

    fun debloquer(agentId: Long) = agir { depot.debloquer(agentId); "Agent débloqué." }

    fun marquerTraitee(anomalieId: Long) = agir { depot.marquerTraitee(anomalieId); null }

    fun ajouterPointage(agentId: Long, type: TypePointage, heure: String, motif: String) {
        val courant = _ecran.value as? EcranAdmin.Espace ?: return
        val instant = lireHeure(heure, courant.jour) ?: return afficher(MESSAGE_HEURE)
        if (instant.isAfter(Instant.now())) return afficher(MESSAGE_FUTUR)
        agir { if (depot.ajouterPointage(agentId, type, instant, motif)) "Pointage ajouté." else "Pointage non ajouté : agent introuvable ou heure dans le futur." }
    }

    /**
     * Corrige un pointage (EF-12) : type, heure, agent (erreur de profil) et statut (annuler ou rétablir).
     * Chaque correction est écrite au journal avec l'ancienne et la nouvelle valeur (EF-13).
     */
    fun modifierPointage(pointageId: Long, agentId: Long, type: TypePointage, heure: String, annule: Boolean, motif: String) {
        val courant = _ecran.value as? EcranAdmin.Espace ?: return
        val instant = lireHeure(heure, courant.jour) ?: return afficher(MESSAGE_HEURE)
        if (instant.isAfter(Instant.now())) return afficher(MESSAGE_FUTUR)
        val statut = if (annule) StatutPointage.ANNULE else StatutPointage.VALIDE
        agir {
            if (depot.modifierPointage(pointageId, type, instant, motif, agentId, statut)) "Pointage modifié."
            else "Pointage non modifié : introuvable, agent inconnu ou heure dans le futur."
        }
    }

    // --- Export, copie et restauration ------------------------------------------------------
    // Les fonctions reçoivent un « ouvrir » plutôt qu'un fichier : c'est l'écran qui sait ouvrir le
    // fichier choisi par l'utilisateur (Android ne donne pas de chemin, seulement un flux).

    /** [agentIds] vide = tous les agents (EF-17). */
    fun exporterCsv(agentIds: Set<Long>, debut: String, fin: String, ouvrir: () -> OutputStream?) =
        exporter(agentIds, debut, fin, ouvrir) { lignes, _, _, _, flux ->
            flux.write(genererCsv(lignes, fuseau).toByteArray(Charsets.UTF_8))
        }

    fun exporterPdf(agentIds: Set<Long>, debut: String, fin: String, ouvrir: () -> OutputStream?) =
        exporter(agentIds, debut, fin, ouvrir) { lignes, d, f, titre, flux -> ecrirePdf(lignes, d, f, fuseau, titre, flux) }

    private fun exporter(
        agentIds: Set<Long>, debut: String, fin: String, ouvrir: () -> OutputStream?,
        ecrire: (List<LigneExport>, LocalDate, LocalDate, String, OutputStream) -> Unit,
    ) {
        val (d, f) = lirePeriode(debut, fin) ?: return afficher(MESSAGE_PERIODE)
        agir {
            val lignes = depotSauvegarde.lignesExport(agentIds, d, f)
            val titre = titreExport(agentIds)
            ecrireFichier(ouvrir) { ecrire(lignes, d, f, titre, it) } ?: "Export terminé : ${lignes.size} pointage(s)."
        }
    }

    private suspend fun titreExport(agentIds: Set<Long>): String {
        if (agentIds.isEmpty()) return "Tous les agents"
        val noms = depotSauvegarde.nomsAgents(agentIds)
        return if (noms.size <= 3) noms.joinToString(", ") else "${noms.size} agents"
    }

    /**
     * Copie complète de la base dans un fichier choisi par l'utilisateur, chiffrée avec le mot de
     * passe de sauvegarde (ADR-003). Ce n'est PAS un envoi : les pointages ne sont pas marqués
     * « envoyés » et ne seront donc jamais purgés à cause d'elle.
     */
    fun enregistrerCopie(ouvrir: () -> OutputStream?) = agir {
        val motDePasse = reglages.motDePasseSauvegarde()
            ?: return@agir "Définissez d'abord le mot de passe de sauvegarde (bouton « Configurer l'envoi »)."
        val fichier = depotSauvegarde.preparerSauvegarde()
        val chiffre = withContext(Dispatchers.Default) {
            ChiffrementSauvegarde.chiffrer(fichier.contenu.toByteArray(Charsets.UTF_8), motDePasse)
        }
        ecrireFichier(ouvrir) { it.write(chiffre) } ?: "Copie chiffrée enregistrée."
    }

    /** [motDePasse] sert à ouvrir une copie chiffrée ; il est ignoré pour une copie en clair. */
    fun restaurer(motDePasse: String, ouvrir: () -> InputStream?) = agir {
        val octets = withContext(Dispatchers.IO) { lireOctets(ouvrir) }
            ?: return@agir "Fichier illisible ou trop gros."
        val json = if (ChiffrementSauvegarde.estChiffre(octets)) {
            if (motDePasse.isEmpty()) return@agir "Cette copie est chiffrée : saisissez le mot de passe de sauvegarde."
            val clair = withContext(Dispatchers.Default) { ChiffrementSauvegarde.dechiffrer(octets, motDePasse) }
                ?: return@agir "Mot de passe de sauvegarde incorrect, ou fichier abîmé."
            String(clair, Charsets.UTF_8)
        } else {
            String(octets, Charsets.UTF_8)
        }
        when (val r = depotSauvegarde.restaurer(json)) {
            is ResultatRestauration.Ok ->
                "Restauration terminée : ${r.compteurs.agents} agent(s), ${r.compteurs.pointages} pointage(s)."
            ResultatRestauration.BaseNonVide -> "Restauration refusée : l'application contient déjà des données."
            ResultatRestauration.Invalide -> "Ce fichier n'est pas une sauvegarde valide."
        }
    }

    /** Écrit via [ecrire] sur un autre fil (disque). Renvoie un message d'erreur, ou null si tout s'est bien passé. */
    private suspend fun ecrireFichier(ouvrir: () -> OutputStream?, ecrire: (OutputStream) -> Unit): String? =
        withContext(Dispatchers.IO) {
            try {
                val flux = ouvrir() ?: return@withContext "Impossible d'ouvrir le fichier choisi."
                flux.use(ecrire)
                null
            } catch (e: IOException) {
                "Échec de l'écriture du fichier."
            } catch (e: SecurityException) {
                "Accès au fichier refusé."
            }
        }

    /** Lit tout le fichier, mais refuse au-delà de [TAILLE_MAX_RESTAURATION] octets (fichier suspect). */
    private fun lireOctets(ouvrir: () -> InputStream?): ByteArray? = try {
        ouvrir()?.use { flux ->
            val sortie = ByteArrayOutputStream()
            val tampon = ByteArray(8192)
            while (true) {
                val n = flux.read(tampon)
                if (n < 0) break
                sortie.write(tampon, 0, n)
                if (sortie.size() > TAILLE_MAX_RESTAURATION) return null
            }
            sortie.toByteArray()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    // --- Envoi par e-mail et conservation ---------------------------------------------------

    /**
     * Enregistre les réglages. Un mot de passe vide garde l'ancien ; il est obligatoire seulement
     * s'il n'y en a pas encore. Aucun mot de passe n'est jamais affiché ni écrit dans un message.
     */
    fun enregistrerReglagesEmail(config: ConfigurationEmail, motDePasseSmtp: String, motDePasseSauvegarde: String) = agir {
        erreurConfigurationEmail(config)?.let { return@agir it }
        val espace = _ecran.value as? EcranAdmin.Espace
        if (motDePasseSmtp.isEmpty() && espace?.aMotDePasseSmtp != true) {
            return@agir "Le mot de passe du compte d'envoi est obligatoire."
        }
        if (motDePasseSauvegarde.isEmpty() && espace?.aMotDePasseSauvegarde != true) {
            return@agir "Le mot de passe de sauvegarde est obligatoire (au moins $LONGUEUR_MIN_MOT_DE_PASSE_SAUVEGARDE caractères)."
        }
        if (motDePasseSauvegarde.isNotEmpty() && !motDePasseSauvegardeValide(motDePasseSauvegarde)) {
            return@agir "Le mot de passe de sauvegarde doit avoir au moins $LONGUEUR_MIN_MOT_DE_PASSE_SAUVEGARDE caractères."
        }
        reglages.enregistrer(config, motDePasseSmtp, motDePasseSauvegarde)
        planifier(config.frequence)
        "Réglages enregistrés. Notez le mot de passe de sauvegarde hors de la tablette : il sert à restaurer."
    }

    fun envoyerSauvegardeMaintenant() = agir {
        when (val r = service.envoyer()) {
            is ResultatSauvegarde.Reussie ->
                "Sauvegarde envoyée" + if (r.pointagesPurges > 0) ", ${r.pointagesPurges} ancien(s) pointage(s) effacé(s) de la tablette." else "."
            is ResultatSauvegarde.Echec -> "Échec de l'envoi : ${r.message}"
            ResultatSauvegarde.NonConfiguree -> "Configurez d'abord l'envoi par e-mail."
        }
    }

    fun enregistrerConservation(texte: String) = agir {
        val mois = texte.trim().toLongOrNull()
        if (mois != null && depotSauvegarde.definirDureeConservation(mois)) {
            "Durée de conservation : $mois mois. Elle s'applique après le prochain envoi réussi."
        } else {
            "Durée invalide : un nombre de mois entre $DUREE_CONSERVATION_MIN_MOIS et $DUREE_CONSERVATION_MAX_MOIS."
        }
    }

    private fun lirePeriode(debut: String, fin: String): Pair<LocalDate, LocalDate>? {
        val periode = try {
            LocalDate.parse(debut.trim()) to LocalDate.parse(fin.trim())
        } catch (e: DateTimeParseException) {
            return null
        }
        return periode.takeIf { !it.second.isBefore(it.first) }
    }

    // --- Outils ----------------------------------------------------------------------------

    private suspend fun charger(espace: EcranAdmin.Espace): EcranAdmin.Espace {
        val pointages = depot.pointagesDuJour(espace.jour)
        return espace.copy(
            pointages = pointages,
            temps = tempsDuJour(pointages, espace.jour),
            anomalies = depot.anomaliesOuvertes(),
            agents = depot.agents(),
            journal = depot.journalRecent(espace.limiteJournal),
            evenements = depot.evenementsSecurite(),
            sauvegardes = depotSauvegarde.sauvegardesRecentes(),
            reglagesEmail = reglages.lire(),
            aMotDePasseSmtp = reglages.aMotDePasseSmtp(),
            aMotDePasseSauvegarde = reglages.aMotDePasseSauvegarde(),
            conservationMois = depotSauvegarde.dureeConservationMois(),
        )
    }

    private fun tempsDuJour(pointages: List<PointageAdmin>, jour: LocalDate): List<TempsAgent> =
        pointages.groupBy { it.pointage.agentId }.mapNotNull { (agentId, liste) ->
            tempsDeLaJournee(liste.map { it.pointage }, jour, fuseau)?.let { TempsAgent(agentId, liste[0].agent, it) }
        }.sortedBy { it.agent }

    /**
     * Lance [bloc] dans la visite en cours et affiche l'écran qu'il renvoie, sauf si l'administrateur
     * a quitté l'espace entre-temps (voir [session]). Une erreur imprévue affiche un message au lieu
     * de faire planter l'application.
     */
    private fun lancer(enCasDErreur: (String) -> EcranAdmin?, bloc: suspend () -> EcranAdmin?) {
        val visite = session
        viewModelScope.launch {
            val suivant = try {
                bloc()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Opération de l'espace administrateur impossible", e)
                enCasDErreur(MESSAGE_ERREUR)
            }
            if (visite == session && suivant != null) _ecran.value = suivant
        }
    }

    /** Exécute une action de l'espace connecté puis recharge les listes ; [action] renvoie le message à afficher, ou null. */
    private fun agir(action: suspend () -> String?) {
        if (_ecran.value !is EcranAdmin.Espace) return
        lancer(enCasDErreur = { message -> (_ecran.value as? EcranAdmin.Espace)?.copy(message = message) }) {
            val message = action()
            // On repart de l'écran actuel (onglet choisi entre-temps) et non de celui du lancement.
            (_ecran.value as? EcranAdmin.Espace)?.let { charger(it.copy(message = message)) }
        }
    }

    private fun afficher(message: String) = modifierEspace { it.copy(message = message) }

    /** Message venu de l'écran (par exemple : aucun sélecteur de fichiers disponible). */
    fun signaler(message: String) = afficher(message)

    private fun modifierEspace(transformation: (EcranAdmin.Espace) -> EcranAdmin.Espace) {
        (_ecran.value as? EcranAdmin.Espace)?.let { _ecran.value = transformation(it) }
    }

    private fun ecrire(valeur: EcranAdmin) {
        _ecran.value = valeur
    }

    private fun lireHeure(texte: String, jour: LocalDate): Instant? = try {
        jour.atTime(LocalTime.parse(texte.trim())).atZone(fuseau).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        const val TAG = "AdminViewModel"
        const val MESSAGE_HEURE = "Heure invalide : écrivez-la sous la forme 08:30."
        const val MESSAGE_FUTUR = "Heure invalide : un pointage ne peut pas être dans le futur."
        const val MESSAGE_PERIODE = "Période invalide : écrivez les dates sous la forme 2026-11-04, la fin après le début."
        const val MESSAGE_ERREUR = "Une erreur technique est survenue : l'opération n'a pas été faite."
        const val TAILLE_MAX_RESTAURATION = 20 * 1024 * 1024
    }
}
