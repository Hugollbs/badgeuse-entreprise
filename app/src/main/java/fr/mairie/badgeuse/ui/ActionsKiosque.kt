package fr.mairie.badgeuse.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Ce que l'espace administrateur sait du mode kiosque et ce qu'il peut en faire. */
data class ActionsKiosque(
    val proprietaire: Boolean,
    val verrouille: Boolean,
    val quitterTemporairement: () -> Unit,
    val retirerDroits: () -> Unit,
)

val LocalKiosque = staticCompositionLocalOf { ActionsKiosque(false, false, {}, {}) }
