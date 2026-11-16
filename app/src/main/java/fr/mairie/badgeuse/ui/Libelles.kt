package fr.mairie.badgeuse.ui

import fr.mairie.badgeuse.domaine.EtatAgent
import fr.mairie.badgeuse.domaine.TypePointage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun TypePointage.libelle() = when (this) {
    TypePointage.ENTREE -> "Entrée"
    TypePointage.DEBUT_PAUSE -> "Début de pause"
    TypePointage.FIN_PAUSE -> "Fin de pause"
    TypePointage.SORTIE -> "Sortie"
}

fun EtatAgent.libelle() = when (this) {
    EtatAgent.HORS_SERVICE -> "Vous n'êtes pas en service"
    EtatAgent.EN_SERVICE -> "Vous êtes en service"
    EtatAgent.EN_PAUSE -> "Vous êtes en pause"
}

private val FORMAT_HEURE = DateTimeFormatter.ofPattern("HH:mm")
private val FORMAT_JOUR = DateTimeFormatter.ofPattern("dd/MM")
private val FORMAT_JOUR_COMPLET = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.FRENCH)

fun Instant.heure(): String = atZone(ZoneId.systemDefault()).format(FORMAT_HEURE)
fun Instant.jour(): String = atZone(ZoneId.systemDefault()).format(FORMAT_JOUR)
fun Instant.jourComplet(): String = atZone(ZoneId.systemDefault()).format(FORMAT_JOUR_COMPLET).replaceFirstChar { it.uppercase() }
