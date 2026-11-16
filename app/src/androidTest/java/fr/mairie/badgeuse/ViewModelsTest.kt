package fr.mairie.badgeuse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.mairie.badgeuse.donnees.AgentAffiche
import fr.mairie.badgeuse.donnees.BaseDeDonnees
import fr.mairie.badgeuse.donnees.CoffreKeystore
import fr.mairie.badgeuse.donnees.DepotAdmin
import fr.mairie.badgeuse.donnees.DepotBadgeuse
import fr.mairie.badgeuse.donnees.DepotSauvegarde
import fr.mairie.badgeuse.donnees.EchecEnvoi
import fr.mairie.badgeuse.donnees.Expediteur
import fr.mairie.badgeuse.donnees.ReglagesSauvegarde
import fr.mairie.badgeuse.donnees.ServiceSauvegarde
import fr.mairie.badgeuse.domaine.ConfigurationEmail
import fr.mairie.badgeuse.domaine.FrequenceSauvegarde
import fr.mairie.badgeuse.domaine.SecuriteSmtp
import fr.mairie.badgeuse.domaine.TypePointage
import fr.mairie.badgeuse.viewmodel.AdminViewModel
import fr.mairie.badgeuse.viewmodel.BadgeuseViewModel
import fr.mairie.badgeuse.viewmodel.Ecran
import fr.mairie.badgeuse.viewmodel.EcranAdmin
import fr.mairie.badgeuse.viewmodel.RaisonPause
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Expéditeur qui reste bloqué jusqu'à ce que le test le libère : simule un serveur SMTP très lent. */
private class ExpediteurBloque : Expediteur {
    val commence = CountDownLatch(1)
    val liberer = CountDownLatch(1)
    override fun envoyer(config: ConfigurationEmail, motDePasse: String, sujet: String, corps: String, nomPiece: String, piece: ByteArray) {
        commence.countDown()
        liberer.await(10, TimeUnit.SECONDS)
        throw EchecEnvoi("Connexion au serveur impossible ou trop lente.", reessayer = true)
    }
}

/**
 * Bugs d'état relevés par l'audit (B1 à B4) : un résultat qui arrive après que l'utilisateur a quitté
 * l'écran ne doit jamais rouvrir un espace (admin sans mot de passe, ou espace d'un agent).
 */
