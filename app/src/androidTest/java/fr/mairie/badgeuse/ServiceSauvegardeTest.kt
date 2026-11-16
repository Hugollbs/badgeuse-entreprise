package fr.mairie.badgeuse

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.mairie.badgeuse.donnees.BaseDeDonnees
import fr.mairie.badgeuse.donnees.ChiffrementSauvegarde
import fr.mairie.badgeuse.donnees.CoffreKeystore
import fr.mairie.badgeuse.donnees.DepotAdmin
import fr.mairie.badgeuse.donnees.DepotSauvegarde
import fr.mairie.badgeuse.donnees.EchecEnvoi
import fr.mairie.badgeuse.donnees.Expediteur
import fr.mairie.badgeuse.donnees.PointageEntite
import fr.mairie.badgeuse.donnees.ReglagesSauvegarde
import fr.mairie.badgeuse.donnees.ResultatSauvegarde
import fr.mairie.badgeuse.donnees.ServiceSauvegarde
import fr.mairie.badgeuse.domaine.ConfigurationEmail
import fr.mairie.badgeuse.domaine.FrequenceSauvegarde
import fr.mairie.badgeuse.domaine.SecuriteSmtp
import kotlinx.coroutines.runBlocking
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
import java.time.ZoneId
import java.time.ZoneOffset

private class HorlogeService(var maintenant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = maintenant
}

/** Faux expéditeur : garde ce qu'on lui demande d'envoyer, ou échoue à la demande. */
private class FauxExpediteur(var echec: EchecEnvoi? = null) : Expediteur {
    var appels = 0
    var motDePasseRecu: String? = null
    var sujet: String? = null
    var corps: String? = null
    var nomPiece: String? = null
    var piece: ByteArray? = null

    override fun envoyer(config: ConfigurationEmail, motDePasse: String, sujet: String, corps: String, nomPiece: String, piece: ByteArray) {
        appels++
        echec?.let { throw it }
        this.motDePasseRecu = motDePasse; this.sujet = sujet; this.corps = corps; this.nomPiece = nomPiece; this.piece = piece
    }
}

