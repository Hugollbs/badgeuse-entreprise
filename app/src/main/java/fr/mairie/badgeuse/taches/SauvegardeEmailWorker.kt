package fr.mairie.badgeuse.taches

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fr.mairie.badgeuse.BadgeuseApp
import fr.mairie.badgeuse.donnees.ResultatSauvegarde

/**
 * Sauvegarde périodique par e-mail (EF-19). Réessaie quelques fois si le réseau manque, puis
 * abandonne jusqu'à la prochaine période : l'échec est déjà enregistré et visible pour l'administrateur (T-32).
 */
class SauvegardeEmailWorker(contexte: Context, parametres: WorkerParameters) : CoroutineWorker(contexte, parametres) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BadgeuseApp
        return when (val r = app.serviceSauvegarde.envoyer()) {
            is ResultatSauvegarde.Reussie, ResultatSauvegarde.NonConfiguree -> Result.success()
            is ResultatSauvegarde.Echec ->
                if (r.reessayer && runAttemptCount < TENTATIVES_MAX - 1) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TENTATIVES_MAX = 3
    }
}