@RunWith(AndroidJUnit4::class)
class ViewModelsTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Paris")
    private val motDePasseAdmin = "Essai-admin-2026"
    private lateinit var base: BaseDeDonnees

    @Before fun ouvrir() {
        base = Room.inMemoryDatabaseBuilder(contexte, BaseDeDonnees::class.java).build()
    }

    @After fun fermer() = base.close()

    private fun surMain(bloc: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(bloc)

    private fun attendre(delaiMs: Long = 5000, condition: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + delaiMs
        while (System.currentTimeMillis() < fin) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    // --- Espace agent ------------------------------------------------------------------------

    private fun agent(depot: DepotBadgeuse): AgentAffiche = runBlocking {
        depot.creerAgent("Dupont", "Marie", "Accueil", "1234")
        depot.agentsActifs().first()
    }

    private fun vmAgent(delai: Duration = Duration.ofSeconds(30)): Pair<BadgeuseViewModel, AgentAffiche> {
        val depot = DepotBadgeuse(base, Clock.systemUTC(), zone)
        val a = agent(depot)
        lateinit var vm: BadgeuseViewModel
        surMain { vm = BadgeuseViewModel(depot, delai) }
        return vm to a
    }

    @Test fun b2_un_code_valide_apres_un_retour_n_ouvre_pas_l_espace() {
        val (vm, a) = vmAgent()
        surMain {
            vm.choisirAgent(a)
            vm.validerCode("1234")
            vm.retourAccueil()
        }
        Thread.sleep(1500)
        assertTrue("l'espace s'est rouvert après le retour", vm.ecran.value is Ecran.Accueil)
    }

    @Test fun b3_un_pointage_termine_apres_quitter_ne_rouvre_pas_l_espace() {
        val (vm, a) = vmAgent()
        surMain { vm.choisirAgent(a); vm.validerCode("1234") }
        assertTrue(attendre { vm.ecran.value is Ecran.Espace })
        surMain {
            vm.pointer(TypePointage.ENTREE)
            vm.retourAccueil()
        }
        Thread.sleep(1000)
        assertTrue("l'espace s'est rouvert après Quitter", vm.ecran.value is Ecran.Accueil)
    }

    @Test fun le_minuteur_ramene_a_l_accueil_et_chaque_toucher_le_repousse() {
        val (vm, a) = vmAgent(Duration.ofMillis(600))
        surMain { vm.choisirAgent(a) }
        repeat(6) { Thread.sleep(200); surMain { vm.activite() } }
        assertTrue("retour trop tôt malgré l'activité", vm.ecran.value is Ecran.Code)
        assertTrue(attendre(2000) { vm.ecran.value is Ecran.Accueil })
    }

    @Test fun b4_le_minuteur_est_suspendu_pendant_le_selecteur_de_fichiers() {
        val (vm, a) = vmAgent(Duration.ofMillis(400))
        surMain { vm.choisirAgent(a); vm.suspendreMinuteur(RaisonPause.SELECTEUR_FICHIER, true) }
        Thread.sleep(1200)
        assertTrue("retour à l'accueil pendant le sélecteur", vm.ecran.value is Ecran.Code)
        surMain { vm.suspendreMinuteur(RaisonPause.SELECTEUR_FICHIER, false) }
        assertTrue(attendre(2000) { vm.ecran.value is Ecran.Accueil })
    }

    // --- Espace administrateur ---------------------------------------------------------------

    private fun vmAdmin(expediteur: Expediteur): AdminViewModel {
        val admin = DepotAdmin(base, Clock.systemUTC(), zone)
        val sauvegarde = DepotSauvegarde(base, Clock.systemUTC(), zone)
        val reglages = ReglagesSauvegarde(base, CoffreKeystore("badgeuse-coffre-test"))
        runBlocking {
            admin.configurerPremiereFois(motDePasseAdmin)
            reglages.enregistrer(
                ConfigurationEmail(
                    "smtp.exemple.fr", 465, SecuriteSmtp.SSL, "compte", "badgeuse@exemple.fr",
                    "dgs@exemple.fr", FrequenceSauvegarde.QUOTIDIENNE,
                ),
                "mdp-smtp", "Sauvegarde-2026!",
            )
        }
        val service = ServiceSauvegarde(sauvegarde, reglages, expediteur, Clock.systemUTC(), zone)
        lateinit var vm: AdminViewModel
        surMain { vm = AdminViewModel(admin, sauvegarde, reglages, service, planifier = {}, fuseau = zone) }
        return vm
    }

    @Test fun b1_un_envoi_termine_apres_la_fermeture_ne_rouvre_pas_l_espace_admin() {
        val expediteur = ExpediteurBloque()
        val vm = vmAdmin(expediteur)
        surMain { vm.demarrer() }
        assertTrue(attendre { vm.ecran.value is EcranAdmin.Connexion })
        surMain { vm.connecter(motDePasseAdmin) }
        assertTrue(attendre(10_000) { vm.ecran.value is EcranAdmin.Espace })

        surMain { vm.envoyerSauvegardeMaintenant() }
        assertTrue(expediteur.commence.await(10, TimeUnit.SECONDS))
        // L'administrateur quitte, quelqu'un d'autre rouvre l'administration.
        surMain { vm.fermer(); vm.demarrer() }
        assertTrue(attendre { vm.ecran.value is EcranAdmin.Connexion })

        expediteur.liberer.countDown()
        Thread.sleep(1500)
        assertTrue("l'espace admin s'est ouvert sans mot de passe", vm.ecran.value is EcranAdmin.Connexion)
    }

    @Test fun b1_une_connexion_terminee_apres_la_fermeture_n_ouvre_pas_l_espace_admin() {
        val vm = vmAdmin(ExpediteurBloque())
        surMain { vm.demarrer() }
        assertTrue(attendre { vm.ecran.value is EcranAdmin.Connexion })
        surMain {
            vm.connecter(motDePasseAdmin)
            vm.fermer()
            vm.demarrer()
        }
        Thread.sleep(3000)
        assertTrue("l'espace admin s'est ouvert après la fermeture", vm.ecran.value !is EcranAdmin.Espace)
    }
}
