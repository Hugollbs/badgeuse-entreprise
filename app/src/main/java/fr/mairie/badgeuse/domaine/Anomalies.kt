package fr.mairie.badgeuse.domaine

import java.time.LocalDate
import java.time.ZoneId

enum class TypeAnomalie { SORTIE_MANQUANTE, PAUSE_NON_TERMINEE }

data class Anomalie(
    val agentId: Long,
    val jour: LocalDate,
    val type: TypeAnomalie,
)

/**
 * Anomalie d'un agent pour la journée [jour], d'après son dernier pointage valide (EF-09).
 * Renvoie null si la journée est complète ou si l'agent n'a pas pointé.
 * Les pointages ne sont jamais modifiés : seule la DGS décide de la suite.
 */
fun anomalieDeLaJournee(
    agentId: Long,
    pointagesDeLAgent: List<Pointage>,
    jour: LocalDate,
    fuseau: ZoneId,
): Anomalie? {
    val type = when (dernierPointageValide(pointagesDeLAgent, jour, fuseau)?.type) {
        TypePointage.ENTREE, TypePointage.FIN_PAUSE -> TypeAnomalie.SORTIE_MANQUANTE
        TypePointage.DEBUT_PAUSE -> TypeAnomalie.PAUSE_NON_TERMINEE
        TypePointage.SORTIE, null -> return null
    }
    return Anomalie(agentId, jour, type)
}

/**
 * Anomalies à enregistrer pour [jour], tous agents confondus.
 * On retire celles déjà en base : relancer la détection ne crée pas de doublon (T-19).
 */
fun anomaliesANouvellementDetecter(
    pointagesParAgent: Map<Long, List<Pointage>>,
    existantes: List<Anomalie>,
    jour: LocalDate,
    fuseau: ZoneId,
): List<Anomalie> = pointagesParAgent
    .mapNotNull { (agentId, pointages) -> anomalieDeLaJournee(agentId, pointages, jour, fuseau) }
    .filter { it !in existantes }
