package fr.mairie.badgeuse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.mairie.badgeuse.donnees.BaseDeDonnees
import fr.mairie.badgeuse.donnees.DepotAdmin
import fr.mairie.badgeuse.donnees.DepotBadgeuse
import fr.mairie.badgeuse.donnees.ResultatCode
import fr.mairie.badgeuse.donnees.ResultatConnexion
import fr.mairie.badgeuse.donnees.ResultatReinitialisation
import fr.mairie.badgeuse.domaine.TypeAnomalie
import fr.mairie.badgeuse.domaine.TypePointage
import kotlinx.coroutines.runBlocking
import fr.mairie.badgeuse.donnees.DepotSauvegarde
import fr.mairie.badgeuse.donnees.PointageEntite
import fr.mairie.badgeuse.domaine.StatutPointage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private class HorlogeAdmin(var maintenant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = maintenant
}

@RunWith(AndroidJUnit4::class)
class DepotAdminTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val horloge = HorlogeAdmin(Instant.parse("2026-11-04T09:00:00Z"))
    private lateinit var base: BaseDeDonnees
    private lateinit var admin: DepotAdmin
    private lateinit var badgeuse: DepotBadgeuse
    private val mdp = "Mairie-2026!"

    @Before fun ouvrir() {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        base = Room.inMemoryDatabaseBuilder(contexte, BaseDeDonnees::class.java).build()
        admin = DepotAdmin(base, horloge, paris)
        badgeuse = DepotBadgeuse(base, horloge, paris)
    }

    @After fun fermer() = base.close()

    @Test fun premiere_configuration_une_seule_fois_et_mot_de_passe_valide_exige() = runBlocking {
        assertNull(admin.configurerPremiereFois("1234"))
        assertFalse(admin.estConfigure())
        assertNotNull(admin.configurerPremiereFois(mdp))
        assertTrue(admin.estConfigure())
        assertNull(admin.configurerPremiereFois("Autre-mdp-2026"))
        assertEquals(ResultatConnexion.Ok, admin.connecter(mdp))
    }

    @Test fun t21_un_code_agent_n_ouvre_pas_l_espace_admin() = runBlocking {
        admin.configurerPremiereFois(mdp)
        admin.creerAgent("A", "X", "Test", "1234")
        assertTrue(admin.connecter("1234") is ResultatConnexion.Incorrect)
    }

    @Test fun connexion_bloquee_apres_3_echecs_puis_libre() = runBlocking {
        admin.configurerPremiereFois(mdp)
        repeat(2) { assertTrue(admin.connecter("faux-faux-faux") is ResultatConnexion.Incorrect) }
        assertTrue(admin.connecter("faux-faux-faux") is ResultatConnexion.Bloque)
        assertTrue(admin.connecter(mdp) is ResultatConnexion.Bloque)
        horloge.maintenant = horloge.maintenant.plusSeconds(6 * 60)
        assertEquals(ResultatConnexion.Ok, admin.connecter(mdp))
    }

    @Test fun t27_le_bon_code_de_secours_change_le_mot_de_passe_et_l_invalide() = runBlocking {
        val secours = admin.configurerPremiereFois(mdp)!!
        val r = admin.reinitialiserMotDePasse(secours.lowercase(), "Nouveau-mdp-2026")
        assertTrue(r is ResultatReinitialisation.Ok)
        val nouveauSecours = (r as ResultatReinitialisation.Ok).nouveauCodeSecours
        assertNotEquals(secours, nouveauSecours)
        assertTrue(admin.connecter(mdp) is ResultatConnexion.Incorrect)
        assertEquals(ResultatConnexion.Ok, admin.connecter("Nouveau-mdp-2026"))
        // L'ancien code de secours ne marche plus.
        assertTrue(admin.reinitialiserMotDePasse(secours, "Encore-un-mdp-26") is ResultatReinitialisation.CodeIncorrect)
        assertTrue(base.securiteDao().tous().any { it.type == "CODE_SECOURS_UTILISE" })
    }

    @Test fun t28_un_mauvais_code_de_secours_est_refuse_et_ne_donne_pas_acces() = runBlocking {
        admin.configurerPremiereFois(mdp)
        assertTrue(admin.reinitialiserMotDePasse("AAAA-BBBB-CCCC", "Nouveau-mdp-2026") is ResultatReinitialisation.CodeIncorrect)
        assertEquals(ResultatConnexion.Ok, admin.connecter(mdp))
    }

    @Test fun t26_creer_changer_le_code_desactiver_un_agent() = runBlocking {
        assertFalse(admin.creerAgent("Dupont", "Marie", "Test", "12"))
        assertTrue(admin.creerAgent("Dupont", "Marie", "Test", "1234"))
        val id = admin.agents().single().id
        assertTrue(admin.changerCode(id, "9999"))
        assertTrue(badgeuse.verifierCode(id, "1234") is ResultatCode.Incorrect)
        assertTrue(badgeuse.verifierCode(id, "9999") is ResultatCode.Valide)

        badgeuse.pointer(id, TypePointage.ENTREE)
        admin.definirActif(id, false)
        assertEquals(0, badgeuse.agentsActifs().size)
        assertEquals(1, badgeuse.historique(id).size)
    }

    @Test fun t13_l_administrateur_debloque_un_agent() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val id = admin.agents().single().id
        repeat(3) { badgeuse.verifierCode(id, "0000") }
        assertTrue(admin.agents().single().bloque)
        admin.debloquer(id)
        assertFalse(admin.agents().single().bloque)
        assertTrue(badgeuse.verifierCode(id, "1234") is ResultatCode.Valide)
    }

    @Test fun t22_t23_t24_modifier_un_pointage_garde_l_ancienne_valeur_au_journal() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val id = admin.agents().single().id
        val p = badgeuse.pointer(id, TypePointage.ENTREE)!!
        val nouvelle = p.horodatage.minusSeconds(3600)

        assertTrue(admin.modifierPointage(p.id, TypePointage.ENTREE, nouvelle, "oubli de badger"))
        val liste = admin.pointagesDuJour(LocalDate.of(2026, 11, 4))
        assertEquals(nouvelle, liste.single().pointage.horodatage)

        val journal = admin.journalRecent().single()
        assertEquals("MODIFICATION", journal.action)
        assertEquals("ADMIN", journal.auteur)
        assertEquals("oubli de badger", journal.motif)
        assertTrue(journal.ancienneValeur!!.contains(p.horodatage.toEpochMilli().toString()))
        assertTrue(journal.nouvelleValeur!!.contains(nouvelle.toEpochMilli().toString()))
    }

    @Test fun un_pointage_modifie_deja_envoye_est_marque_a_renvoyer() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val id = admin.agents().single().id
        val p = badgeuse.pointer(id, TypePointage.ENTREE)!!
        base.pointageDao().marquerEnvoyes(1)
        assertNotNull(base.pointageDao().parId(p.id)!!.envoyeLe)
        admin.modifierPointage(p.id, TypePointage.SORTIE, p.horodatage, null)
        assertNull(base.pointageDao().parId(p.id)!!.envoyeLe)
    }

    @Test fun ajouter_un_pointage_a_la_main_est_trace_avec_l_origine_admin() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val id = admin.agents().single().id
        assertTrue(admin.ajouterPointage(id, TypePointage.SORTIE, horloge.maintenant, "agent parti sans badger"))
        assertEquals("ADMIN", admin.pointagesDuJour(LocalDate.of(2026, 11, 4)).single().origine)
        assertEquals("CREATION_ADMIN", admin.journalRecent().single().action)
        assertFalse(admin.ajouterPointage(999, TypePointage.SORTIE, horloge.maintenant, null))
    }

    @Test fun t17_t19_t20_detection_des_oublis_sans_doublon() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        admin.creerAgent("Martin", "Paul", "Test", "1234")
        val (a, b) = admin.agents().map { it.id }
        badgeuse.pointer(a, TypePointage.ENTREE)            // jamais de sortie
        badgeuse.pointer(b, TypePointage.ENTREE)
        badgeuse.pointer(b, TypePointage.SORTIE)            // journée complète
        val jour = LocalDate.of(2026, 11, 4)

        assertEquals(1, admin.detecterAnomalies(listOf(jour)))
        assertEquals(0, admin.detecterAnomalies(listOf(jour)))
        val ouvertes = admin.anomaliesOuvertes()
        assertEquals(TypeAnomalie.SORTIE_MANQUANTE, ouvertes.single().type)
        assertEquals("Marie Dupont", ouvertes.single().agent)

        admin.marquerTraitee(ouvertes.single().id)
        assertEquals(0, admin.anomaliesOuvertes().size)
        assertEquals(0, admin.detecterAnomalies(listOf(jour)))
    }

    @Test fun b8_l_administrateur_reattribue_et_annule_un_pointage_avec_trace() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        admin.creerAgent("Martin", "Paul", "Test", "1234")
        val (marie, paul) = admin.agents().sortedBy { it.nom }.map { it.id }
        // Paul a badgé sur le profil de Marie ; on s'en aperçoit bien après les 15 secondes.
        val p = badgeuse.pointer(marie, TypePointage.ENTREE)!!
        assertTrue(admin.modifierPointage(p.id, TypePointage.ENTREE, p.horodatage, "erreur de profil", agentId = paul))
        assertEquals(paul, base.pointageDao().parId(p.id)!!.agentId)
        val journal = admin.journalRecent().first()
        assertTrue(journal.ancienneValeur!!.contains("\"agentId\":$marie"))
        assertTrue(journal.nouvelleValeur!!.contains("\"agentId\":$paul"))

        assertTrue(admin.modifierPointage(p.id, TypePointage.ENTREE, p.horodatage, "doublon", statut = StatutPointage.ANNULE))
        assertEquals(StatutPointage.ANNULE.name, base.pointageDao().parId(p.id)!!.statut)
        assertEquals(2, admin.journalRecent().size)
        assertFalse("un agent inconnu est refusé", admin.modifierPointage(p.id, TypePointage.ENTREE, p.horodatage, null, agentId = 999))
    }

    @Test fun b13_un_pointage_dans_le_futur_est_refuse() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val id = admin.agents().single().id
        val futur = horloge.maintenant.plusSeconds(3600)
        assertFalse(admin.ajouterPointage(id, TypePointage.ENTREE, futur, null))
        val p = badgeuse.pointer(id, TypePointage.ENTREE)!!
        assertFalse(admin.modifierPointage(p.id, TypePointage.ENTREE, futur, null))
        assertEquals(0, admin.journalRecent().size)
    }

    @Test fun b11_un_agent_desactive_ne_peut_plus_entrer_ni_pointer() = runBlocking {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val id = admin.agents().single().id
        admin.definirActif(id, false)
        assertTrue(badgeuse.verifierCode(id, "1234") is ResultatCode.AgentIntrouvable)
        assertNull(badgeuse.pointer(id, TypePointage.ENTREE))
    }

    @Test fun b10_les_evenements_de_securite_sont_lisibles_par_l_administrateur() = runBlocking {
        admin.configurerPremiereFois(mdp)
        admin.connecter("mauvais-mot-de-passe")
        admin.connecter(mdp)
        val types = admin.evenementsSecurite().map { it.type }
        assertEquals(listOf("CONNEXION_ADMIN_REUSSIE", "CONNEXION_ADMIN_ECHEC"), types)
    }

    @Test fun la_duree_de_conservation_est_reglable_et_tracee() = runBlocking {
        val sauvegarde = DepotSauvegarde(base, horloge, paris)
        assertEquals(4L, sauvegarde.dureeConservationMois())
        assertFalse(sauvegarde.definirDureeConservation(0))
        assertTrue(sauvegarde.definirDureeConservation(12))
        assertEquals(12L, sauvegarde.dureeConservationMois())
        assertEquals("CONSERVATION_MODIFIEE", admin.evenementsSecurite().single().type)

        // Un pointage envoyé de 6 mois est gardé avec 12 mois de conservation, effacé avec 1 mois.
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val id = admin.agents().single().id
        val ancien = Instant.parse("2026-05-04T08:00:00Z")
        base.pointageDao().inserer(
            PointageEntite(agentId = id, type = "ENTREE", horodatage = ancien.toEpochMilli(), fuseau = "Europe/Paris",
                statut = "VALIDE", origine = "AGENT", envoyeLe = ancien.toEpochMilli()),
        )
        assertEquals(0, sauvegarde.purger())
        sauvegarde.definirDureeConservation(1)
        assertEquals(1, sauvegarde.purger())
    }
}
