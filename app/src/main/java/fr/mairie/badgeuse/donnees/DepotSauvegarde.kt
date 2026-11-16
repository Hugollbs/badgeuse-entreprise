package fr.mairie.badgeuse.donnees

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypeAnomalie
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.domaine.DUREE_CONSERVATION_MOIS
import fr.mairie.badgeuse.domaine.dureeConservationValide
import fr.mairie.badgeuse.domaine.pointagesAPurger
import fr.mairie.badgeuse.export.LigneExport
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Le fichier de sauvegarde et les pointages non envoyés qu'il contient (voir [DepotSauvegarde.confirmerEnvoi]). */
class FichierSauvegarde(val contenu: String, internal val nonEnvoyes: List<PointageEntite>)

data class Compteurs(val agents: Int, val pointages: Int, val journal: Int, val anomalies: Int)

sealed interface ResultatRestauration {
    data class Ok(val compteurs: Compteurs) : ResultatRestauration

    /** On ne restaure jamais par-dessus des données existantes : elles seraient mélangées ou perdues. */
    data object BaseNonVide : ResultatRestauration
    data object Invalide : ResultatRestauration
}

/**
 * Export, sauvegarde, restauration et purge (EF-16, EF-17, EF-19, EF-20, rétention).
 *
 * L'envoi par e-mail n'est pas ici : l'appelant fait [preparerSauvegarde], envoie le fichier, puis
 * appelle [confirmerEnvoi] si l'envoi a réussi, ou [enregistrerEchec] sinon. Un pointage n'est donc
 * jamais marqué « envoyé » (donc jamais purgeable) tant que l'envoi n'a pas réellement abouti.
 */
