package fr.mairie.badgeuse.taches

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fr.mairie.badgeuse.BadgeuseApp
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Détection des oublis de pointage (EF-09). Elle regarde les 3 derniers jours terminés, pas
 * seulement la veille : si la tablette était éteinte à minuit, le rattrapage est automatique.
 * Ce n'est pas un doublon, la détection ignore les anomalies déjà enregistrées.
 */
class DetectionOublisWorker(contexte: Context, parametres: WorkerParameters) : CoroutineWorker(contexte, parametres) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BadgeuseApp
        val aujourdhui = LocalDate.now(ZoneId.systemDefault())
        val jours = (1L..JOURS_RATTRAPES).map { aujourdhui.minusDays(it) }
        return try {
            app.depotAdmin.detecterAnomalies(jours)
            PlanificationTaches.reprogrammerDetection(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager arrête la tâche (contraintes, fermeture) : il faut laisser l'annulation remonter.
            throw e
        } catch (e: Exception) {
            // On réessaie plus tard ; la détection étant idempotente, rejouer ne fait pas de doublon.
            Result.retry()
        }
    }

    private companion object {
        const val JOURS_RATTRAPES = 3L
    }
}
