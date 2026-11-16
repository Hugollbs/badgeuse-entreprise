package fr.mairie.badgeuse.domaine

import java.time.Instant
import java.time.ZoneId

/** Durée de conservation sur la tablette par défaut ; l'administrateur peut la changer. */
const val DUREE_CONSERVATION_MOIS = 4L
const val DUREE_CONSERVATION_MIN_MOIS = 1L
const val DUREE_CONSERVATION_MAX_MOIS = 60L

fun dureeConservationValide(mois: Long) = mois in DUREE_CONSERVATION_MIN_MOIS..DUREE_CONSERVATION_MAX_MOIS

/**
 * Pointages à supprimer de la tablette après une sauvegarde réussie (durée de conservation).
 * Condition double : plus vieux que [dureeMois] mois ET déjà envoyé par e-mail : la sauvegarde
 * reçue par la mairie les garde. Un pointage jamais envoyé n'est jamais supprimé (T-35).
 */
fun pointagesAPurger(
    pointages: List<Pointage>,
    maintenant: Instant,
    fuseau: ZoneId,
    dureeMois: Long = DUREE_CONSERVATION_MOIS,
): List<Pointage> {
    val limite = maintenant.atZone(fuseau).minusMonths(dureeMois).toInstant()
    return pointages.filter { it.envoyeLe != null && it.horodatage.isBefore(limite) }
}
