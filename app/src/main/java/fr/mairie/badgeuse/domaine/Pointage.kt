package fr.mairie.badgeuse.domaine

import java.time.Instant

enum class TypePointage { ENTREE, DEBUT_PAUSE, FIN_PAUSE, SORTIE }

enum class StatutPointage { VALIDE, ANNULE }

/**
 * Un pointage tel que les règles métier le voient. Ce n'est pas l'entité Room :
 * garder les deux séparées permet de tester les règles sans base de données.
 *
 * [horodatage] est un instant absolu (UTC). L'heure locale s'obtient avec un fuseau,
 * ce qui évite les erreurs au changement d'heure été/hiver.
 */
data class Pointage(
    val id: Long,
    val agentId: Long,
    val type: TypePointage,
    val horodatage: Instant,
    val statut: StatutPointage = StatutPointage.VALIDE,
    /** Renseigné quand une sauvegarde par e-mail contenant ce pointage a réussi. */
    val envoyeLe: Instant? = null,
)
