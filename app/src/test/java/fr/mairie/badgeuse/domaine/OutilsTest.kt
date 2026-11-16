package fr.mairie.badgeuse.domaine

import java.time.Instant
import java.time.ZoneId

val PARIS: ZoneId = ZoneId.of("Europe/Paris")

/** Instant à partir d'une heure locale de Paris, plus lisible dans les tests. */
fun paris(texte: String): Instant =
    java.time.LocalDateTime.parse(texte).atZone(PARIS).toInstant()

private var compteur = 0L

fun pointage(
    type: TypePointage,
    quand: String,
    statut: StatutPointage = StatutPointage.VALIDE,
    envoyeLe: Instant? = null,
    agentId: Long = 1,
) = Pointage(++compteur, agentId, type, paris(quand), statut, envoyeLe)