@RunWith(AndroidJUnit4::class)
class ServiceSauvegardeTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val horloge = HorlogeService(Instant.parse("2026-11-04T09:00:00Z"))
    private val contexte = ApplicationProvider.getApplicationContext<Context>()
    private val coffre = CoffreKeystore("badgeuse-coffre-test")
    private val motDePasseSmtp = "Mdp-du-compte-smtp"
    private val motDePasseSauvegarde = "Sauvegarde-2026!"
    private val config = ConfigurationEmail(
        "smtp.exemple.fr", 465, SecuriteSmtp.SSL, "badgeuse@exemple.fr", "badgeuse@exemple.fr",
        "dgs@exemple.fr", FrequenceSauvegarde.QUOTIDIENNE,
    )
    private lateinit var base: BaseDeDonnees
    private lateinit var admin: DepotAdmin
    private lateinit var depot: DepotSauvegarde
    private lateinit var reglages: ReglagesSauvegarde
    private lateinit var faux: FauxExpediteur
    private lateinit var service: ServiceSauvegarde

    @Before fun ouvrir() {
        base = Room.inMemoryDatabaseBuilder(contexte, BaseDeDonnees::class.java).build()
        admin = DepotAdmin(base, horloge, paris)
        depot = DepotSauvegarde(base, horloge, paris)
        reglages = ReglagesSauvegarde(base, coffre)
        faux = FauxExpediteur()
        service = ServiceSauvegarde(depot, reglages, faux, horloge, paris)
    }

    @After fun fermer() = base.close()

    private suspend fun configurer() = reglages.enregistrer(config, motDePasseSmtp, motDePasseSauvegarde)

    private suspend fun pointage(quand: String): Long {
        admin.creerAgent("Dupont", "Marie", "Test", "1234")
        val agent = admin.agents().first().id
        return base.pointageDao().inserer(
            PointageEntite(agentId = agent, type = "ENTREE", horodatage = Instant.parse(quand).toEpochMilli(),
                fuseau = paris.id, statut = "VALIDE", origine = "BORNE"),
        )
    }

    @Test fun sans_reglages_rien_n_est_envoye_ni_enregistre() = runBlocking {
        assertEquals(ResultatSauvegarde.NonConfiguree, service.envoyer())
        assertEquals(0, faux.appels)
        assertTrue(depot.sauvegardesRecentes().isEmpty())
    }

    @Test fun un_envoi_reussi_chiffre_le_fichier_marque_les_pointages_et_purge() = runBlocking {
        configurer()
        val recent = pointage("2026-11-02T08:00:00Z")
        val ancien = pointage("2026-06-01T08:00:00Z")

        val resultat = service.envoyer()

        assertEquals(ResultatSauvegarde.Reussie(pointagesPurges = 1), resultat)
        assertEquals(motDePasseSmtp, faux.motDePasseRecu)
        assertEquals("sauvegarde-badgeuse-2026-11-04.bdgs", faux.nomPiece)
        // La pièce jointe est chiffrée : illisible en clair, lisible avec le mot de passe de sauvegarde.
        assertTrue(ChiffrementSauvegarde.estChiffre(faux.piece!!))
        assertFalse(String(faux.piece!!, Charsets.ISO_8859_1).contains("Dupont"))
        val json = String(ChiffrementSauvegarde.dechiffrer(faux.piece!!, motDePasseSauvegarde)!!, Charsets.UTF_8)
        assertTrue(json.contains("Dupont"))
        // Le message lui-même ne contient aucune donnée personnelle ni aucun mot de passe.
        assertFalse(faux.corps!!.contains("Dupont") || faux.corps!!.contains(motDePasseSauvegarde))
        assertNotNull(base.pointageDao().parId(recent)!!.envoyeLe)
        assertNull("ancien et envoyé : purgé", base.pointageDao().parId(ancien))
        assertEquals("REUSSIE", depot.sauvegardesRecentes().first().statut)
    }

    @Test fun un_echec_reseau_est_trace_et_ne_marque_aucun_pointage() = runBlocking {
        configurer()
        val id = pointage("2026-06-01T08:00:00Z")
        faux.echec = EchecEnvoi("Connexion au serveur impossible ou trop lente.", reessayer = true)

        val resultat = service.envoyer() as ResultatSauvegarde.Echec

        assertTrue(resultat.reessayer)
        val trace = depot.sauvegardesRecentes().first()
        assertEquals("ECHEC", trace.statut)
        assertEquals("Connexion au serveur impossible ou trop lente.", trace.messageErreur)
        assertNull("pas envoyé : jamais marqué, jamais purgé (T-35)", base.pointageDao().parId(id)!!.envoyeLe)
        assertNotNull(base.pointageDao().parId(id))
    }

    @Test fun apres_un_echec_la_sauvegarde_suivante_reussit_et_efface_l_alerte() = runBlocking {
        configurer()
        pointage("2026-11-02T08:00:00Z")
        faux.echec = EchecEnvoi("Connexion impossible", reessayer = true)
        service.envoyer()
        faux.echec = null
        horloge.maintenant = Instant.parse("2026-11-04T10:00:00Z")

        assertTrue(service.envoyer() is ResultatSauvegarde.Reussie)
        assertEquals("REUSSIE", depot.sauvegardesRecentes().first().statut)
    }

    @Test fun des_mots_de_passe_absents_donnent_un_echec_clair_sans_envoi() = runBlocking {
        reglages.enregistrer(config, motDePasseSmtp = "", motDePasseSauvegarde = "")
        val resultat = service.envoyer() as ResultatSauvegarde.Echec
        assertFalse(resultat.reessayer)
        assertEquals(0, faux.appels)
        assertEquals("ECHEC", depot.sauvegardesRecentes().first().statut)
    }

    @Test fun les_mots_de_passe_ne_sont_jamais_ecrits_en_clair_dans_la_base() = runBlocking {
        configurer()
        val smtp = base.parametreDao().valeur("smtp_mdp_chiffre")!!
        val sauvegarde = base.parametreDao().valeur("sauvegarde_mdp_chiffre")!!
        assertFalse(smtp.contains(motDePasseSmtp))
        assertFalse(sauvegarde.contains(motDePasseSauvegarde))
        assertEquals(motDePasseSmtp, reglages.motDePasseSmtp())
        assertEquals(motDePasseSauvegarde, reglages.motDePasseSauvegarde())
    }

    @Test fun un_mot_de_passe_vide_conserve_l_ancien_et_les_reglages_sont_relus() = runBlocking {
        configurer()
        reglages.enregistrer(config.copy(destinataire = "autre@exemple.fr", frequence = FrequenceSauvegarde.HEBDOMADAIRE), "", null)
        assertEquals(motDePasseSmtp, reglages.motDePasseSmtp())
        assertEquals(motDePasseSauvegarde, reglages.motDePasseSauvegarde())
        val relu = reglages.lire()!!
        assertEquals("autre@exemple.fr", relu.destinataire)
        assertEquals(FrequenceSauvegarde.HEBDOMADAIRE, relu.frequence)
    }

    @Test fun le_coffre_chiffre_dechiffre_et_refuse_une_valeur_alteree() {
        val a = coffre.chiffrer("secret")
        val b = coffre.chiffrer("secret")
        assertNotEquals("vecteur aléatoire : deux chiffrements diffèrent", a, b)
        assertEquals("secret", coffre.dechiffrer(a))
        assertNull(coffre.dechiffrer(a.dropLast(3) + "AAA"))
        assertNull(coffre.dechiffrer("pas-un-chiffre"))
        assertNull(coffre.dechiffrer(""))
    }
}
