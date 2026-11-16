package fr.mairie.badgeuse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.mairie.badgeuse.donnees.BaseDeDonnees
import fr.mairie.badgeuse.donnees.DepotBadgeuse
import fr.mairie.badgeuse.donnees.ResultatCode
import fr.mairie.badgeuse.domaine.EtatAgent
import fr.mairie.badgeuse.domaine.StatutPointage
import fr.mairie.badgeuse.domaine.TypePointage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Horloge qu'on avance à la main pour tester les délais (15 s, blocage) sans attendre. */
private class HorlogeTest(var maintenant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = maintenant
}

@RunWith(AndroidJUnit4::class)
class DepotBadgeuseTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val horloge = HorlogeTest(Instant.parse("2026-11-04T09:00:00Z"))
    private lateinit var base: BaseDeDonnees
    private lateinit var depot: DepotBadgeuse

    @Before fun ouvrir() {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        base = Room.inMemoryDatabaseBuilder(contexte, BaseDeDonnees::class.java).build()
        depot = DepotBadgeuse(base, horloge, paris)
    }

    @After fun fermer() = base.close()

    private suspend fun agent(nom: String = "A") = depot.creerAgent(nom, "X", "Test", "1234")

    @Test fun t02_le_bon_code_ouvre_l_espace() = runBlocking {
        val id = agent()
        assertTrue(depot.verifierCode(id, "1234") is ResultatCode.Valide)
    }

    @Test fun t12_trois_codes_faux_bloquent_meme_avec_le_bon_code() = runBlocking {
        val id = agent()
        repeat(2) { assertTrue(depot.verifierCode(id, "0000") is ResultatCode.Incorrect) }
        assertTrue(depot.verifierCode(id, "0000") is ResultatCode.Bloque)
        assertTrue(depot.verifierCode(id, "1234") is ResultatCode.Bloque)
        horloge.maintenant = horloge.maintenant.plusSeconds(6 * 60)
        assertTrue(depot.verifierCode(id, "1234") is ResultatCode.Valide)
        assertEquals(1, base.securiteDao().tous().size)
    }

    @Test fun t14_un_succes_remet_le_compteur_a_zero() = runBlocking {
        val id = agent()
        repeat(2) { depot.verifierCode(id, "0000") }
        depot.verifierCode(id, "1234")
        repeat(2) { depot.verifierCode(id, "0000") }
        assertTrue(depot.verifierCode(id, "1234") is ResultatCode.Valide)
    }

    @Test fun t03_les_quatre_pointages_s_enchainent_et_t06_refuse_les_actions_interdites() = runBlocking {
        val id = agent()
        assertNull(depot.pointer(id, TypePointage.SORTIE))
        assertNotNull(depot.pointer(id, TypePointage.ENTREE))
        assertNull(depot.pointer(id, TypePointage.ENTREE))
        assertNotNull(depot.pointer(id, TypePointage.DEBUT_PAUSE))
        // En pause, une nouvelle entrée reste interdite. (La sortie, elle, est permise depuis le
        // 05/11/2026 : voir le test suivant. Ce test vérifiait auparavant qu'elle était refusée.)
        assertNull(depot.pointer(id, TypePointage.ENTREE))
        assertNotNull(depot.pointer(id, TypePointage.FIN_PAUSE))
        assertNotNull(depot.pointer(id, TypePointage.SORTIE))
        assertEquals(EtatAgent.HORS_SERVICE, depot.etatDeLAgent(id))
        assertEquals(4, depot.historique(id).size)
    }

    @Test fun t07_l_agent_peut_sortir_pendant_une_pause() = runBlocking {
        val id = agent()
        assertNotNull(depot.pointer(id, TypePointage.ENTREE))
        assertNotNull(depot.pointer(id, TypePointage.DEBUT_PAUSE))
        assertNotNull(depot.pointer(id, TypePointage.SORTIE))
        assertEquals(EtatAgent.HORS_SERVICE, depot.etatDeLAgent(id))
    }

    @Test fun t08_l_heure_enregistree_est_celle_de_l_horloge() = runBlocking {
        val id = agent()
        val p = depot.pointer(id, TypePointage.ENTREE)!!
        assertEquals(horloge.maintenant, p.horodatage)
    }

    @Test fun t10_annuler_dans_les_15_secondes_restaure_l_etat_et_laisse_une_trace() = runBlocking {
        val id = agent()
        val p = depot.pointer(id, TypePointage.ENTREE)!!
        horloge.maintenant = horloge.maintenant.plusSeconds(10)
        assertTrue(depot.annuler(id, p.id))
        assertEquals(EtatAgent.HORS_SERVICE, depot.etatDeLAgent(id))
        assertEquals(StatutPointage.ANNULE, depot.historique(id).single().statut)
        assertEquals(1, base.journalDao().duPointage(p.id).size)
    }

    @Test fun t11_annuler_apres_15_secondes_est_refuse() = runBlocking {
        val id = agent()
        val p = depot.pointer(id, TypePointage.ENTREE)!!
        horloge.maintenant = horloge.maintenant.plusSeconds(16)
        assertFalse(depot.annuler(id, p.id))
        assertEquals(EtatAgent.EN_SERVICE, depot.etatDeLAgent(id))
    }

    @Test fun un_agent_ne_peut_pas_annuler_le_pointage_d_un_autre() = runBlocking {
        val a = agent("A"); val b = agent("B")
        val p = depot.pointer(a, TypePointage.ENTREE)!!
        assertFalse(depot.annuler(b, p.id))
    }

    @Test fun t15_l_historique_ne_contient_que_les_pointages_de_l_agent() = runBlocking {
        val a = agent("A"); val b = agent("B")
        depot.pointer(a, TypePointage.ENTREE)
        depot.pointer(b, TypePointage.ENTREE)
        assertTrue(depot.historique(a).all { it.agentId == a })
        assertEquals(1, depot.historique(a).size)
    }
}
