package fr.mairie.badgeuse.kiosque

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager

/**
 * Mode kiosque (ADR-001) : l'application reste seule à l'écran, sans barre de navigation ni
 * notifications ni réglages, et se relance toute seule au démarrage de la tablette.
 *
 * Android ne l'autorise que pour le « propriétaire de l'appareil ». Ce statut se donne une seule fois,
 * sur une tablette neuve ou réinitialisée, sans compte configuré, par la commande :
 *   adb shell dpm set-device-owner fr.mairie.badgeuse/.kiosque.AdminAppareil
 * Sans ce statut, rien de tout cela n'est actif : l'application fonctionne normalement.
 */
class Kiosque(private val contexte: Context) {
    private val gestionnaire = contexte.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(contexte, AdminAppareil::class.java)

    // Alias d'activité qui porte le rôle d'« écran d'accueil » : désactivé tant que la tablette n'est
    // pas propriétaire, pour ne pas imposer un choix d'écran d'accueil sur un téléphone ordinaire.
    private val aliasAccueil = ComponentName(contexte, "fr.mairie.badgeuse.AccueilKiosque")

    fun estProprietaire(): Boolean = gestionnaire.isDeviceOwnerApp(contexte.packageName)

    fun estVerrouille(): Boolean =
        contexte.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** À appeler à chaque retour au premier plan : règle l'appareil si besoin, puis verrouille l'écran. */
    fun appliquer(activite: Activity) {
        if (!estProprietaire()) return
        // L'heure reste imposée même pendant une maintenance : les pointages reposent sur elle.
        imposerHeureAutomatique()
        if (maintenance) return
        configurer()
        if (!estVerrouille()) activite.startLockTask()
    }

    /**
     * Sortie pour l'administrateur (mise à jour, réglages de la tablette). Valable jusqu'au prochain
     * démarrage de l'application : le kiosque se remet en place tout seul ensuite. On rend aussi la
     * barre d'état et l'écran d'accueil d'Android : sans eux, sortir du verrouillage ne donnait accès
     * à rien (audit B6). Le réglage de la date et de l'heure, lui, reste interdit.
     */
    fun quitterTemporairement(activite: Activity) {
        maintenance = true
        if (estVerrouille()) activite.stopLockTask()
        if (estProprietaire()) rendreLaTablette()
    }

    /** Rend la tablette à un usage normal. Nécessaire avant de pouvoir désinstaller l'application. */
    fun retirerDroits(activite: Activity) {
        if (!estProprietaire()) return
        maintenance = true
        if (estVerrouille()) activite.stopLockTask()
        rendreLaTablette()
        gestionnaire.setLockTaskPackages(admin, emptyArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            gestionnaire.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        }
        @Suppress("DEPRECATION")
        gestionnaire.clearDeviceOwnerApp(contexte.packageName)
    }

    private fun rendreLaTablette() {
        gestionnaire.setKeyguardDisabled(admin, false)
        gestionnaire.setStatusBarDisabled(admin, false)
        gestionnaire.clearPackagePersistentPreferredActivities(admin, contexte.packageName)
        activerAliasAccueil(false)
    }

    /**
     * Cahier des charges § 5.1 : l'heure est réglée automatiquement par le réseau et personne ne
     * peut la modifier (EF-04 repose entièrement sur l'horloge de la tablette).
     */
    private fun imposerHeureAutomatique() {
        // Cette restriction n'existe qu'à partir d'Android 9 ; avant, l'heure automatique imposée
        // (setAutoTimeRequired) suffit, et les réglages sont de toute façon inaccessibles en kiosque.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            gestionnaire.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gestionnaire.setAutoTimeEnabled(admin, true)
            gestionnaire.setAutoTimeZoneEnabled(admin, true)
        } else {
            @Suppress("DEPRECATION")
            gestionnaire.setAutoTimeRequired(admin, true)
        }
    }

    private fun configurer() {
        gestionnaire.setLockTaskPackages(admin, paquetsAutorises().toTypedArray())
        // Aucune fonction système en mode verrouillé : ni accueil, ni vue d'ensemble, ni notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            gestionnaire.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
        gestionnaire.setKeyguardDisabled(admin, true)
        gestionnaire.setStatusBarDisabled(admin, true)

        // Après un redémarrage, Android lance l'écran d'accueil : on s'y place pour que l'application démarre seule (T-38).
        activerAliasAccueil(true)
        val filtre = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        gestionnaire.addPersistentPreferredActivity(admin, filtre, aliasAccueil)
    }

    /**
     * L'application elle-même, plus les sélecteurs de fichiers du système : sans eux, l'export et la
     * copie (qui ouvrent un écran d'une autre application) seraient bloqués par le kiosque. Leur nom varie selon la marque.
     */
    private fun paquetsAutorises(): Set<String> {
        val gestionnairePaquets = contexte.packageManager
        val selecteurs = listOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT).flatMap { action ->
            val intent = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            gestionnairePaquets.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
        }
        return setOf(contexte.packageName) + selecteurs
    }

    private fun activerAliasAccueil(actif: Boolean) {
        contexte.packageManager.setComponentEnabledSetting(
            aliasAccueil,
            if (actif) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private companion object {
        // En mémoire seulement : un redémarrage de l'application remet le kiosque.
        @Volatile var maintenance = false
    }
}
