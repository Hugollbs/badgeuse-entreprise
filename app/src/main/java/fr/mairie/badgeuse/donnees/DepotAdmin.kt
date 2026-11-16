package fr.mairie.badgeuse.donnees

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import fr.mairie.badgeuse.domaine.Anomalie
import fr.mairie.badgeuse.domaine.BlocageCode
import fr.mairie.badgeuse.domaine.EtatCode
import fr.mairie.badgeuse.domaine.Pointage
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypeAnomalie
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.domaine.anomaliesANouvellementDetecter
import fr.mairie.badgeuse.domaine.codeAgentValide
import fr.mairie.badgeuse.domaine.motDePasseAdminValide
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AgentAdmin(
    val id: Long, val nom: String, val prenom: String, val pole: String,
    val actif: Boolean, val bloque: Boolean,
)

data class PointageAdmin(val pointage: Pointage, val agent: String, val origine: String)

data class EvenementSecurite(val date: Instant, val type: String, val detail: String?)

data class AnomalieAffichee(val id: Long, val agent: String, val jour: LocalDate, val type: TypeAnomalie)

data class EntreeJournal(
    val date: Instant, val action: String, val pointageId: Long, val auteur: String,
    val ancienneValeur: String?, val nouvelleValeur: String?, val motif: String?,
)

sealed interface ResultatConnexion {
    data object Ok : ResultatConnexion
    data class Incorrect(val essaisRestants: Int) : ResultatConnexion
    data class Bloque(val jusqua: Instant) : ResultatConnexion
    data object NonConfigure : ResultatConnexion
}

sealed interface ResultatReinitialisation {
    /** [nouveauCodeSecours] est affiché une seule fois : l'ancien est invalidé. */
    data class Ok(val nouveauCodeSecours: String) : ResultatReinitialisation
    data class CodeIncorrect(val essaisRestants: Int) : ResultatReinitialisation
    data class Bloque(val jusqua: Instant) : ResultatReinitialisation
    data object MotDePasseInvalide : ResultatReinitialisation
}

/**
 * Tout ce que fait l'administrateur. Aucune méthode ne supprime un pointage (EF-12) :
 * une erreur se corrige par modification, et chaque modification est écrite au journal (EF-13).
 */
