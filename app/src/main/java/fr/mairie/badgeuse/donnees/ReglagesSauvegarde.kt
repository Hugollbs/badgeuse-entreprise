package fr.mairie.badgeuse.donnees

import fr.mairie.badgeuse.domaine.ConfigurationEmail
import fr.mairie.badgeuse.domaine.FrequenceSauvegarde
import fr.mairie.badgeuse.domaine.SecuriteSmtp

/**
 * Réglages d'envoi de la sauvegarde. Les réglages simples sont dans la table `parametre` ; les deux
 * mots de passe n'y sont qu'en texte chiffré par le [CoffreSecret]. Aucun mot de passe n'est relu
 * vers l'interface : l'écran sait seulement s'il existe ([aMotDePasseSmtp], [aMotDePasseSauvegarde]).
 */
class ReglagesSauvegarde(private val base: BaseDeDonnees, private val coffre: CoffreSecret) {
    private val parametres get() = base.parametreDao()

    /** Null tant que les réglages ne sont pas complets. */
    suspend fun lire(): ConfigurationEmail? {
        val hote = parametres.valeur(HOTE) ?: return null
        return ConfigurationEmail(
            hote = hote,
            port = parametres.valeur(PORT)?.toIntOrNull() ?: return null,
            securite = parametres.valeur(SECURITE)?.let { runCatching { SecuriteSmtp.valueOf(it) }.getOrNull() } ?: return null,
            utilisateur = parametres.valeur(UTILISATEUR) ?: return null,
            expediteur = parametres.valeur(EXPEDITEUR) ?: return null,
            destinataire = parametres.valeur(DESTINATAIRE) ?: return null,
            frequence = parametres.valeur(FREQUENCE)?.let { runCatching { FrequenceSauvegarde.valueOf(it) }.getOrNull() } ?: return null,
        )
    }

    /** Un mot de passe à null ou vide reste inchangé : on ne demande pas de le ressaisir à chaque modification. */
    suspend fun enregistrer(config: ConfigurationEmail, motDePasseSmtp: String?, motDePasseSauvegarde: String?) {
        parametres.enregistrer(ParametreEntite(HOTE, config.hote.trim()))
        parametres.enregistrer(ParametreEntite(PORT, config.port.toString()))
        parametres.enregistrer(ParametreEntite(SECURITE, config.securite.name))
        parametres.enregistrer(ParametreEntite(UTILISATEUR, config.utilisateur.trim()))
        parametres.enregistrer(ParametreEntite(EXPEDITEUR, config.expediteur.trim()))
        parametres.enregistrer(ParametreEntite(DESTINATAIRE, config.destinataire.trim()))
        parametres.enregistrer(ParametreEntite(FREQUENCE, config.frequence.name))
        if (!motDePasseSmtp.isNullOrEmpty()) parametres.enregistrer(ParametreEntite(MDP_SMTP, coffre.chiffrer(motDePasseSmtp)))
        if (!motDePasseSauvegarde.isNullOrEmpty()) parametres.enregistrer(ParametreEntite(MDP_SAUVEGARDE, coffre.chiffrer(motDePasseSauvegarde)))
    }

    suspend fun motDePasseSmtp(): String? = parametres.valeur(MDP_SMTP)?.let(coffre::dechiffrer)
    suspend fun motDePasseSauvegarde(): String? = parametres.valeur(MDP_SAUVEGARDE)?.let(coffre::dechiffrer)
    suspend fun aMotDePasseSmtp(): Boolean = motDePasseSmtp() != null
    suspend fun aMotDePasseSauvegarde(): Boolean = motDePasseSauvegarde() != null

    private companion object {
        const val HOTE = "smtp_hote"
        const val PORT = "smtp_port"
        const val SECURITE = "smtp_securite"
        const val UTILISATEUR = "smtp_utilisateur"
        const val EXPEDITEUR = "smtp_expediteur"
        const val DESTINATAIRE = "smtp_destinataire"
        const val FREQUENCE = "sauvegarde_frequence"
        const val MDP_SMTP = "smtp_mdp_chiffre"
        const val MDP_SAUVEGARDE = "sauvegarde_mdp_chiffre"
    }
}
