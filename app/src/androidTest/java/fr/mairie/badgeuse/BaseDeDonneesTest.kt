package fr.mairie.badgeuse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.mairie.badgeuse.donnees.AgentEntite
import fr.mairie.badgeuse.donnees.AnomalieEntite
import fr.mairie.badgeuse.donnees.BaseDeDonnees
import fr.mairie.badgeuse.donnees.PointageEntite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests d'intégration sur une vraie base Room, en mémoire (aucun fichier créé). */
@RunWith(AndroidJUnit4::class)
class BaseDeDonneesTest {
    private lateinit var base: BaseDeDonnees

    @Before fun ouvrir() {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        base = Room.inMemoryDatabaseBuilder(contexte, BaseDeDonnees::class.java).build()
    }

    @After fun fermer() = base.close()

    private suspend fun agent(nom: String, actif: Boolean = true) =
        base.agentDao().inserer(AgentEntite(nom = nom, prenom = "X", pole = "Test", codeEmpreinte = "h", codeSel = "s", actif = actif))

    private fun pointage(agentId: Long, quand: Long, envoyeLe: Long? = null) =
        PointageEntite(agentId = agentId, type = "ENTREE", horodatage = quand, fuseau = "Europe/Paris",
            statut = "VALIDE", origine = "AGENT", envoyeLe = envoyeLe)

    @Test fun t01_seuls_les_agents_actifs_sont_listes() = runBlocking {
        agent("A"); agent("B"); agent("C"); agent("D", actif = false)
        assertEquals(3, base.agentDao().actifs().size)
        assertEquals(4, base.agentDao().tous().size)
    }

    @Test fun t15_un_agent_ne_voit_que_ses_pointages() = runBlocking {
        val a = agent("A"); val b = agent("B")
        base.pointageDao().inserer(pointage(a, 1000))
        base.pointageDao().inserer(pointage(b, 2000))
        val vus = base.pointageDao().deLAgent(a)
        assertEquals(1, vus.size)
        assertEquals(a, vus[0].agentId)
    }

    @Test fun t19_une_anomalie_n_est_creee_qu_une_fois() = runBlocking {
        val a = agent("A")
        val anomalie = AnomalieEntite(agentId = a, jour = "2026-11-03", type = "SORTIE_MANQUANTE", detecteeLe = 1)
        base.anomalieDao().inserer(anomalie)
        base.anomalieDao().inserer(anomalie)
        assertEquals(1, base.anomalieDao().duJour("2026-11-03").size)
    }

    @Test fun t35_la_purge_ne_touche_pas_un_pointage_non_envoye() = runBlocking {
        val a = agent("A")
        val envoye = base.pointageDao().inserer(pointage(a, 1000, envoyeLe = 5))
        val jamais = base.pointageDao().inserer(pointage(a, 1000, envoyeLe = null))
        val dao = base.pointageDao()
        dao.supprimerPourPurge(dao.dejaEnvoyes().map { it.id })
        assertEquals(null, dao.parId(envoye))
        assertEquals(jamais, dao.parId(jamais)?.id)
    }
}
