package fr.mairie.badgeuse.domaine

import fr.mairie.badgeuse.domaine.TypePointage.ENTREE
import org.junit.Assert.assertEquals
import org.junit.Test

class PurgeTest {
    private val maintenant = paris("2026-11-04T12:00")
    private val envoye = paris("2026-11-01T00:00")

    @Test fun `T-34 vieux et envoye est supprime`() {
        val p = pointage(ENTREE, "2026-06-01T08:00", envoyeLe = envoye)
        assertEquals(listOf(p), pointagesAPurger(listOf(p), maintenant, PARIS))
    }

    @Test fun `T-35 vieux mais jamais envoye n'est jamais supprime`() {
        val p = pointage(ENTREE, "2026-06-01T08:00", envoyeLe = null)
        assertEquals(emptyList<Pointage>(), pointagesAPurger(listOf(p), maintenant, PARIS))
    }

    @Test fun `T-36 recent et envoye est conserve`() {
        val p = pointage(ENTREE, "2026-10-01T08:00", envoyeLe = envoye)
        assertEquals(emptyList<Pointage>(), pointagesAPurger(listOf(p), maintenant, PARIS))
    }

    @Test fun `limite exacte de 4 mois est conservee`() {
        val p = pointage(ENTREE, "2026-07-04T12:00", envoyeLe = envoye)
        assertEquals(emptyList<Pointage>(), pointagesAPurger(listOf(p), maintenant, PARIS))
    }

    @Test fun `la duree de conservation reglee par l'administrateur est respectee`() {
        val p = pointage(ENTREE, "2026-06-01T08:00", envoyeLe = envoye) // 5 mois
        assertEquals(emptyList<Pointage>(), pointagesAPurger(listOf(p), maintenant, PARIS, dureeMois = 12))
        assertEquals(listOf(p), pointagesAPurger(listOf(p), maintenant, PARIS, dureeMois = 1))
    }

    @Test fun `la duree de conservation est bornee`() {
        assertEquals(false, dureeConservationValide(0))
        assertEquals(true, dureeConservationValide(DUREE_CONSERVATION_MIN_MOIS))
        assertEquals(true, dureeConservationValide(DUREE_CONSERVATION_MAX_MOIS))
        assertEquals(false, dureeConservationValide(DUREE_CONSERVATION_MAX_MOIS + 1))
    }
}
