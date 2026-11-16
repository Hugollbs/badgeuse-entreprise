package fr.mairie.badgeuse.domaine

import fr.mairie.badgeuse.donnees.CodeSecours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifiantsTest {
    @Test fun `un code agent a exactement 4 chiffres`() {
        assertTrue(codeAgentValide("0042"))
        assertFalse(codeAgentValide("123"))
        assertFalse(codeAgentValide("12345"))
        assertFalse(codeAgentValide("12a4"))
        assertFalse(codeAgentValide(""))
    }

    @Test fun `T-21 un code agent n'est pas un mot de passe administrateur valide`() {
        assertFalse(motDePasseAdminValide("1234"))
        assertFalse(motDePasseAdminValide("        "))
        assertTrue(motDePasseAdminValide("Mairie-2026!"))
    }

    @Test fun `le code de secours a 12 caracteres en 3 groupes et se normalise`() {
        val code = CodeSecours.generer()
        assertEquals(14, code.length)
        assertEquals(3, code.split("-").size)
        assertEquals(12, CodeSecours.normaliser(code).length)
        assertEquals("ABCD2345EFGH", CodeSecours.normaliser("abcd-2345 efgh"))
    }

    @Test fun `deux codes de secours generes sont differents`() {
        assertFalse(CodeSecours.generer() == CodeSecours.generer())
    }
}
