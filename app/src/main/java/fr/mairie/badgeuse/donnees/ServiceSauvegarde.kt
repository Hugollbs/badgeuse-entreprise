package fr.mairie.badgeuse.donnees

import fr.mairie.badgeuse.domaine.motDePasseSauvegardeValide
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

sealed interface ResultatSauvegarde {
    /** [pointagesPurges] : nombre de pointages de plus de 4 mois supprimés après l'envoi. */
    data class Reussie(val pointagesPurges: Int) : ResultatSauvegarde

    /** [reessayer] : vrai si un nouvel essai a une chance de réussir (réseau), faux si l'administrateur doit corriger quelque chose. */
    data class Echec(val message: String, val reessayer: Boolean) : ResultatSauvegarde
    data object NonConfiguree : ResultatSauvegarde
}

/**
 * Une sauvegarde complète : préparer le fichier, le chiffrer, l'envoyer, puis seulement alors marquer
 * les pointages « envoyés » et purger (EF-19, T-31, T-32). Appelé par la tâche planifiée et par le
 * bouton « Envoyer maintenant ».
 */
class ServiceSauvegarde(
    private val depot: DepotSauvegarde,
    private val reglages: ReglagesSauvegarde,
    private val expediteur: Expediteur,
    private val horloge: Clock,
    private val fuseau: ZoneId,
) {
    // Deux envois en même temps (tâche planifiée + bouton) enverraient deux fois le même fichier.
    private val verrou = Mutex()

    suspend fun envoyer(): ResultatSauvegarde = verrou.withLock {
        val config = reglages.lire() ?: return ResultatSauvegarde.NonConfiguree
        val motDePasseSmtp = reglages.motDePasseSmtp()
            ?: return echec("Le mot de passe du compte d'envoi n'est pas enregistré.", reessayer = false)
        val motDePasseSauvegarde = reglages.motDePasseSauvegarde()?.takeIf(::motDePasseSauvegardeValide)
            ?: return echec("Le mot de passe de sauvegarde n'est pas défini.", reessayer = false)

        val jour = LocalDate.now(horloge.withZone(fuseau))
        val fichier = try {
            val fichier = depot.preparerSauvegarde()
            val chiffre = withContext(Dispatchers.Default) {
                ChiffrementSauvegarde.chiffrer(fichier.contenu.toByteArray(Charsets.UTF_8), motDePasseSauvegarde)
            }
            withContext(Dispatchers.IO) {
                expediteur.envoyer(
                    config, motDePasseSmtp,
                    sujet = "Sauvegarde Badgeuse - $jour",
                    // Aucun nom, aucune heure dans le message : les données sont dans la pièce jointe chiffrée.
                    corps = "Sauvegarde automatique de l'application de badgage.\n\n" +
                        "Le fichier joint est chiffré. Conservez le mot de passe de sauvegarde en lieu sûr : " +
                        "sans lui, la sauvegarde ne peut pas être restaurée.",
                    nomPiece = "sauvegarde-badgeuse-$jour.bdgs",
                    piece = chiffre,
                )
            }
            fichier
        } catch (e: EchecEnvoi) {
            return echec(e.message ?: "Échec de l'envoi.", e.reessayer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Erreur imprévue (lecture de la base, chiffrement, bibliothèque d'envoi) : elle doit quand même
            // être signalée à l'administrateur (§ 5.3), pas faire planter la tâche sans trace.
            Log.e("ServiceSauvegarde", "Échec imprévu de la sauvegarde", e)
            return echec("Erreur technique pendant la préparation ou l'envoi de la sauvegarde.", reessayer = true)
        }
        ResultatSauvegarde.Reussie(depot.confirmerEnvoi(fichier))
    }

    private suspend fun echec(message: String, reessayer: Boolean): ResultatSauvegarde.Echec {
        depot.enregistrerEchec(message)
        return ResultatSauvegarde.Echec(message, reessayer)
    }
}
