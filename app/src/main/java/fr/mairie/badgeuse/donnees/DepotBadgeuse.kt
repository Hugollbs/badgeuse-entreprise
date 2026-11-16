package fr.mairie.badgeuse.donnees

import androidx.room.withTransaction
import fr.mairie.badgeuse.domaine.BlocageCode
import fr.mairie.badgeuse.domaine.EtatAgent
import fr.mairie.badgeuse.domaine.EtatCode
import fr.mairie.badgeuse.domaine.Pointage
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.domaine.actionsAutorisees
import fr.mairie.badgeuse.domaine.dernierPointageValide
import fr.mairie.badgeuse.domaine.etatDeLAgent
import fr.mairie.badgeuse.domaine.jourLocal
import fr.mairie.badgeuse.domaine.peutAnnuler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Ce que l'interface a le droit de connaître d'un agent : jamais l'empreinte de son code. */
data class AgentAffiche(val id: Long, val nom: String, val prenom: String, val pole: String)

sealed interface ResultatCode {
    data class Valide(val agent: AgentAffiche) : ResultatCode
    data class Incorrect(val essaisRestants: Int) : ResultatCode
    data class Bloque(val jusqua: Instant) : ResultatCode
    data object AgentIntrouvable : ResultatCode
}

/**
 * Point d'accès unique aux données pour l'espace agent. Les règles viennent du paquet `domaine` ;
 * ici on les applique à la base, dans des transactions pour que deux appuis rapides sur un bouton
 * ne puissent pas créer deux pointages contradictoires.
 */
