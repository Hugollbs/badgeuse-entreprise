package fr.mairie.badgeuse.export

import fr.mairie.badgeuse.domaine.PARIS
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.domaine.paris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExportPointagesTest {
    private fun ligne(
        quand: String = "2026-11-04T08:30:00",
        agent: String = "Marie Dupont",
        type: TypePointage = TypePointage.ENTREE,
        statut: StatutPointage = StatutPointage.VALIDE,
    ) = LigneExport(agent, "Accueil", paris(quand), type, statut, "BORNE")

    @Test fun csv_vide_contient_seulement_la_ligne_d_entetes() {
        val csv = genererCsv(emptyList(), PARIS)
        assertEquals("\uFEFFAgent;Pôle;Date;Heure;Type;Statut;Origine\r\n", csv)
    }

    @Test fun csv_ecrit_date_et_heure_locales() {
        val csv = genererCsv(listOf(ligne()), PARIS).lines()
        assertEquals("Marie Dupont;Accueil;04/11/2026;08:30;Entrée;Valide;BORNE", csv[1])
    }

    @Test fun csv_garde_l_heure_locale_apres_le_changement_d_heure_d_hiver() {
        // 25/10/2026 : à 03:00 l'heure d'été (UTC+2) repasse à 02:00 (UTC+1). 08:30 locale = 07:30 UTC.
        val avant = Instant.parse("2026-10-24T06:30:00Z") // 08:30 à Paris (été)
        val apres = Instant.parse("2026-10-26T07:30:00Z") // 08:30 à Paris (hiver)
        val lignes = listOf(avant, apres).map { LigneExport("A B", "", it, TypePointage.ENTREE, StatutPointage.VALIDE, "BORNE") }
        val csv = genererCsv(lignes, PARIS).lines()
        assertTrue(csv[1].contains(";24/10/2026;08:30;"))
        assertTrue(csv[2].contains(";26/10/2026;08:30;"))
    }

    @Test fun csv_marque_les_pointages_annules_au_lieu_de_les_cacher() {
        val csv = genererCsv(listOf(ligne(statut = StatutPointage.ANNULE)), PARIS)
        assertTrue(csv.contains(";Annulé;"))
    }

    @Test fun csv_trie_par_date_puis_par_agent() {
        val csv = genererCsv(
            listOf(
                ligne("2026-11-04T09:00:00", "Zoé Zola"),
                ligne("2026-11-04T08:00:00", "Paul Martin"),
                ligne("2026-11-04T08:00:00", "Marie Dupont"),
            ),
            PARIS,
        ).lines()
        assertTrue(csv[1].startsWith("Marie Dupont"))
        assertTrue(csv[2].startsWith("Paul Martin"))
        assertTrue(csv[3].startsWith("Zoé Zola"))
    }

    @Test fun csv_met_entre_guillemets_les_cellules_avec_separateur_ou_guillemet() {
        val csv = genererCsv(listOf(ligne(agent = "Dupont; \"Marie\"")), PARIS).lines()
        assertTrue(csv[1].startsWith("\"Dupont; \"\"Marie\"\"\";"))
    }

    @Test fun csv_neutralise_les_cellules_qui_ressemblent_a_une_formule() {
        val csv = genererCsv(listOf(ligne(agent = "=HYPERLINK(\"http://x\")")), PARIS).lines()
        assertTrue(csv[1].startsWith("\"'=HYPERLINK("))
        val plus = genererCsv(listOf(ligne(agent = "+33 1")), PARIS).lines()
        assertTrue(plus[1].startsWith("'+33 1;"))
    }

    @Test fun `csv ajoute le temps de travail par jour et le total de la periode`() {
        val lignes = listOf(
            LigneExport("Marie Dupont", "Accueil", paris("2026-11-04T08:00:00"), TypePointage.ENTREE, StatutPointage.VALIDE, "AGENT", 1),
            LigneExport("Marie Dupont", "Accueil", paris("2026-11-04T12:00:00"), TypePointage.DEBUT_PAUSE, StatutPointage.VALIDE, "AGENT", 1),
            LigneExport("Marie Dupont", "Accueil", paris("2026-11-04T13:00:00"), TypePointage.FIN_PAUSE, StatutPointage.VALIDE, "AGENT", 1),
            LigneExport("Marie Dupont", "Accueil", paris("2026-11-04T17:05:00"), TypePointage.SORTIE, StatutPointage.VALIDE, "AGENT", 1),
            LigneExport("Marie Dupont", "Accueil", paris("2026-11-05T08:00:00"), TypePointage.ENTREE, StatutPointage.VALIDE, "AGENT", 1),
        )
        val csv = genererCsv(lignes, PARIS).lines()
        val debut = csv.indexOf("Temps de travail")
        assertTrue("section absente", debut > 0)
        assertEquals("Agent;Pôle;Date;Temps de travail;Heures (décimal);Pause;Remarque", csv[debut + 1])
        assertEquals("Marie Dupont;Accueil;04/11/2026;8:05;8,08;1:00;", csv[debut + 2])
        assertEquals("Marie Dupont;Accueil;05/11/2026;0:00;0,00;0:00;$REMARQUE_INCOMPLETE", csv[debut + 3])
        assertEquals("Marie Dupont;Accueil;Total période;8:05;8,08;1:00;dont 1 journée(s) incomplète(s)", csv[debut + 4])
    }

    @Test fun `deux agents homonymes restent separes grace a leur identifiant`() {
        val lignes = listOf(
            LigneExport("A B", "", paris("2026-11-04T08:00:00"), TypePointage.ENTREE, StatutPointage.VALIDE, "AGENT", 1),
            LigneExport("A B", "", paris("2026-11-04T09:00:00"), TypePointage.ENTREE, StatutPointage.VALIDE, "AGENT", 2),
            LigneExport("A B", "", paris("2026-11-04T10:00:00"), TypePointage.SORTIE, StatutPointage.VALIDE, "AGENT", 1),
        )
        val recap = recapTemps(lignes, PARIS)
        assertEquals(2, recap.size)
        assertEquals(1, recap.count { it.joursIncomplets == 0 })
    }
}