class DepotSauvegarde(
    private val base: BaseDeDonnees,
    private val horloge: Clock,
    private val fuseau: ZoneId,
) {
    private fun maintenant(): Instant = Instant.now(horloge)

    // --- Export ----------------------------------------------------------------------------

    /**
     * Pointages de [debut] à [fin] inclus, pour les agents [agentIds] (un, plusieurs, ou tous si
     * l'ensemble est vide, EF-17). Annulés compris.
     */
    suspend fun lignesExport(agentIds: Set<Long>, debut: LocalDate, fin: LocalDate): List<LigneExport> {
        val deMs = debut.atStartOfDay(fuseau).toInstant().toEpochMilli()
        val aMs = fin.plusDays(1).atStartOfDay(fuseau).toInstant().toEpochMilli()
        val agents = base.agentDao().tous().associateBy { it.id }
        val pointages = base.pointageDao().entre(deMs, aMs).filter { agentIds.isEmpty() || it.agentId in agentIds }
        return pointages.map {
            val agent = agents[it.agentId]
            LigneExport(
                agent = agent?.let { a -> "${a.prenom} ${a.nom}" } ?: "?",
                pole = agent?.pole.orEmpty(),
                horodatage = Instant.ofEpochMilli(it.horodatage),
                type = TypePointage.valueOf(it.type),
                statut = StatutPointage.valueOf(it.statut),
                origine = it.origine,
                agentId = it.agentId,
            )
        }
    }

    suspend fun nomsAgents(agentIds: Set<Long>): List<String> =
        agentIds.mapNotNull { id -> base.agentDao().parId(id)?.let { "${it.prenom} ${it.nom}" } }.sorted()

    // --- Durée de conservation ----------------------------------------------------------------

    /** Durée, en mois, pendant laquelle un pointage déjà sauvegardé reste sur la tablette. */
    suspend fun dureeConservationMois(): Long =
        base.parametreDao().valeur(CLE_CONSERVATION)?.toLongOrNull()?.takeIf(::dureeConservationValide)
            ?: DUREE_CONSERVATION_MOIS

    /** Change la durée de conservation ; le changement est tracé au journal de sécurité. */
    suspend fun definirDureeConservation(mois: Long): Boolean {
        if (!dureeConservationValide(mois)) return false
        base.withTransaction {
            base.parametreDao().enregistrer(ParametreEntite(CLE_CONSERVATION, mois.toString()))
            base.securiteDao().inserer(
                EvenementSecuriteEntite(type = "CONSERVATION_MODIFIEE", date = maintenant().toEpochMilli(), detail = "$mois mois"),
            )
        }
        return true
    }

    // --- Sauvegarde ------------------------------------------------------------------------

    /**
     * Photographie de toute la base, lue dans une seule transaction pour rester cohérente. Le mot de
     * passe administrateur et le code de secours n'en font pas partie : une restauration ne doit
     * pas pouvoir changer qui administre la tablette.
     */
    suspend fun preparerSauvegarde(): FichierSauvegarde = base.withTransaction {
        val pointages = base.pointageDao().tous()
        val json = JSONObject()
            .put("version", VERSION_SAUVEGARDE)
            .put("creeeLe", maintenant().toEpochMilli())
            .put("agents", JSONArray(base.agentDao().tous().map { it.versJson() }))
            .put("pointages", JSONArray(pointages.map { it.versJson() }))
            .put("journal", JSONArray(base.journalDao().tous().map { it.versJson() }))
            .put("anomalies", JSONArray(base.anomalieDao().toutes().map { it.versJson() }))
        FichierSauvegarde(json.toString(), pointages.filter { it.envoyeLe == null })
    }

    /**
     * À appeler quand le fichier a bien été envoyé. Ne marque « envoyés » que les pointages tels
     * qu'ils étaient dans le fichier : un pointage créé ou modifié pendant l'envoi n'y figure pas
     * (ou avec l'ancienne valeur), il doit repartir à la prochaine sauvegarde. Lance ensuite la
     * purge et renvoie le nombre de pointages supprimés.
     */
    suspend fun confirmerEnvoi(fichier: FichierSauvegarde): Int = base.withTransaction {
        val date = maintenant().toEpochMilli()
        val inchanges = fichier.nonEnvoyes.filter { base.pointageDao().parId(it.id) == it }.map { it.id }
        inchanges.chunked(TAILLE_LOT).forEach { base.pointageDao().marquerEnvoyesParIds(it, date) }
        base.sauvegardeDao().inserer(SauvegardeEntite(date = date, statut = STATUT_REUSSIE))
        purger()
    }

    /** Trace l'échec pour l'alerte de l'administrateur (T-32). Les pointages restent « non envoyés ». */
    suspend fun enregistrerEchec(message: String) {
        base.sauvegardeDao().inserer(
            SauvegardeEntite(date = maintenant().toEpochMilli(), statut = STATUT_ECHEC, messageErreur = message),
        )
    }

    suspend fun sauvegardesRecentes(limite: Int = 10): List<SauvegardeEntite> = base.sauvegardeDao().recentes(limite)

    // --- Purge -----------------------------------------------------------------------------

    /**
     * Supprime de la tablette les pointages déjà envoyés et plus vieux que la durée de conservation
     * (T-34 à T-36), et leurs lignes de journal : elles portent sur les mêmes données personnelles,
     * donc la même durée de conservation, et la clé étrangère interdit de supprimer le pointage tant
     * que son journal existe. Les sauvegardes reçues par e-mail, elles, les conservent.
     */
    suspend fun purger(): Int = base.withTransaction {
        val envoyes = base.pointageDao().dejaEnvoyes().map { it.versPointage() }
        val ids = pointagesAPurger(envoyes, maintenant(), fuseau, dureeConservationMois()).map { it.id }
        // SQLite limite le nombre de paramètres d'une requête : on supprime par lots.
        ids.chunked(TAILLE_LOT).forEach {
            base.journalDao().supprimerPourPurge(it)
            base.pointageDao().supprimerPourPurge(it)
        }
        ids.size
    }

    // --- Restauration ----------------------------------------------------------------------

    /** Recharge une sauvegarde dans une base vide (T-33). Tout ou rien : à la moindre erreur, rien n'est écrit. */
    suspend fun restaurer(json: String): ResultatRestauration {
        val contenu = try {
            lire(json)
        } catch (e: JSONException) {
            return ResultatRestauration.Invalide
        } catch (e: IllegalArgumentException) {
            return ResultatRestauration.Invalide
        }
        return try {
            base.withTransaction {
                if (base.agentDao().tous().isNotEmpty() || base.pointageDao().compter() > 0) {
                    return@withTransaction ResultatRestauration.BaseNonVide
                }
                // Dans l'ordre des clés étrangères : agents, puis pointages, puis ce qui en dépend.
                // Les identifiants sont conservés pour que le journal et les anomalies restent reliés.
                contenu.agents.forEach { base.agentDao().inserer(it) }
                contenu.pointages.forEach { base.pointageDao().inserer(it) }
                contenu.journal.forEach { base.journalDao().inserer(it) }
                contenu.anomalies.forEach { base.anomalieDao().inserer(it) }
                ResultatRestauration.Ok(
                    Compteurs(contenu.agents.size, contenu.pointages.size, contenu.journal.size, contenu.anomalies.size),
                )
            }
        } catch (e: SQLiteConstraintException) {
            // Référence vers un agent ou un pointage absent du fichier : fichier incohérent, transaction annulée.
            ResultatRestauration.Invalide
        }
    }

    private companion object {
        const val STATUT_REUSSIE = "REUSSIE"
        const val STATUT_ECHEC = "ECHEC"
        const val TAILLE_LOT = 500
        const val CLE_CONSERVATION = "conservation_mois"
    }
}

