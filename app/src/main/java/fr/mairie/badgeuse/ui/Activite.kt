package fr.mairie.badgeuse.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Signale une activité de l'utilisateur pour repousser le retour automatique à l'accueil.
 * Fournie une fois dans MainActivity : les boîtes de dialogue et la saisie au clavier ne passent
 * pas par la fenêtre principale, elles doivent donc la signaler elles-mêmes.
 */
val LocalActivite = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * À appeler avec vrai juste avant d'ouvrir le sélecteur de fichiers d'Android, et avec faux à son
 * retour : pendant qu'il est ouvert, le retour automatique à l'accueil est suspendu. Sinon, après
 * 30 s passées à choisir un dossier, l'espace admin se fermait et le fichier restait vide (audit B4).
 */
val LocalSelecteurFichier = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** Observe tous les touchers sans les consommer (passe Initial), pour ne gêner aucun bouton. */
fun Modifier.observerActivite(activite: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            activite()
        }
    }
}
