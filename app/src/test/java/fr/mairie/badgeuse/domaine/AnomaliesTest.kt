package fr.mairie.badgeuse.domaine

import fr.mairie.badgeuse.domaine.TypePointage.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AnomaliesTest {
    private val jour = LocalDate.of(2026, 11, 3)

    @Test fun `T-17 entree sans sortie donne une sortie manquante`() {
        val a = anomalieDeLaJournee(1, listOf(pointage(ENTREE, "2026-11-03T08:00")), jour, PARIS)
        assertEquals(TypeAnomalie.SORTIE_MANQUANTE, a?.type)
    }

    @Test fun `pause ouverte donne une pause non terminee`() {
        val p = listOf(pointage(ENTREE, "2026-11-03T08:00"), pointage(DEBUT_PAUSE, "2026-11-03T12:00"))
        assertEquals(TypeAnomalie.PAUSE_NON_TERMINEE, anomalieDeLaJournee(1, p, jour, PARIS)?.type)
    }

    @Test fun `T-18 journee complete sans anomalie`() {
        val p = listOf(pointage(ENTREE, "2026-11-03T08:00"), pointage(SORTIE, "2026-11-03T17:00"))
        assertNull(anomalieDeLaJournee(1, p, jour, PARIS))
    }

    @Test fun `un agent absent n'a pas d'anomalie`() {
        assertNull(anomalieDeLaJournee(1, emptyList(), jour, PARIS))
    }

    @Test fun `T-19 relancer la detection ne cree pas de doublon`() {
        val parAgent = mapOf(1L to listOf(pointage(ENTREE, "2026-11-03T08:00")))
        val premiere = anomaliesANouvellementDetecter(parAgent, emptyList(), jour, PARIS)
        assertEquals(1, premiere.size)
        val seconde = anomaliesANouvellementDetecter(parAgent, premiere, jour, PARIS)
        assertEquals(0, seconde.size)
    }
}
