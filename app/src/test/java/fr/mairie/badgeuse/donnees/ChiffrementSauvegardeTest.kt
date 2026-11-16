package fr.mairie.badgeuse.donnees

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChiffrementSauvegardeTest {
    private val contenu = """{"agents":[{"nom":"Dupont"}]}""".toByteArray()
    private val motDePasse = "Sauvegarde-2026!"

    @Test fun `un fichier chiffre se dechiffre avec le bon mot de passe`() {
        val fichier = ChiffrementSauvegarde.chiffrer(contenu, motDePasse)
        assertArrayEquals(contenu, ChiffrementSauvegarde.dechiffrer(fichier, motDePasse))
    }

    @Test fun `le fichier ne contient pas le texte en clair`() {
        val fichier = ChiffrementSauvegarde.chiffrer(contenu, motDePasse)
        assertFalse(String(fichier, Charsets.ISO_8859_1).contains("Dupont"))
    }

    @Test fun `un mauvais mot de passe est refuse`() {
        val fichier = ChiffrementSauvegarde.chiffrer(contenu, motDePasse)
        assertNull(ChiffrementSauvegarde.dechiffrer(fichier, "Autre-mot-de-passe"))
    }

    @Test fun `un fichier modifie est refuse`() {
        val fichier = ChiffrementSauvegarde.chiffrer(contenu, motDePasse)
        val altere = fichier.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertNull(ChiffrementSauvegarde.dechiffrer(altere, motDePasse))
        // Modifier le sel (donc la clé) doit aussi être détecté.
        val selChange = fichier.copyOf().also { it[6] = (it[6] + 1).toByte() }
        assertNull(ChiffrementSauvegarde.dechiffrer(selChange, motDePasse))
    }

    @Test fun `deux chiffrements du meme contenu donnent deux fichiers differents`() {
        val a = ChiffrementSauvegarde.chiffrer(contenu, motDePasse)
        val b = ChiffrementSauvegarde.chiffrer(contenu, motDePasse)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun `les fichiers trop courts ou en clair sont reconnus comme tels`() {
        assertFalse(ChiffrementSauvegarde.estChiffre(contenu))
        assertTrue(ChiffrementSauvegarde.estChiffre(ChiffrementSauvegarde.chiffrer(contenu, motDePasse)))
        assertNull(ChiffrementSauvegarde.dechiffrer("BDGS1".toByteArray(), motDePasse))
        assertNull(ChiffrementSauvegarde.dechiffrer(ByteArray(0), motDePasse))
        assertNull(ChiffrementSauvegarde.dechiffrer(contenu, motDePasse))
    }

    @Test fun `un contenu vide et un grand contenu fonctionnent`() {
        val grand = ByteArray(2_000_000) { (it % 251).toByte() }
        assertArrayEquals(grand, ChiffrementSauvegarde.dechiffrer(ChiffrementSauvegarde.chiffrer(grand, motDePasse), motDePasse))
        assertEquals(0, ChiffrementSauvegarde.dechiffrer(ChiffrementSauvegarde.chiffrer(ByteArray(0), motDePasse), motDePasse)!!.size)
    }
}