class DepotBadgeuse(
    private val base: BaseDeDonnees,
    private val horloge: Clock,
    private val fuseau: ZoneId,
) {
    private fun maintenant(): Instant = Instant.now(horloge)

    suspend fun agentsActifs(): List<AgentAffiche> =
        base.agentDao().actifs().map { it.versAffiche() }

    fun agentsActifsEnContinu(): Flow<List<AgentAffiche>> =
        base.agentDao().actifsEnContinu().map { liste -> liste.map { it.versAffiche() } }

    suspend fun creerAgent(nom: String, prenom: String, pole: String, code: String): Long {
        val sel = HachageCode.genererSel()
        return base.agentDao().inserer(
            AgentEntite(
                nom = nom, prenom = prenom, pole = pole,
                codeSel = sel, codeEmpreinte = HachageCode.hacher(code, sel, HachageCode.TOURS_CODE_AGENT),
            ),
        )
    }

    suspend fun verifierCode(agentId: Long, code: String): ResultatCode = base.withTransaction {
        // Un agent désactivé pendant qu'il était sur l'écran du code ne doit plus pouvoir entrer.
        val agent = base.agentDao().parId(agentId)?.takeIf { it.actif } ?: return@withTransaction ResultatCode.AgentIntrouvable
        val maintenant = maintenant()
        val etat = EtatCode(agent.echecsConsecutifs, agent.bloqueJusqua?.let(Instant::ofEpochMilli))

        if (BlocageCode.estBloque(etat, maintenant)) {
            return@withTransaction ResultatCode.Bloque(etat.bloqueJusqua!!)
        }
        if (HachageCode.verifier(code, agent.codeSel, agent.codeEmpreinte)) {
            enregistrerEtatCode(agent, BlocageCode.apresSucces())
            return@withTransaction ResultatCode.Valide(agent.versAffiche())
        }
        val apres = BlocageCode.apresEchec(etat, maintenant)
        enregistrerEtatCode(agent, apres)
        if (apres.bloqueJusqua != null) {
            base.securiteDao().inserer(
                EvenementSecuriteEntite(
                    type = "BLOCAGE_AGENT", date = maintenant.toEpochMilli(), detail = "agent ${agent.id}",
                ),
            )
            ResultatCode.Bloque(apres.bloqueJusqua)
        } else {
            ResultatCode.Incorrect(BlocageCode.MAX_ECHECS - apres.echecsConsecutifs)
        }
    }

    suspend fun etatDeLAgent(agentId: Long): EtatAgent {
        val maintenant = maintenant()
        return etatDeLAgent(pointagesDuJour(agentId, maintenant), maintenant, fuseau)
    }

    /** Enregistre un pointage à l'heure de la tablette, ou renvoie null si l'action n'est pas permise. */
    suspend fun pointer(agentId: Long, type: TypePointage): Pointage? = base.withTransaction {
        if (base.agentDao().parId(agentId)?.actif != true) return@withTransaction null
        val maintenant = maintenant()
        val etat = etatDeLAgent(pointagesDuJour(agentId, maintenant), maintenant, fuseau)
        if (type !in actionsAutorisees(etat)) return@withTransaction null

        val entite = PointageEntite(
            agentId = agentId, type = type.name, horodatage = maintenant.toEpochMilli(),
            fuseau = fuseau.id, statut = StatutPointage.VALIDE.name, origine = "AGENT",
        )
        entite.copy(id = base.pointageDao().inserer(entite)).versDomaine()
    }

    /**
     * Annule un pointage dans les 15 secondes. Seul le dernier pointage valide de la journée peut
     * être annulé, pour que l'état de l'agent revienne bien à ce qu'il était. La ligne reste en
     * base avec le statut « annulé », et le journal garde la trace (EF-06b).
     */
    suspend fun annuler(agentId: Long, pointageId: Long): Boolean = base.withTransaction {
        val maintenant = maintenant()
        val entite = base.pointageDao().parId(pointageId) ?: return@withTransaction false
        if (entite.agentId != agentId) return@withTransaction false

        val dernier = dernierPointageValide(pointagesDuJour(agentId, maintenant), jourLocal(maintenant, fuseau), fuseau)
        if (dernier?.id != pointageId || !peutAnnuler(entite.versDomaine(), maintenant)) {
            return@withTransaction false
        }
        val annule = entite.copy(statut = StatutPointage.ANNULE.name)
        base.pointageDao().mettreAJour(annule)
        base.journalDao().inserer(
            JournalEntite(
                pointageId = pointageId, action = "ANNULATION",
                ancienneValeur = entite.enJson(), nouvelleValeur = annule.enJson(),
                auteur = "AGENT", date = maintenant.toEpochMilli(),
            ),
        )
        true
    }

    /** Derniers pointages de cet agent uniquement (T-15), annulés compris. */
    suspend fun historique(agentId: Long, limite: Int = 10): List<Pointage> =
        base.pointageDao().derniersDeLAgent(agentId, limite).map { it.versDomaine() }

    /** Tous les pointages encore sur la tablette, de cet agent uniquement (EF-07, EF-08), du plus récent au plus ancien. */
    suspend fun tousLesPointages(agentId: Long): List<Pointage> =
        base.pointageDao().deLAgent(agentId).map { it.versDomaine() }

    private suspend fun pointagesDuJour(agentId: Long, maintenant: Instant): List<Pointage> {
        val jour = jourLocal(maintenant, fuseau)
        val debut = jour.atStartOfDay(fuseau).toInstant().toEpochMilli()
        val fin = jour.plusDays(1).atStartOfDay(fuseau).toInstant().toEpochMilli()
        return base.pointageDao().deLAgentEntre(agentId, debut, fin).map { it.versDomaine() }
    }

    private suspend fun enregistrerEtatCode(agent: AgentEntite, etat: EtatCode) {
        base.agentDao().mettreAJour(
            agent.copy(echecsConsecutifs = etat.echecsConsecutifs, bloqueJusqua = etat.bloqueJusqua?.toEpochMilli()),
        )
    }
}

private fun AgentEntite.versAffiche() = AgentAffiche(id, nom, prenom, pole)

private fun PointageEntite.versDomaine() = Pointage(
    id = id,
    agentId = agentId,
    type = TypePointage.valueOf(type),
    horodatage = Instant.ofEpochMilli(horodatage),
    statut = StatutPointage.valueOf(statut),
    envoyeLe = envoyeLe?.let(Instant::ofEpochMilli),
)

private fun PointageEntite.enJson(): String = JSONObject()
    .put("type", type).put("horodatage", horodatage).put("statut", statut).put("origine", origine)
    .toString()
