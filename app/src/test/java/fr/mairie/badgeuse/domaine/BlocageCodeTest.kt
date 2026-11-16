package fr.mairie.badgeuse.domaine

import org.junit.Assert.assertEquals
import org.junit.Test

class BlocageCodeTest {
    private val t0 = paris("2026-11-04T09:00")

    @Test fun `T-12 trois echecs de suite bloquent l'agent`() {
        var e = EtatCode()
        repeat(3) { e = BlocageCode.apresEchec(e, t0) }
        assertEquals(true, BlocageCode.estBloque(e, t0))
    }

    @Test fun `deux echecs ne bloquent pas`() {
        var e = EtatCode()
        repeat(2) { e = BlocageCode.apresEchec(e, t0) }
        assertEquals(false, BlocageCode.estBloque(e, t0))
    }

    @Test fun `le blocage prend fin apres la duree prevue`() {
        var e = EtatCode()
        repeat(3) { e = BlocageCode.apresEchec(e, t0) }
        val fin = t0.plus(BlocageCode.DUREE_BLOCAGE)
        assertEquals(true, BlocageCode.estBloque(e, fin.minusSeconds(1)))
        assertEquals(false, BlocageCode.estBloque(e, fin))
    }

    @Test fun `T-13 l'administrateur debloque`() {
        var e = EtatCode()
        repeat(3) { e = BlocageCode.apresEchec(e, t0) }
        assertEquals(false, BlocageCode.estBloque(BlocageCode.debloquer(), t0))
    }

    @Test fun `T-14 un succes remet le compteur a zero`() {
        var e = EtatCode()
        repeat(2) { e = BlocageCode.apresEchec(e, t0) }
        e = BlocageCode.apresSucces()
        repeat(2) { e = BlocageCode.apresEchec(e, t0) }
        assertEquals(false, BlocageCode.estBloque(e, t0))
    }

    @Test fun `un essai pendant le blocage ne le prolonge pas`() {
        var e = EtatCode()
        repeat(3) { e = BlocageCode.apresEchec(e, t0) }
        val pendant = BlocageCode.apresEchec(e, t0.plusSeconds(60))
        assertEquals(e, pendant)
    }
}
