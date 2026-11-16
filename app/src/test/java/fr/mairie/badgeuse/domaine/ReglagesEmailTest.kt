package fr.mairie.badgeuse.domaine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglagesEmailTest {
    private val valide = ConfigurationEmail(
        "smtp.exemple.fr", 465, SecuriteSmtp.SSL, "badgeuse@exemple.fr",
        "badgeuse@exemple.fr", "dgs@exemple.fr", FrequenceSauvegarde.QUOTIDIENNE,
    )

    @Test fun `des reglages complets sont acceptes`() = assertNull(erreurConfigurationEmail(valide))

    @Test fun `chaque champ invalide est signale`() {
        assertNotNull(erreurConfigurationEmail(valide.copy(hote = " ")))
        assertNotNull(erreurConfigurationEmail(valide.copy(hote = "smtp exemple.fr")))
        assertNotNull(erreurConfigurationEmail(valide.copy(port = 0)))
        assertNotNull(erreurConfigurationEmail(valide.copy(port = 70000)))
        assertNotNull(erreurConfigurationEmail(valide.copy(utilisateur = "")))
        assertNotNull(erreurConfigurationEmail(valide.copy(expediteur = "pas-une-adresse")))
        assertNotNull(erreurConfigurationEmail(valide.copy(destinataire = "a@b")))
    }

    @Test fun `les adresses multiples ou avec des chevrons sont refusees`() {
        assertFalse(adresseEmailValide("a@b.fr, c@d.fr"))
        assertFalse(adresseEmailValide("Nom <a@b.fr>"))
        assertTrue(adresseEmailValide(" dgs@exemple.fr "))
    }

    @Test fun `le mot de passe de sauvegarde demande 12 caracteres`() {
        assertFalse(motDePasseSauvegardeValide("court"))
        assertFalse(motDePasseSauvegardeValide("12345678901"))
        assertTrue(motDePasseSauvegardeValide("123456789012"))
    }

    @Test fun `les frequences correspondent a 1 et 7 jours`() {
        assertEquals(1L, FrequenceSauvegarde.QUOTIDIENNE.jours)
        assertEquals(7L, FrequenceSauvegarde.HEBDOMADAIRE.jours)
    }
}
