package fr.mairie.badgeuse.domaine

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Temps d'une journée d'un agent. [complet] est faux si la journée contient une période restée
 * ouverte (entrée sans sortie, pause sans fin) ou un enchaînement incohérent : la partie non
 * fermée n'est pas comptée, et l'administrateur doit la vérifier plutôt que de croire le total.
 */
data class TempsJournee(val travail: Duration, val pause: Duration, val complet: Boolean)

/**
 * Calcule le temps de travail de [jour] à partir des pointages valides (les annulés sont ignorés).
 * Le travail court d'une entrée (ou d'une fin de pause) jusqu'au début de pause ou à la sortie
 * suivante. Une sortie pendant une pause est permise : elle termine la pause, sans travail compté.
 * Plusieurs entrées et sorties dans la journée s'additionnent. Renvoie null si aucun pointage valide.
 * Rien ne déborde sur le jour suivant : un agent ne travaille pas après minuit (EF-05).
 */
fun tempsDeLaJournee(pointages: List<Pointage>, jour: LocalDate, fuseau: ZoneId): TempsJournee? {
    val duJour = pointages
        .filter { it.statut == StatutPointage.VALIDE && jourLocal(it.horodatage, fuseau) == jour }
        .sortedWith(compareBy({ it.horodatage }, { it.id }))
    if (duJour.isEmpty()) return null

    var travail = Duration.ZERO
    var pause = Duration.ZERO
    var coherent = true
    var debutTravail: java.time.Instant? = null
    var debutPause: java.time.Instant? = null

    for (p in duJour) {
        val t = p.horodatage
        when (p.type) {
            TypePointage.ENTREE ->
                if (debutTravail == null && debutPause == null) debutTravail = t else coherent = false
            TypePointage.DEBUT_PAUSE -> {
                val debut = debutTravail
                if (debut != null) {
                    travail += Duration.between(debut, t)
                    debutTravail = null
                    debutPause = t
                } else {
                    coherent = false
                }
            }
            TypePointage.FIN_PAUSE -> {
                val debut = debutPause
                if (debut != null) {
                    pause += Duration.between(debut, t)
                    debutPause = null
                    debutTravail = t
                } else {
                    coherent = false
                }
            }
            TypePointage.SORTIE -> {
                val travailEnCours = debutTravail
                val pauseEnCours = debutPause
                when {
                    travailEnCours != null -> travail += Duration.between(travailEnCours, t)
                    pauseEnCours != null -> pause += Duration.between(pauseEnCours, t)
                    else -> coherent = false
                }
                debutTravail = null
                debutPause = null
            }
        }
    }
    val fermee = debutTravail == null && debutPause == null
    return TempsJournee(travail, pause, coherent && fermee)
}

/** « 7 h 05 », lisible à l'écran et dans le PDF. */
fun formaterDuree(duree: Duration): String {
    val minutes = duree.toMinutes()
    return "%d h %02d".format(minutes / 60, minutes % 60)
}

/** « 7:05 » : format qu'un tableur reconnaît comme une durée et sait additionner. */
fun formaterDureeTableur(duree: Duration): String {
    val minutes = duree.toMinutes()
    return "%d:%02d".format(minutes / 60, minutes % 60)
}

/** Heures décimales avec une virgule (« 7,08 »), pour les calculs dans un tableur français. */
fun formaterHeuresDecimales(duree: Duration): String =
    "%.2f".format(java.util.Locale.FRANCE, duree.toMinutes() / 60.0)
