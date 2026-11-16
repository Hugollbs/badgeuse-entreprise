package fr.mairie.badgeuse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.mairie.badgeuse.donnees.BaseDeDonnees
import fr.mairie.badgeuse.donnees.DepotAdmin
import fr.mairie.badgeuse.donnees.DepotBadgeuse
import fr.mairie.badgeuse.donnees.DepotSauvegarde
import fr.mairie.badgeuse.donnees.JournalEntite
import fr.mairie.badgeuse.donnees.PointageEntite
import fr.mairie.badgeuse.donnees.ResultatRestauration
import fr.mairie.badgeuse.domaine.TypeAnomalie
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.export.ecrirePdf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private class HorlogeSauvegarde(var maintenant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = maintenant
}

@RunWith(AndroidJUnit4::class)
class DepotSauvegardeTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val horloge = HorlogeSauvegarde(Instant.parse("2026-11-04T09:00:00Z"))
    private val contexte = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var base: BaseDeDonnees
    private lateinit var admin: DepotAdmin
    private lateinit var badgeuse: DepotBadgeuse
    private lateinit var sauvegarde: DepotSauvegarde

    private fun nouvelleBase() = Room.inMemoryDatabaseBuilder(contexte, BaseDeDonnees::class.java).build()

    @Before fun ouvrir() {
        base = nouvelleBase()
        admin = DepotAdmin(base, horloge, paris)
        badgeuse = DepotBadgeuse(base, horloge, paris)
        sauvegarde = DepotSauvegarde(base, horloge, paris)
    }

    @After fun fermer() = base.close()

    private suspend fun creerAgent(nom: String): Long {
        admin.creerAgent(nom, "Test", "Pôle", "1234")
        return admin.agents().first { it.nom == nom }.id
    }

    private suspend fun inserer(agentId: Long, quand: String, envoyeLe: Long? = null): Long =
        base.pointageDao().inserer(
            PointageEntite(
                agentId = agentId, type = "ENTREE", horodatage = Instant.parse(quand).toEpochMilli(),
                fuseau = paris.id, statut = "VALIDE", origine = "BORNE", envoyeLe = envoyeLe,
            ),
        )

    // --- Export (T-29) ---------------------------------------------------------------------

    @Test fun l_export_ne_garde_que_l_agent_et_la_periode_demandes() = runBlocking {
        val a = creerAgent("Dupont")
        val b = creerAgent("Martin")
        inserer(a, "2026-11-01T08:00:00Z") // avant la période
        inserer(a, "2026-11-02T08:00:00Z")
        inserer(a, "2026-11-03T22:30:00Z") // 23:30 à Paris le 3 : dernier jour inclus
        inserer(a, "2026-11-03T23:30:00Z") // 00:30 à Paris le 4 : hors période
        inserer(b, "2026-11-02T08:00:00Z") // autre agent

        val lignes = sauvegarde.lignesExport(setOf(a), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))

        assertEquals(2, lignes.size)
        assertTrue(lignes.all { it.agent == "Test Dupont" })
        val tous = sauvegarde.lignesExport(emptySet(), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        assertEquals(3, tous.size)
    }

    @Test fun l_export_garde_les_pointages_annules_et_indique_leur_statut() = runBlocking {
        val a = creerAgent("Dupont")
        val p = badgeuse.pointer(a, TypePointage.ENTREE)!!
        assertTrue(badgeuse.annuler(a, p.id))
        val lignes = sauvegarde.lignesExport(setOf(a), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 4))
        assertEquals(1, lignes.size)
        assertEquals("ANNULE", lignes.single().statut.name)
    }

    @Test fun le_pdf_est_un_vrai_pdf_et_gere_plusieurs_pages() = runBlocking {
        val a = creerAgent("Dupont")
        repeat(120) { inserer(a, "2026-11-02T08:00:00Z") }
        val lignes = sauvegarde.lignesExport(emptySet(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        val sortie = ByteArrayOutputStream()
        ecrirePdf(lignes, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), paris, "Tous les agents", sortie)
        val octets = sortie.toByteArray()
        assertEquals("%PDF", String(octets, 0, 4, Charsets.US_ASCII))
        assertTrue(octets.size > 1000)
    }

    @Test fun le_pdf_sans_aucun_pointage_reste_valide() {
        val sortie = ByteArrayOutputStream()
        ecrirePdf(emptyList(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), paris, "Tous les agents", sortie)
        assertEquals("%PDF", String(sortie.toByteArray(), 0, 4, Charsets.US_ASCII))
    }

    // --- Sauvegarde et restauration (T-33) -------------------------------------------------

    @Test fun une_sauvegarde_restauree_dans_une_base_vide_est_identique() = runBlocking {
        val a = creerAgent("Dupont")
        val p = badgeuse.pointer(a, TypePointage.ENTREE)!!
        admin.modifierPointage(p.id, TypePointage.ENTREE, p.horodatage.plusSeconds(600), "oubli")
        horloge.maintenant = Instant.parse("2026-11-06T09:00:00Z")
        admin.detecterAnomalies(listOf(LocalDate.of(2026, 11, 4)))
        assertEquals(TypeAnomalie.SORTIE_MANQUANTE.name, base.anomalieDao().toutes().single().type)

        val fichier = sauvegarde.preparerSauvegarde()

        val autre = nouvelleBase()
        try {
            val resultat = DepotSauvegarde(autre, horloge, paris).restaurer(fichier.contenu)
            assertTrue(resultat is ResultatRestauration.Ok)
            assertEquals(base.agentDao().tous(), autre.agentDao().tous())
            assertEquals(base.pointageDao().tous(), autre.pointageDao().tous())
            assertEquals(base.journalDao().tous(), autre.journalDao().tous())
            assertEquals(base.anomalieDao().toutes(), autre.anomalieDao().toutes())
            // Les codes agents restaurés fonctionnent : le sel et l'empreinte ont bien été copiés.
            assertTrue(DepotBadgeuse(autre, horloge, paris).verifierCode(a, "1234") is fr.mairie.badgeuse.donnees.ResultatCode.Valide)
        } finally {
            autre.close()
        }
    }

    @Test fun la_sauvegarde_ne_contient_ni_mot_de_passe_admin_ni_code_de_secours() = runBlocking {
        admin.configurerPremiereFois("Mairie-2026!")
        creerAgent("Dupont")
        val contenu = sauvegarde.preparerSauvegarde().contenu
        assertTrue(!contenu.contains("admin_empreinte") && !contenu.contains("secours"))
    }

    @Test fun la_restauration_est_refusee_si_la_base_contient_deja_des_donnees() = runBlocking {
        creerAgent("Dupont")
        val fichier = sauvegarde.preparerSauvegarde()
        assertEquals(ResultatRestauration.BaseNonVide, sauvegarde.restaurer(fichier.contenu))
        assertEquals(1, base.agentDao().tous().size)
    }

    @Test fun les_fichiers_invalides_sont_refuses_et_n_ecrivent_rien() = runBlocking {
        val a = creerAgent("Dupont")
        inserer(a, "2026-11-02T08:00:00Z")
        val valide = sauvegarde.preparerSauvegarde().contenu
        val autre = nouvelleBase()
        try {
            val depot = DepotSauvegarde(autre, horloge, paris)
            val invalides = listOf(
                "ceci n'est pas du json",
                "{}",
                valide.replace("\"version\":1", "\"version\":99"),
                valide.replace("\"type\":\"ENTREE\"", "\"type\":\"INCONNU\""),
                // Pointage rattaché à un agent qui n'existe pas dans le fichier.
                valide.replace("\"agentId\":$a,\"type\"", "\"agentId\":999,\"type\""),
            )
            // Garde-fou : un remplacement qui ne trouve rien laisserait un fichier valide et fausserait le test.
            invalides.drop(2).forEach { assertTrue(it != valide) }
            invalides.forEach { assertEquals(it.take(40), ResultatRestauration.Invalide, depot.restaurer(it)) }
            assertTrue(autre.agentDao().tous().isEmpty())
            assertEquals(0, autre.pointageDao().compter())
        } finally {
            autre.close()
        }
    }

    // --- Envoi confirmé, échec, purge ------------------------------------------------------

    @Test fun confirmer_l_envoi_ne_marque_que_les_pointages_presents_dans_le_fichier() = runBlocking {
        val a = creerAgent("Dupont")
        val dansFichier = inserer(a, "2026-11-02T08:00:00Z")
        val modifie = inserer(a, "2026-11-02T09:00:00Z")
        val fichier = sauvegarde.preparerSauvegarde()

        val apres = inserer(a, "2026-11-03T08:00:00Z") // créé pendant l'envoi
        admin.modifierPointage(modifie, TypePointage.SORTIE, Instant.parse("2026-11-02T09:05:00Z"), "") // modifié pendant l'envoi
        sauvegarde.confirmerEnvoi(fichier)

        assertNotNull(base.pointageDao().parId(dansFichier)!!.envoyeLe)
        assertNull("créé après le fichier", base.pointageDao().parId(apres)!!.envoyeLe)
        assertNull("modifié après le fichier", base.pointageDao().parId(modifie)!!.envoyeLe)
        assertEquals("REUSSIE", base.sauvegardeDao().derniere()!!.statut)
    }

    @Test fun un_echec_d_envoi_est_trace_et_ne_marque_rien() = runBlocking {
        val a = creerAgent("Dupont")
        val id = inserer(a, "2026-01-02T08:00:00Z")
        sauvegarde.preparerSauvegarde()
        sauvegarde.enregistrerEchec("Pas de réseau")

        val derniere = sauvegarde.sauvegardesRecentes().first()
        assertEquals("ECHEC", derniere.statut)
        assertEquals("Pas de réseau", derniere.messageErreur)
        assertNull(base.pointageDao().parId(id)!!.envoyeLe)
    }

    @Test fun la_purge_respecte_les_trois_cas_de_retention() = runBlocking {
        val a = creerAgent("Dupont")
        val envoye = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        val ancienEnvoye = inserer(a, "2026-06-01T08:00:00Z", envoyeLe = envoye) // T-34 : supprimé
        val ancienJamaisEnvoye = inserer(a, "2026-06-01T09:00:00Z") // T-35 : jamais supprimé
        val recentEnvoye = inserer(a, "2026-10-20T08:00:00Z", envoyeLe = envoye) // T-36 : conservé
        // Le journal du pointage purgé doit partir avec lui, sinon la clé étrangère bloque la suppression.
        base.journalDao().inserer(JournalEntite(pointageId = ancienEnvoye, action = "MODIFICATION",
            ancienneValeur = "{}", nouvelleValeur = "{}", auteur = "ADMIN", date = envoye))

        assertEquals(1, sauvegarde.purger())

        assertNull(base.pointageDao().parId(ancienEnvoye))
        assertNotNull(base.pointageDao().parId(ancienJamaisEnvoye))
        assertNotNull(base.pointageDao().parId(recentEnvoye))
        assertTrue(base.journalDao().tous().isEmpty())
    }

    @Test fun la_purge_supprime_aussi_un_grand_nombre_de_pointages() = runBlocking {
        val a = creerAgent("Dupont")
        val envoye = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        // Plus de 999 : SQLite refuse autant de paramètres dans une seule requête, la purge doit travailler par lots.
        repeat(1200) { inserer(a, "2026-05-01T08:00:00Z", envoyeLe = envoye) }
        assertEquals(1200, sauvegarde.purger())
        assertEquals(0, base.pointageDao().compter())
    }

    @Test fun apres_un_envoi_reussi_les_pointages_anciens_envoyes_sont_purges() = runBlocking {
        val a = creerAgent("Dupont")
        val ancien = inserer(a, "2026-06-01T08:00:00Z")
        val recent = inserer(a, "2026-11-02T08:00:00Z")
        val fichier = sauvegarde.preparerSauvegarde()

        assertEquals(1, sauvegarde.confirmerEnvoi(fichier))

        assertNull(base.pointageDao().parId(ancien))
        assertNotNull(base.pointageDao().parId(recent)!!.envoyeLe)
    }
}
