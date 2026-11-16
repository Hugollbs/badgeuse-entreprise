package fr.mairie.badgeuse.taches

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import fr.mairie.badgeuse.domaine.FrequenceSauvegarde
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object PlanificationTaches {
    private const val NOM_DETECTION = "detection-oublis"
    private const val NOM_SAUVEGARDE = "sauvegarde-email"

    /** Délai jusqu'au prochain minuit, dans le fuseau de la tablette. */
    fun delaiJusquAMinuit(horloge: Clock, fuseau: ZoneId): Duration {
        val maintenant = ZonedDateTime.now(horloge).withZoneSameInstant(fuseau)
        val minuit = maintenant.toLocalDate().plusDays(1).atStartOfDay(fuseau)
        return Duration.between(maintenant, minuit)
    }

    /**
     * Programme la prochaine détection à 00h00. Une tâche unique qui se reprogramme elle-même :
     * WorkManager ne sait pas déclencher à une heure précise avec une tâche périodique.
     * KEEP évite d'empiler plusieurs tâches si l'application est relancée.
     */
    fun planifierDetection(contexte: Context) {
        val delai = delaiJusquAMinuit(Clock.systemUTC(), ZoneId.systemDefault())
        val requete = OneTimeWorkRequestBuilder<DetectionOublisWorker>()
            .setInitialDelay(delai.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(contexte).enqueueUniqueWork(NOM_DETECTION, ExistingWorkPolicy.KEEP, requete)
    }

    /** Pour la reprogrammation depuis le worker lui-même : la tâche en cours doit être remplacée. */
    fun reprogrammerDetection(contexte: Context) {
        val delai = delaiJusquAMinuit(Clock.systemUTC(), ZoneId.systemDefault())
        val requete = OneTimeWorkRequestBuilder<DetectionOublisWorker>()
            .setInitialDelay(delai.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(contexte).enqueueUniqueWork(NOM_DETECTION, ExistingWorkPolicy.APPEND_OR_REPLACE, requete)
    }

    /**
     * Programme la sauvegarde périodique. UPDATE : un changement de fréquence remplace la tâche
     * existante sans décaler inutilement les envois quand rien ne change. L'envoi n'est tenté que
     * si la tablette a du réseau ; en cas d'échec, WorkManager réessaie avec un délai croissant.
     */
    fun planifierSauvegarde(contexte: Context, frequence: FrequenceSauvegarde) {
        val requete = PeriodicWorkRequestBuilder<SauvegardeEmailWorker>(frequence.jours, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(contexte)
            .enqueueUniquePeriodicWork(NOM_SAUVEGARDE, ExistingPeriodicWorkPolicy.UPDATE, requete)
    }
}
