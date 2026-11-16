package fr.mairie.badgeuse.domaine

import java.time.Duration
import java.time.Instant

val DELAI_ANNULATION: Duration = Duration.ofSeconds(15)

/** L'agent peut annuler son pointage pendant 15 secondes seulement (EF-06b). */
fun peutAnnuler(pointage: Pointage, maintenant: Instant): Boolean =
    pointage.statut == StatutPointage.VALIDE &&
        !maintenant.isBefore(pointage.horodatage) &&
        Duration.between(pointage.horodatage, maintenant) <= DELAI_ANNULATION
