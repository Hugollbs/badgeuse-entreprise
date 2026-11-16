package fr.mairie.badgeuse.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import fr.mairie.badgeuse.domaine.formaterDuree
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Unités de PdfDocument : des points (1/72 de pouce). A4 = 595 × 842.
private const val LARGEUR_PAGE = 595
private const val HAUTEUR_PAGE = 842
private const val MARGE = 40f
private const val HAUTEUR_LIGNE = 18f
private const val Y_TITRE_TABLEAU = 98f
private const val Y_ENTETES = 120f
private const val Y_DEBUT_TABLEAU = 132f
private const val Y_PIED = HAUTEUR_PAGE - 30f
private const val LONGUEUR_MAX_CELLULE = 24

private val FORMAT_PERIODE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Un tableau à écrire : il peut s'étendre sur plusieurs pages, ses en-têtes sont répétés sur chacune. */
private class Tableau(val titre: String, val entetes: List<String>, val colonnesX: FloatArray, val lignes: List<List<String>>)

/**
 * Écrit le PDF dans [sortie] ; l'appelant ferme le flux. Deux tableaux : les pointages, puis le
 * temps de travail par agent et par jour avec le total de la période. Les noms trop longs sont
 * tronqués pour ne pas déborder sur la colonne voisine.
 */
fun ecrirePdf(
    lignes: List<LigneExport>, debut: LocalDate, fin: LocalDate, fuseau: ZoneId, titreAgent: String, sortie: OutputStream,
) {
    val pointages = Tableau(
        "Pointages", listOf("Agent", "Date", "Heure", "Type", "Statut"), floatArrayOf(MARGE, 215f, 285f, 335f, 450f),
        // On n'écrit que Agent, Date, Heure, Type, Statut : le pôle et l'origine restent dans le CSV.
        lignes.triees().map { ligne -> ligne.cellules(fuseau).let { listOf(it[0], it[2], it[3], it[4], it[5]) } },
    )
    val recap = recapTemps(lignes, fuseau)
    val temps = Tableau(
        "Temps de travail", listOf("Agent", "Date", "Travail", "Pause", "Remarque"), floatArrayOf(MARGE, 215f, 285f, 345f, 405f),
        (recap + totauxParAgent(recap)).map {
            listOf(it.agent, it.jour?.format(FORMAT_PERIODE) ?: "Total période", formaterDuree(it.travail), formaterDuree(it.pause), it.remarque())
        },
    )
    val tableaux = if (recap.isEmpty()) listOf(pointages) else listOf(pointages, temps)
    ecrirePages(tableaux, "$titreAgent, du ${debut.format(FORMAT_PERIODE)} au ${fin.format(FORMAT_PERIODE)}", sortie)
}

private fun ecrirePages(tableaux: List<Tableau>, sousTitre: String, sortie: OutputStream) {
    val titre = Paint().apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
    val gras = Paint().apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD }
    val normal = Paint().apply { textSize = 10f }
    val lignesParPage = ((Y_PIED - Y_DEBUT_TABLEAU) / HAUTEUR_LIGNE).toInt()

    // Chaque page = un morceau d'un tableau ; un tableau vide occupe quand même une page.
    val pages = tableaux.flatMap { t -> t.lignes.chunked(lignesParPage).ifEmpty { listOf(emptyList()) }.map { t to it } }

    val document = PdfDocument()
    try {
        pages.forEachIndexed { index, (tableau, lot) ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(LARGEUR_PAGE, HAUTEUR_PAGE, index + 1).create())
            val toile = page.canvas
            toile.drawText("Pointages — Mairie d'Exemple-sur-Loire", MARGE, 50f, titre)
            toile.drawText(sousTitre, MARGE, 72f, normal)
            toile.drawText(tableau.titre, MARGE, Y_TITRE_TABLEAU, gras)
            tableau.entetes.forEachIndexed { i, texte -> toile.drawText(texte, tableau.colonnesX[i], Y_ENTETES, gras) }
            toile.drawLine(MARGE, Y_ENTETES + 5f, LARGEUR_PAGE - MARGE, Y_ENTETES + 5f, normal)

            var y = Y_DEBUT_TABLEAU + HAUTEUR_LIGNE
            lot.forEach { cellules ->
                cellules.forEachIndexed { i, texte -> toile.drawText(tronquer(texte), tableau.colonnesX[i], y, normal) }
                y += HAUTEUR_LIGNE
            }
            toile.drawText("Page ${index + 1} / ${pages.size}", MARGE, Y_PIED, normal)
            document.finishPage(page)
        }
        document.writeTo(sortie)
    } finally {
        document.close()
    }
}

private fun tronquer(texte: String) =
    if (texte.length <= LONGUEUR_MAX_CELLULE) texte else texte.take(LONGUEUR_MAX_CELLULE - 1) + "…"