class DepotAdmin(
    private val base: BaseDeDonnees,
    private val horloge: Clock,
    private val fuseau: ZoneId,
) {
    private fun maintenant(): Instant = Instant.now(horloge)

    // --- Mot de passe, code de secours -----------------------------------------------------

    suspend fun estConfigure(): Boolean = base.parametreDao().valeur(CLE_ADMIN_EMPREINTE) != null

    /**
     * Première configuration : choisit le mot de passe et renvoie le code de secours, à noter sur
     * papier. Refuse si un mot de passe existe déjà (sinon n'importe qui pourrait le remplacer).
     */
    suspend fun configurerPremiereFois(motDePasse: String): String? {
        if (!motDePasseAdminValide(motDePasse)) return null
        // Les deux calculs PBKDF2 (environ 1 s chacun sur une tablette modeste) se font hors
        // transaction : sinon la base resterait verrouillée et un agent ne pourrait pas pointer.
        val motDePasseHache = hacherLentement(motDePasse)
        val code = CodeSecours.generer()
        val codeHache = hacherLentement(CodeSecours.normaliser(code))
        return base.withTransaction {
            if (estConfigure()) return@withTransaction null
            enregistrerEmpreintes(motDePasseHache, codeHache)
            code
        }
    }

    suspend fun connecter(motDePasse: String): ResultatConnexion {
        val (sel, empreinte) = base.withTransaction {
            if (!estConfigure()) return@withTransaction null
            etatTentatives().let { if (BlocageCode.estBloque(it, maintenant())) return@withTransaction null }
            base.parametreDao().valeur(CLE_ADMIN_SEL)!! to base.parametreDao().valeur(CLE_ADMIN_EMPREINTE)!!
        } ?: return when {
            !estConfigure() -> ResultatConnexion.NonConfigure
            else -> ResultatConnexion.Bloque(etatTentatives().bloqueJusqua ?: maintenant())
        }
        // Vérification hors transaction (calcul lent), puis on relit l'état pour enregistrer le résultat.
        val correct = withContext(Dispatchers.Default) { HachageCode.verifier(motDePasse, sel, empreinte) }
        return base.withTransaction {
            val maintenant = maintenant()
            if (correct) {
                enregistrerTentatives(BlocageCode.apresSucces())
                journaliserSecurite("CONNEXION_ADMIN_REUSSIE", null)
                ResultatConnexion.Ok
            } else {
                val apres = BlocageCode.apresEchec(etatTentatives(), maintenant)
                enregistrerTentatives(apres)
                journaliserSecurite("CONNEXION_ADMIN_ECHEC", null)
                if (apres.bloqueJusqua != null) ResultatConnexion.Bloque(apres.bloqueJusqua)
                else ResultatConnexion.Incorrect(BlocageCode.MAX_ECHECS - apres.echecsConsecutifs)
            }
        }
    }

    /**
     * EF-23. Le code de secours ne donne accès à aucune donnée : il remplace seulement le mot de
     * passe. Les essais ratés comptent dans le même blocage que ceux du mot de passe.
     */
    suspend fun reinitialiserMotDePasse(codeSecours: String, nouveauMotDePasse: String): ResultatReinitialisation {
        etatTentatives().let { if (BlocageCode.estBloque(it, maintenant())) return ResultatReinitialisation.Bloque(it.bloqueJusqua!!) }
        if (!motDePasseAdminValide(nouveauMotDePasse)) return ResultatReinitialisation.MotDePasseInvalide
        val sel = base.parametreDao().valeur(CLE_SECOURS_SEL) ?: return ResultatReinitialisation.CodeIncorrect(0)
        val empreinte = base.parametreDao().valeur(CLE_SECOURS_EMPREINTE) ?: return ResultatReinitialisation.CodeIncorrect(0)

        // Calculs lents hors transaction (voir configurerPremiereFois).
        val correct = withContext(Dispatchers.Default) { HachageCode.verifier(CodeSecours.normaliser(codeSecours), sel, empreinte) }
        if (!correct) {
            return base.withTransaction {
                val apres = BlocageCode.apresEchec(etatTentatives(), maintenant())
                enregistrerTentatives(apres)
                journaliserSecurite("CODE_SECOURS_ECHEC", null)
                if (apres.bloqueJusqua != null) ResultatReinitialisation.Bloque(apres.bloqueJusqua)
                else ResultatReinitialisation.CodeIncorrect(BlocageCode.MAX_ECHECS - apres.echecsConsecutifs)
            }
        }
        val motDePasseHache = hacherLentement(nouveauMotDePasse)
        val nouveau = CodeSecours.generer()
        val codeHache = hacherLentement(CodeSecours.normaliser(nouveau))
        return base.withTransaction {
            // Le code a pu être utilisé entre-temps par une autre saisie : on vérifie qu'il n'a pas changé.
            if (base.parametreDao().valeur(CLE_SECOURS_EMPREINTE) != empreinte) {
                return@withTransaction ResultatReinitialisation.CodeIncorrect(0)
            }
            enregistrerEmpreintes(motDePasseHache, codeHache)
            enregistrerTentatives(BlocageCode.apresSucces())
            journaliserSecurite("CODE_SECOURS_UTILISE", "mot de passe réinitialisé")
            ResultatReinitialisation.Ok(nouveau)
        }
    }

    // --- Agents ----------------------------------------------------------------------------

    suspend fun agents(): List<AgentAdmin> {
        val maintenant = maintenant()
        return base.agentDao().tous().map {
            val bloque = it.bloqueJusqua?.let { fin -> maintenant.toEpochMilli() < fin } ?: false
            AgentAdmin(it.id, it.nom, it.prenom, it.pole, it.actif, bloque)
        }
    }

    suspend fun creerAgent(nom: String, prenom: String, pole: String, code: String): Boolean {
        if (nom.isBlank() || prenom.isBlank() || !codeAgentValide(code)) return false
        val sel = HachageCode.genererSel()
        val empreinte = hacherEnArrierePlan(code, sel)
        base.agentDao().inserer(
            AgentEntite(nom = nom.trim(), prenom = prenom.trim(), pole = pole.trim(),
                codeSel = sel, codeEmpreinte = empreinte),
        )
        return true
    }

    /** Un agent n'est jamais supprimé : désactivé, il disparaît de l'accueil mais garde son historique. */
    suspend fun definirActif(agentId: Long, actif: Boolean) {
        val agent = base.agentDao().parId(agentId) ?: return
        base.agentDao().mettreAJour(agent.copy(actif = actif))
    }

    suspend fun changerCode(agentId: Long, nouveauCode: String): Boolean {
        if (!codeAgentValide(nouveauCode)) return false
        val agent = base.agentDao().parId(agentId) ?: return false
        val sel = HachageCode.genererSel()
        val empreinte = hacherEnArrierePlan(nouveauCode, sel)
        base.agentDao().mettreAJour(
            agent.copy(codeSel = sel, codeEmpreinte = empreinte,
                echecsConsecutifs = 0, bloqueJusqua = null),
        )
        return true
    }

    /**
     * PBKDF2 est volontairement lent (120 000 itérations). Appelé depuis le ViewModel, on serait
     * sur le fil de l'interface, qui gèlerait le temps du calcul : on le fait sur Dispatchers.Default.
     * Réservé aux codes agents (peu de tours) ; les autres hachages sont déjà dans withTransaction.
     */
    private suspend fun hacherEnArrierePlan(code: String, sel: String): String =
        withContext(Dispatchers.Default) { HachageCode.hacher(code, sel, HachageCode.TOURS_CODE_AGENT) }

    suspend fun debloquer(agentId: Long) {
        val agent = base.agentDao().parId(agentId) ?: return
        base.agentDao().mettreAJour(agent.copy(echecsConsecutifs = 0, bloqueJusqua = null))
    }

    // --- Pointages -------------------------------------------------------------------------

    /** Tous les pointages de la journée [jour], de tous les agents (EF-11), annulés compris. */
    suspend fun pointagesDuJour(jour: LocalDate): List<PointageAdmin> {
        val noms = base.agentDao().tous().associate { it.id to "${it.prenom} ${it.nom}" }
        return base.pointageDao().entre(debutDuJour(jour), debutDuJour(jour.plusDays(1))).map {
            PointageAdmin(it.versPointage(), noms[it.agentId] ?: "?", it.origine)
        }
    }

    suspend fun ajouterPointage(agentId: Long, type: TypePointage, horodatage: Instant, motif: String?): Boolean =
        base.withTransaction {
            if (base.agentDao().parId(agentId) == null || horodatage.isAfter(maintenant())) return@withTransaction false
            val entite = PointageEntite(
                agentId = agentId, type = type.name, horodatage = horodatage.toEpochMilli(),
                fuseau = fuseau.id, statut = StatutPointage.VALIDE.name, origine = "ADMIN",
            )
            val id = base.pointageDao().inserer(entite)
            base.journalDao().inserer(
                JournalEntite(pointageId = id, action = "CREATION_ADMIN", ancienneValeur = null,
                    nouvelleValeur = entite.enJson(), auteur = "ADMIN",
                    date = maintenant().toEpochMilli(), motif = motif?.takeIf { it.isNotBlank() }),
            )
            true
        }

    /**
     * Corrige un pointage : type, heure, et si besoin l'agent (pointage fait sur le mauvais profil)
     * ou le statut (annuler un pointage erroné, ou le rétablir). Rien n'est supprimé (EF-12) : l'ancienne
     * valeur reste au journal (EF-13). Si le pointage avait déjà été envoyé par e-mail, on le marque
     * « à renvoyer » : la sauvegarde déjà envoyée contient l'ancienne valeur, et la purge ne doit pas
     * le supprimer avant le prochain envoi. Refusé si l'heure est dans le futur ou l'agent inconnu.
     */
    suspend fun modifierPointage(
        pointageId: Long, type: TypePointage, horodatage: Instant, motif: String?,
        agentId: Long? = null, statut: StatutPointage? = null,
    ): Boolean =
        base.withTransaction {
            val avant = base.pointageDao().parId(pointageId) ?: return@withTransaction false
            if (horodatage.isAfter(maintenant())) return@withTransaction false
            if (agentId != null && base.agentDao().parId(agentId) == null) return@withTransaction false
            val apres = avant.copy(
                agentId = agentId ?: avant.agentId,
                type = type.name,
                horodatage = horodatage.toEpochMilli(),
                statut = (statut ?: StatutPointage.valueOf(avant.statut)).name,
                envoyeLe = null,
            )
            base.pointageDao().mettreAJour(apres)
            base.journalDao().inserer(
                JournalEntite(pointageId = pointageId, action = "MODIFICATION",
                    ancienneValeur = avant.enJson(), nouvelleValeur = apres.enJson(), auteur = "ADMIN",
                    date = maintenant().toEpochMilli(), motif = motif?.takeIf { it.isNotBlank() }),
            )
            true
        }

    /** Les [limite] dernières lignes du journal, la plus récente d'abord. Lecture seule (EF-13). */
    suspend fun journalRecent(limite: Int = 50): List<EntreeJournal> =
        base.journalDao().recents(limite).map {
            EntreeJournal(Instant.ofEpochMilli(it.date), it.action, it.pointageId, it.auteur,
                it.ancienneValeur, it.nouvelleValeur, it.motif)
        }

    /** Événements de sécurité récents (connexions, code de secours, blocages), le plus récent d'abord. */
    suspend fun evenementsSecurite(limite: Int = 50): List<EvenementSecurite> =
        base.securiteDao().recents(limite).map { EvenementSecurite(Instant.ofEpochMilli(it.date), it.type, it.detail) }

    // --- Anomalies -------------------------------------------------------------------------

    /** Détecte les oublis des journées [jours] (EF-09). Idempotent : renvoie le nombre de nouvelles anomalies. */
    suspend fun detecterAnomalies(jours: List<LocalDate>): Int = base.withTransaction {
        val maintenant = maintenant()
        var creees = 0
        for (jour in jours) {
            val parAgent = base.pointageDao().entre(debutDuJour(jour), debutDuJour(jour.plusDays(1)))
                .groupBy({ it.agentId }, { it.versPointage() })
            val existantes = base.anomalieDao().duJour(jour.toString())
                .map { Anomalie(it.agentId, jour, TypeAnomalie.valueOf(it.type)) }
            for (a in anomaliesANouvellementDetecter(parAgent, existantes, jour, fuseau)) {
                val id = base.anomalieDao().inserer(
                    AnomalieEntite(agentId = a.agentId, jour = a.jour.toString(), type = a.type.name,
                        detecteeLe = maintenant.toEpochMilli()),
                )
                if (id != -1L) creees++
            }
        }
        creees
    }

    suspend fun anomaliesOuvertes(): List<AnomalieAffichee> {
        val noms = base.agentDao().tous().associate { it.id to "${it.prenom} ${it.nom}" }
        return base.anomalieDao().ouvertes().map {
            AnomalieAffichee(it.id, noms[it.agentId] ?: "?", LocalDate.parse(it.jour), TypeAnomalie.valueOf(it.type))
        }
    }

    suspend fun marquerTraitee(anomalieId: Long) = base.anomalieDao().marquerTraitee(anomalieId)

    // --- Outils internes -------------------------------------------------------------------

    private fun debutDuJour(jour: LocalDate): Long = jour.atStartOfDay(fuseau).toInstant().toEpochMilli()

    /** Sel et empreinte (mot de passe admin ou code de secours), calculés sur un fil de calcul. */
    private class Empreinte(val sel: String, val valeur: String)

    private suspend fun hacherLentement(secret: String): Empreinte = withContext(Dispatchers.Default) {
        val sel = HachageCode.genererSel()
        Empreinte(sel, HachageCode.hacher(secret, sel))
    }

    private suspend fun enregistrerEmpreintes(motDePasse: Empreinte, codeSecours: Empreinte) {
        base.parametreDao().enregistrer(ParametreEntite(CLE_ADMIN_SEL, motDePasse.sel))
        base.parametreDao().enregistrer(ParametreEntite(CLE_ADMIN_EMPREINTE, motDePasse.valeur))
        base.parametreDao().enregistrer(ParametreEntite(CLE_SECOURS_SEL, codeSecours.sel))
        base.parametreDao().enregistrer(ParametreEntite(CLE_SECOURS_EMPREINTE, codeSecours.valeur))
    }

    private suspend fun etatTentatives(): EtatCode = EtatCode(
        base.parametreDao().valeur(CLE_ECHECS)?.toIntOrNull() ?: 0,
        // Chaîne vide = pas de blocage en cours (voir enregistrerTentatives).
        base.parametreDao().valeur(CLE_BLOQUE)?.toLongOrNull()?.let(Instant::ofEpochMilli),
    )

    private suspend fun enregistrerTentatives(etat: EtatCode) {
        base.parametreDao().enregistrer(ParametreEntite(CLE_ECHECS, etat.echecsConsecutifs.toString()))
        base.parametreDao().enregistrer(ParametreEntite(CLE_BLOQUE, etat.bloqueJusqua?.toEpochMilli()?.toString() ?: ""))
    }

    private suspend fun journaliserSecurite(type: String, detail: String?) {
        base.securiteDao().inserer(EvenementSecuriteEntite(type = type, date = maintenant().toEpochMilli(), detail = detail))
    }

    private companion object {
        const val CLE_ADMIN_SEL = "admin_sel"
        const val CLE_ADMIN_EMPREINTE = "admin_empreinte"
        const val CLE_SECOURS_SEL = "secours_sel"
        const val CLE_SECOURS_EMPREINTE = "secours_empreinte"
        const val CLE_ECHECS = "admin_echecs"
        const val CLE_BLOQUE = "admin_bloque"
    }
}

internal fun PointageEntite.versPointage() = Pointage(
    id, agentId, TypePointage.valueOf(type), Instant.ofEpochMilli(horodatage),
    StatutPointage.valueOf(statut), envoyeLe?.let(Instant::ofEpochMilli),
)

// L'agent fait partie de la valeur : une réattribution à un autre agent doit se lire dans le journal.
private fun PointageEntite.enJson(): String = JSONObject()
    .put("agentId", agentId).put("type", type).put("horodatage", horodatage).put("statut", statut).put("origine", origine)
    .toString()
