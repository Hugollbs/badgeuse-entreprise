package fr.mairie.badgeuse.domaine

import fr.mairie.badgeuse.domaine.TypePointage.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class EtatAgentTest {
    private val soir = paris("2026-11-04T18:00")

    private fun etat(vararg p: Pointage) = etatDeLAgent(p.toList(), soir, PARIS)

    @Test fun `T-03 entree, pause, fin de pause, sortie s'enchainent`() {
        val p = mutableListOf<Pointage>()
        assertEquals(setOf(ENTREE), actionsAutorisees(etatDeLAgent(p, soir, PARIS)))
        p += pointage(ENTREE, "2026-11-04T08:00")
        assertEquals(setOf(DEBUT_PAUSE, SORTIE), actionsAutorisees(etatDeLAgent(p, soir, PARIS)))
        p += pointage(DEBUT_PAUSE, "2026-11-04T12:00")
        assertEquals(setOf(FIN_PAUSE, SORTIE), actionsAutorisees(etatDeLAgent(p, soir, PARIS)))
        p += pointage(FIN_PAUSE, "2026-11-04T13:00")
        assertEquals(EtatAgent.EN_SERVICE, etatDeLAgent(p, soir, PARIS))
        p += pointage(SORTIE, "2026-11-04T17:00")
        assertEquals(EtatAgent.HORS_SERVICE, etatDeLAgent(p, soir, PARIS))
    }

    @Test fun `T-04 plusieurs entrees par jour sont autorisees`() {
        val e = etat(
            pointage(ENTREE, "2026-11-04T08:00"), pointage(SORTIE, "2026-11-04T12:00"),
        )
        assertEquals(true, ENTREE in actionsAutorisees(e))
    }

    @Test fun `T-05 pas de sortie sans entree`() {
        assertEquals(false, SORTIE in actionsAutorisees(etat()))
    }

    @Test fun `T-06 pas d'entree quand l'agent est en service`() {
        val e = etat(pointage(ENTREE, "2026-11-04T08:00"))
        assertEquals(false, ENTREE in actionsAutorisees(e))
    }

    // Règle changée le 05/11/2026 (décision de la mairie) : on peut sortir pendant une pause.
    @Test fun `T-07 la sortie est permise pendant une pause et termine la journee`() {
        val pause = listOf(pointage(ENTREE, "2026-11-04T08:00"), pointage(DEBUT_PAUSE, "2026-11-04T12:00"))
        assertEquals(true, SORTIE in actionsAutorisees(etatDeLAgent(pause, soir, PARIS)))
        val sorti = pause + pointage(SORTIE, "2026-11-04T12:30")
        assertEquals(EtatAgent.HORS_SERVICE, etatDeLAgent(sorti, soir, PARIS))
    }

    @Test fun `pas de travail apres minuit - la journee ouverte la veille ne permet pas de sortir le lendemain`() {
        val p = listOf(pointage(ENTREE, "2026-11-04T22:00"))
        val apresMinuit = paris("2026-11-05T00:30")
        assertEquals(EtatAgent.HORS_SERVICE, etatDeLAgent(p, apresMinuit, PARIS))
        assertEquals(setOf(ENTREE), actionsAutorisees(etatDeLAgent(p, apresMinuit, PARIS)))
        // Et la veille reste une anomalie à corriger par l'administrateur (EF-09).
        assertEquals(TypeAnomalie.SORTIE_MANQUANTE, anomalieDeLaJournee(1, p, java.time.LocalDate.of(2026, 11, 4), PARIS)?.type)
    }

    @Test fun `un pointage annule est ignore`() {
        val e = etat(
            pointage(ENTREE, "2026-11-04T08:00"),
            pointage(SORTIE, "2026-11-04T08:00:10", StatutPointage.ANNULE),
        )
        assertEquals(EtatAgent.EN_SERVICE, e)
    }

    @Test fun `l'ordre de la liste n'a pas d'importance`() {
        val e = etat(
            pointage(SORTIE, "2026-11-04T17:00"), pointage(ENTREE, "2026-11-04T08:00"),
        )
        assertEquals(EtatAgent.HORS_SERVICE, e)
    }

    @Test fun `deux pointages au meme instant - le plus recent par identifiant gagne`() {
        val ancien = pointage(ENTREE, "2026-11-04T08:00")
        val recent = pointage(DEBUT_PAUSE, "2026-11-04T08:00")
        assertEquals(EtatAgent.EN_PAUSE, etat(ancien, recent))
        assertEquals(EtatAgent.EN_PAUSE, etat(recent, ancien))
    }

    @Test fun `les pointages de la veille ne comptent pas`() {
        val e = etat(pointage(ENTREE, "2026-11-03T08:00"))
        assertEquals(EtatAgent.HORS_SERVICE, e)
    }

    @Test fun `T-41 changement d'heure d'automne - jour et heure locaux corrects`() {
        // Retour à l'heure d'hiver à Paris : dimanche 25 octobre 2026, 03h00 -> 02h00.
        val apresMinuitEte = java.time.Instant.parse("2026-10-24T22:30:00Z") // 00:30 CEST le 25
        val avantMinuitHiver = java.time.Instant.parse("2026-10-25T22:59:00Z") // 23:59 CET le 25
        val apresMinuitHiver = java.time.Instant.parse("2026-10-25T23:30:00Z") // 00:30 CET le 26
        assertEquals(25, jourLocal(apresMinuitEte, PARIS).dayOfMonth)
        assertEquals(25, jourLocal(avantMinuitHiver, PARIS).dayOfMonth)
        assertEquals(26, jourLocal(apresMinuitHiver, PARIS).dayOfMonth)
        assertEquals(LocalTime.of(0, 30), heureLocale(apresMinuitEte, PARIS))
        assertEquals(LocalTime.of(23, 59), heureLocale(avantMinuitHiver, PARIS))
    }

    @Test fun `T-41 changement d'heure de printemps`() {
        // Passage à l'heure d'été : dimanche 29 mars 2026, 02h00 -> 03h00.
        val avant = java.time.Instant.parse("2026-03-29T00:59:00Z") // 01:59 CET
        val apres = java.time.Instant.parse("2026-03-29T01:00:00Z") // 03:00 CEST
        assertEquals(LocalTime.of(1, 59), heureLocale(avant, PARIS))
        assertEquals(LocalTime.of(3, 0), heureLocale(apres, PARIS))
    }

    @Test fun `T-10 T-11 annulation possible pendant 15 secondes seulement`() {
        val p = pointage(ENTREE, "2026-11-04T08:00:00")
        assertEquals(true, peutAnnuler(p, paris("2026-11-04T08:00:15")))
        assertEquals(false, peutAnnuler(p, paris("2026-11-04T08:00:16")))
        assertEquals(false, peutAnnuler(p.copy(statut = StatutPointage.ANNULE), paris("2026-11-04T08:00:05")))
    }
}