private const val VERSION_SAUVEGARDE = 1

private class ContenuSauvegarde(
    val agents: List<AgentEntite>,
    val pointages: List<PointageEntite>,
    val journal: List<JournalEntite>,
    val anomalies: List<AnomalieEntite>,
)

private fun lire(json: String): ContenuSauvegarde {
    val racine = JSONObject(json)
    require(racine.getInt("version") == VERSION_SAUVEGARDE) { "version de sauvegarde non prise en charge" }
    return ContenuSauvegarde(
        agents = racine.getJSONArray("agents").objets().map { it.enAgent() },
        pointages = racine.getJSONArray("pointages").objets().map { it.enPointage() },
        journal = racine.getJSONArray("journal").objets().map { it.enJournal() },
        anomalies = racine.getJSONArray("anomalies").objets().map { it.enAnomalie() },
    )
}

private fun JSONArray.objets(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

// Une valeur vide est simplement absente du JSON (put(cle, null) supprime la clé).
private fun JSONObject.longOuNull(cle: String): Long? = if (isNull(cle)) null else getLong(cle)
private fun JSONObject.texteOuNull(cle: String): String? = if (isNull(cle)) null else getString(cle)

private fun AgentEntite.versJson() = JSONObject()
    .put("id", id).put("nom", nom).put("prenom", prenom).put("pole", pole)
    .put("codeEmpreinte", codeEmpreinte).put("codeSel", codeSel).put("actif", actif)
    .put("echecsConsecutifs", echecsConsecutifs).put("bloqueJusqua", bloqueJusqua)

private fun JSONObject.enAgent() = AgentEntite(
    id = getLong("id"), nom = getString("nom"), prenom = getString("prenom"), pole = getString("pole"),
    codeEmpreinte = getString("codeEmpreinte"), codeSel = getString("codeSel"), actif = getBoolean("actif"),
    echecsConsecutifs = getInt("echecsConsecutifs"), bloqueJusqua = longOuNull("bloqueJusqua"),
)

private fun PointageEntite.versJson() = JSONObject()
    .put("id", id).put("agentId", agentId).put("type", type).put("horodatage", horodatage)
    .put("fuseau", fuseau).put("statut", statut).put("origine", origine).put("envoyeLe", envoyeLe)

private fun JSONObject.enPointage(): PointageEntite {
    val type = getString("type")
    val statut = getString("statut")
    // valueOf refuse une valeur inconnue : mieux vaut rejeter le fichier que casser l'application plus tard.
    TypePointage.valueOf(type)
    StatutPointage.valueOf(statut)
    return PointageEntite(
        id = getLong("id"), agentId = getLong("agentId"), type = type, horodatage = getLong("horodatage"),
        fuseau = getString("fuseau"), statut = statut, origine = getString("origine"), envoyeLe = longOuNull("envoyeLe"),
    )
}

private fun JournalEntite.versJson() = JSONObject()
    .put("id", id).put("pointageId", pointageId).put("action", action)
    .put("ancienneValeur", ancienneValeur).put("nouvelleValeur", nouvelleValeur)
    .put("auteur", auteur).put("date", date).put("motif", motif)

private fun JSONObject.enJournal() = JournalEntite(
    id = getLong("id"), pointageId = getLong("pointageId"), action = getString("action"),
    ancienneValeur = texteOuNull("ancienneValeur"), nouvelleValeur = texteOuNull("nouvelleValeur"),
    auteur = getString("auteur"), date = getLong("date"), motif = texteOuNull("motif"),
)

private fun AnomalieEntite.versJson() = JSONObject()
    .put("id", id).put("agentId", agentId).put("jour", jour).put("type", type)
    .put("statut", statut).put("detecteeLe", detecteeLe)

private fun JSONObject.enAnomalie(): AnomalieEntite {
    val type = getString("type")
    TypeAnomalie.valueOf(type)
    return AnomalieEntite(
        id = getLong("id"), agentId = getLong("agentId"), jour = getString("jour"),
        type = type, statut = getString("statut"), detecteeLe = getLong("detecteeLe"),
    )
}
