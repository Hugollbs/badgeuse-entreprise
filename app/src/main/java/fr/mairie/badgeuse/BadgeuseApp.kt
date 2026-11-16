package fr.mairie.badgeuse

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.room.Room
import fr.mairie.badgeuse.donnees.BaseDeDonnees
import fr.mairie.badgeuse.donnees.CoffreKeystore
import fr.mairie.badgeuse.donnees.DepotAdmin
import fr.mairie.badgeuse.donnees.DepotBadgeuse
import fr.mairie.badgeuse.donnees.DepotSauvegarde
import fr.mairie.badgeuse.donnees.EnvoiEmail
import fr.mairie.badgeuse.donnees.ReglagesSauvegarde
import fr.mairie.badgeuse.donnees.ServiceSauvegarde
import fr.mairie.badgeuse.taches.PlanificationTaches
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

class BadgeuseApp : Application() {
    lateinit var depot: DepotBadgeuse
        private set
    lateinit var depotAdmin: DepotAdmin
        private set
    lateinit var depotSauvegarde: DepotSauvegarde
        private set
    lateinit var reglagesSauvegarde: ReglagesSauvegarde
        private set
    lateinit var serviceSauvegarde: ServiceSauvegarde
        private set

    override fun onCreate() {
        super.onCreate()
        val base = Room.databaseBuilder(this, BaseDeDonnees::class.java, "badgeuse.db").build()
        // Le fuseau est celui de la tablette (réglé en automatique en mode kiosque).
        depot = DepotBadgeuse(base, Clock.systemUTC(), ZoneId.systemDefault())
        depotAdmin = DepotAdmin(base, Clock.systemUTC(), ZoneId.systemDefault())
        depotSauvegarde = DepotSauvegarde(base, Clock.systemUTC(), ZoneId.systemDefault())
        reglagesSauvegarde = ReglagesSauvegarde(base, CoffreKeystore())
        serviceSauvegarde = ServiceSauvegarde(
            depotSauvegarde, reglagesSauvegarde, EnvoiEmail(), Clock.systemUTC(), ZoneId.systemDefault(),
        )
        PlanificationTaches.planifierDetection(this)
        // Si l'envoi est déjà configuré, on s'assure que la tâche périodique existe (après une mise à jour, par exemple).
        // Une erreur ici (base illisible...) ne doit pas faire planter l'application au démarrage :
        // l'administrateur pourra toujours réenregistrer les réglages, ce qui reprogramme l'envoi.
        CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e("BadgeuseApp", "Reprogrammation de la sauvegarde impossible", e)
        }).launch {
            reglagesSauvegarde.lire()?.let { PlanificationTaches.planifierSauvegarde(this@BadgeuseApp, it.frequence) }
        }

        // Agents FICTIFS pour essayer l'application. Uniquement dans une version de développement :
        // jamais dans la version installée à la mairie. L'administrateur créera les vrais agents.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            CoroutineScope(Dispatchers.IO).launch {
                if (depot.agentsActifs().isEmpty()) {
                    depot.creerAgent("Dupont", "Marie", "Administratif", "1234")
                    depot.creerAgent("Martin", "Paul", "Entretien", "1234")
                    depot.creerAgent("Bernard", "Léa", "Accueil", "1234")
                    depot.creerAgent("Moreau", "Julien", "Technique", "1234")
                }
            }
        }
    }
}
