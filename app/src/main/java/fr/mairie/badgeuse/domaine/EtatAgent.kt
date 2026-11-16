package fr.mairie.badgeuse.domaine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class EtatAgent { HORS_SERVICE, EN_SERVICE, EN_PAUSE }

/** Jour local d'un instant, dans le fuseau donné. */
fun jourLocal(instant: Instant, fuseau: ZoneId): LocalDate =
    instant.atZone(fuseau).toLocalDate()

/** Heure locale affichée à l'agent ou exportée. */
fun heureLocale(instant: Instant, fuseau: ZoneId): LocalTime =
    instant.atZone(fuseau).toLocalTime()

/**
 * Dernier pointage valide du jour [jour]. Les pointages annulés sont ignorés.
 * On trie par instant, puis par identifiant si deux pointages ont le même instant :
 * l'ordre de la liste reçue n'est pas fiable.
 */
fun dernierPointageValide(
    pointages: List<Pointage>,
    jour: LocalDate,
    fuseau: ZoneId,
): Pointage? = pointages
    .filter { it.statut == StatutPointage.VALIDE && jourLocal(it.horodatage, fuseau) == jour }
    .maxWithOrNull(compareBy({ it.horodatage }, { it.id }))

/** L'état se déduit du dernier pointage valide de la journée en cours (architecture, section 3). */
fun etatDeLAgent(
    pointagesDeLAgent: List<Pointage>,
    maintenant: Instant,
    fuseau: ZoneId,
): EtatAgent {
    val dernier = dernierPointageValide(pointagesDeLAgent, jourLocal(maintenant, fuseau), fuseau)
    return when (dernier?.type) {
        null, TypePointage.SORTIE -> EtatAgent.HORS_SERVICE
        TypePointage.ENTREE, TypePointage.FIN_PAUSE -> EtatAgent.EN_SERVICE
        TypePointage.DEBUT_PAUSE -> EtatAgent.EN_PAUSE
    }
}

/**
 * Actions proposées à l'agent selon son état (EF-05).
 * Décision de la mairie (05/11/2026) : on peut sortir pendant une pause ; la pause se termine alors
 * à la sortie. L'état repart de zéro chaque jour : un agent ne travaille pas après minuit, une
 * journée restée ouverte devient une anomalie (EF-09).
 */
fun actionsAutorisees(etat: EtatAgent): Set<TypePointage> = when (etat) {
    EtatAgent.HORS_SERVICE -> setOf(TypePointage.ENTREE)
    EtatAgent.EN_SERVICE -> setOf(TypePointage.DEBUT_PAUSE, TypePointage.SORTIE)
    EtatAgent.EN_PAUSE -> setOf(TypePointage.FIN_PAUSE, TypePointage.SORTIE)
}
