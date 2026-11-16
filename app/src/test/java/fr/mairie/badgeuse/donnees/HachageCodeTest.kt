package fr.mairie.badgeuse.donnees

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HachageCodeTest {
    @Test fun `le bon code est accepte et un mauvais refuse`() {
        val sel = HachageCode.genererSel()
        val empreinte = HachageCode.hacher("1234", sel)
        assertTrue(HachageCode.verifier("1234", sel, empreinte))
        assertFalse(HachageCode.verifier("1235", sel, empreinte))
    }

    @Test fun `l'empreinte ne contient pas le code`() {
        val sel = HachageCode.genererSel()
        assertFalse(HachageCode.hacher("1234", sel).contains("1234"))
    }

    @Test fun `deux sels differents donnent deux empreintes differentes`() {
        assertNotEquals(HachageCode.genererSel(), HachageCode.genererSel())
        assertNotEquals(
            HachageCode.hacher("1234", HachageCode.genererSel()),
            HachageCode.hacher("1234", HachageCode.genererSel()),
        )
    }

    @Test fun `le nombre de tours est enregistre avec l'empreinte et peut differer`() {
        val sel = HachageCode.genererSel()
        val rapide = HachageCode.hacher("1234", sel, HachageCode.TOURS_CODE_AGENT)
        val lente = HachageCode.hacher("1234", sel)
        assertTrue(rapide.startsWith("10000$"))
        assertTrue(lente.startsWith("120000$"))
        assertTrue(HachageCode.verifier("1234", sel, rapide))
        assertTrue(HachageCode.verifier("1234", sel, lente))
        assertFalse(HachageCode.verifier("1235", sel, rapide))
    }

    @Test fun `une empreinte d'avant le format a tours est toujours acceptee`() {
        // Calculée avec 120 000 tours puis écrite sans préfixe, comme avant : doit rester valide.
        val sel = HachageCode.genererSel()
        val ancienne = HachageCode.hacher("1234", sel).substringAfter('$')
        assertTrue(HachageCode.verifier("1234", sel, ancienne))
        assertFalse(HachageCode.verifier("4321", sel, ancienne))
    }

    @Test fun `un nombre de tours trafique est refuse sans lancer le calcul`() {
        val sel = HachageCode.genererSel()
        assertFalse(HachageCode.verifier("1234", sel, "2000000000\$abc"))
        assertFalse(HachageCode.verifier("1234", sel, "5\$abc"))
        assertFalse(HachageCode.verifier("1234", sel, "xyz\$abc"))
    }

    @Test fun `une meme entree donne toujours la meme empreinte`() {
        val sel = HachageCode.genererSel()
        assertEquals(HachageCode.hacher("0000", sel), HachageCode.hacher("0000", sel))
    }
}
