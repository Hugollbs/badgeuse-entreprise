package fr.mairie.badgeuse.domaine

import fr.mairie.badgeuse.domaine.TypePointage.DEBUT_PAUSE
import fr.mairie.badgeuse.domaine.TypePointage.ENTREE
import fr.mairie.badgeuse.domaine.TypePointage.FIN_PAUSE
import fr.mairie.badgeuse.domaine.TypePointage.SORTIE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class TempsDeTravailTest {
    private val jour = LocalDate.of(2026, 11, 4)

    private fun temps(vararg p: Pointage) = tempsDeLaJournee(p.toList(), jour, PARIS)

    @Test fun `journee classique - entree, pause, fin de pause, sortie`() {
        val t = temps(
            pointage(ENTREE, "2026-11-04T08:00"), pointage(DEBUT_PAUSE, "2026-11-04T12:00"),
            pointage(FIN_PAUSE, "2026-11-04T13:30"), pointage(SORTIE, "2026-11-04T17:15"),
        )!!
        assertEquals(Duration.ofMinutes(4 * 60 + 3 * 60 + 45), t.travail)
        assertEquals(Duration.ofMinutes(90), t.pause)
        assertEquals(true, t.complet)
    }

    @Test fun `plusieurs entrees et sorties s'additionnent`() {
        val t = temps(
            pointage(ENTREE, "2026-11-04T08:00"), pointage(SORTIE, "2026-11-04T12:00"),
            pointage(ENTREE, "2026-11-04T14:00"), pointage(SORTIE, "2026-11-04T16:30"),
        )!!
        assertEquals(Duration.ofMinutes(6 * 60 + 30), t.travail)
        assertEquals(Duration.ZERO, t.pause)
        assertEquals(true, t.complet)
    }

    @Test fun `sortie pendant une pause - la pause s'arrete a la sortie, sans travail en plus`() {
        val t = temps(
            pointage(ENTREE, "2026-11-04T08:00"), pointage(DEBUT_PAUSE, "2026-11-04T12:00"),
            pointage(SORTIE, "2026-11-04T12:20"),
        )!!
        assertEquals(Duration.ofHours(4), t.travail)
        assertEquals(Duration.ofMinutes(20), t.pause)
        assertEquals(true, t.complet)
    }

    @Test fun `periode restee ouverte - non comptee et journee incomplete`() {
        val t = temps(pointage(ENTREE, "2026-11-04T08:00"), pointage(DEBUT_PAUSE, "2026-11-04T12:00"))!!
        assertEquals(Duration.ofHours(4), t.travail)
        assertEquals(false, t.complet)
        assertEquals(false, temps(pointage(ENTREE, "2026-11-04T08:00"))!!.complet)
    }

    @Test fun `les pointages annules sont ignores`() {
        val t = temps(
            pointage(ENTREE, "2026-11-04T08:00"),
            pointage(SORTIE, "2026-11-04T08:05", StatutPointage.ANNULE),
            pointage(SORTIE, "2026-11-04T16:00"),
        )!!
        assertEquals(Duration.ofHours(8), t.travail)
        assertEquals(true, t.complet)
    }

    @Test fun `enchainement incoherent apres une correction - signale incomplet`() {
        val t = temps(pointage(SORTIE, "2026-11-04T08:00"), pointage(ENTREE, "2026-11-04T09:00"), pointage(SORTIE, "2026-11-04T10:00"))!!
        assertEquals(Duration.ofHours(1), t.travail)
        assertEquals(false, t.complet)
    }

    @Test fun `aucun pointage valide ce jour-la - pas de temps`() {
        assertNull(temps())
        assertNull(temps(pointage(ENTREE, "2026-11-03T08:00")))
        assertNull(temps(pointage(ENTREE, "2026-11-04T08:00", StatutPointage.ANNULE)))
    }

    @Test fun `ordre de la liste sans importance`() {
        val t = temps(pointage(SORTIE, "2026-11-04T17:00"), pointage(ENTREE, "2026-11-04T09:00"))!!
        assertEquals(Duration.ofHours(8), t.travail)
    }

    @Test fun `formats d'affichage`() {
        val d = Duration.ofMinutes(7 * 60 + 5)
        assertEquals("7 h 05", formaterDuree(d))
        assertEquals("7:05", formaterDureeTableur(d))
        assertEquals("7,08", formaterHeuresDecimales(d))
        assertEquals("0 h 00", formaterDuree(Duration.ZERO))
    }
}
