package fr.mairie.badgeuse.export

import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.ui.libelle
import fr.mairie.badgeuse.domaine.Pointage
import fr.mairie.badgeuse.domaine.formaterDureeTableur
import fr.mairie.badgeuse.domaine.formaterHeuresDecimales
import fr.mairie.badgeuse.domaine.jourLocal
import fr.mairie.badgeuse.domaine.tempsDeLaJournee
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Une ligne d'export : un pointage avec le nom de son agent, prêt à être écrit dans un fichier. */
data class LigneExport(
    val agent: String,
    val pole: String,
    val horodatage: Instant,
    val type: TypePointage,
    val statut: StatutPointage,
    val origine: String,
    /** Identifiant de l'agent, pour regrouper ses pointages (deux agents peuvent porter le même nom). */
    val agentId: Long = 0,
)

val ENTETES_EXPORT = listOf("Agent", "Pôle", "Date", "Heure", "Type", "Statut", "Origine")

internal val FORMAT_DATE_EXPORT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val FORMAT_HEURE_EXPORT = DateTimeFormatter.ofPattern("HH:mm")

fun StatutPointage.libelleExport() = when (this) {
    StatutPointage.VALIDE -> "Valide"
    StatutPointage.ANNULE -> "Annulé"
}

/** Les cellules d'une ligne, avec la date et l'heure en heure locale du fuseau [fuseau] (T-41). */
fun LigneExport.cellules(fuseau: ZoneId): List<String> {
    val local = horodatage.atZone(fuseau)
    return listOf(
        agent, pole, local.format(FORMAT_DATE_EXPORT), local.format(FORMAT_HEURE_EXPORT),
        type.libelle(), statut.libelleExport(), origine,
    )
}

/** Tri stable et lisible : par date, puis par agent. */
fun List<LigneExport>.triees(): List<LigneExport> = sortedWith(compareBy({ it.horodatage }, { it.agent }))

private const val SEPARATEUR = ";"

// BOM UTF-8 : sans lui, Excel lit le fichier en ANSI et abîme les accents.
private const val BOM_UTF8 = "\uFEFF"

/**
 * CSV prévu pour Excel en français : séparateur « ; », fin de ligne CRLF, accents conservés.
 * Les noms viennent d'une saisie : une cellule qui commence par = + - ou @ serait interprétée
 * comme une formule par Excel, donc on la neutralise avec une apostrophe (injection de formule).
 */
fun genererCsv(lignes: List<LigneExport>, fuseau: ZoneId): String = buildString {
    append(BOM_UTF8)
    append(ENTETES_EXPORT.joinToString(SEPARATEUR) { proteger(it) }).append("\r\n")
    lignes.triees().forEach { ligne ->
        append(ligne.cellules(fuseau).joinToString(SEPARATEUR) { proteger(it) }).append("\r\n")
    }
    // Deuxième tableau, sous une ligne vide : le temps de travail par agent et par jour, puis le total.
    val recap = recapTemps(lignes, fuseau)
    if (recap.isNotEmpty()) {
        append("\r\n").append("Temps de travail").append("\r\n")
        append(ENTETES_TEMPS.joinToString(SEPARATEUR) { proteger(it) }).append("\r\n")
        (recap.map { it.cellulesTableur() } + totauxParAgent(recap).map { it.cellulesTableur() }).forEach { cellules ->
            append(cellules.joinToString(SEPARATEUR) { proteger(it) }).append("\r\n")
        }
    }
}

val ENTETES_TEMPS = listOf("Agent", "Pôle", "Date", "Temps de travail", "Heures (décimal)", "Pause", "Remarque")

const val REMARQUE_INCOMPLETE = "incomplète, à vérifier"

/** Temps de travail d'un agent pour un jour, ou pour toute la période quand [jour] est null (ligne de total). */
data class RecapTemps(
    val agent: String,
    val pole: String,
    val jour: LocalDate?,
    val travail: Duration,
    val pause: Duration,
    val joursIncomplets: Int,
)

/**
 * Temps de travail par agent et par jour, calculé à partir des pointages exportés (annulés ignorés).
 * Les jours sans pointage valide n'apparaissent pas.
 */
fun recapTemps(lignes: List<LigneExport>, fuseau: ZoneId): List<RecapTemps> =
    lignes.groupBy { if (it.agentId != 0L) "id:${it.agentId}" else "nom:${it.agent}" }.values.flatMap { liste ->
        // L'ordre d'origine sert d'identifiant : à heure égale, il départage comme en base.
        val pointages = liste.mapIndexed { i, l -> Pointage(i.toLong(), l.agentId, l.type, l.horodatage, l.statut) }
        pointages.map { jourLocal(it.horodatage, fuseau) }.distinct().mapNotNull { jour ->
            tempsDeLaJournee(pointages, jour, fuseau)?.let { t ->
                RecapTemps(liste[0].agent, liste[0].pole, jour, t.travail, t.pause, if (t.complet) 0 else 1)
            }
        }
    }.sortedWith(compareBy({ it.jour }, { it.agent }))

/** Une ligne de total par agent sur la période, triée par nom. */
fun totauxParAgent(recap: List<RecapTemps>): List<RecapTemps> =
    recap.groupBy { it.agent to it.pole }.map { (cle, jours) ->
        RecapTemps(
            cle.first, cle.second, null,
            jours.fold(Duration.ZERO) { total, j -> total + j.travail },
            jours.fold(Duration.ZERO) { total, j -> total + j.pause },
            jours.sumOf { it.joursIncomplets },
        )
    }.sortedBy { it.agent }

fun RecapTemps.remarque(): String = when {
    jour != null && joursIncomplets > 0 -> REMARQUE_INCOMPLETE
    jour == null && joursIncomplets > 0 -> "dont $joursIncomplets journée(s) incomplète(s)"
    else -> ""
}

private fun RecapTemps.cellulesTableur(): List<String> = listOf(
    agent, pole, jour?.format(FORMAT_DATE_EXPORT) ?: "Total période",
    formaterDureeTableur(travail), formaterHeuresDecimales(travail), formaterDureeTableur(pause), remarque(),
)

private fun proteger(valeur: String): String {
    val sansFormule = if (valeur.firstOrNull() in listOf('=', '+', '-', '@')) "'$valeur" else valeur
    val doitEtreCite = sansFormule.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
    return if (doitEtreCite) "\"" + sansFormule.replace("\"", "\"\"") + "\"" else sansFormule
}
