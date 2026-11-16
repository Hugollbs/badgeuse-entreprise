package fr.mairie.badgeuse.domaine

/**
 * Sécurité de la connexion au serveur. [AUCUNE] n'existe que pour les tests (serveur local) :
 * l'interface ne la propose pas et l'envoi la refuse en usage normal.
 */
enum class SecuriteSmtp { SSL, STARTTLS, AUCUNE }

enum class FrequenceSauvegarde(val jours: Long) { QUOTIDIENNE(1), HEBDOMADAIRE(7) }

/** Les réglages d'envoi, sans aucun mot de passe : ceux-ci vivent chiffrés dans le coffre. */
data class ConfigurationEmail(
    val hote: String,
    val port: Int,
    val securite: SecuriteSmtp,
    val utilisateur: String,
    val expediteur: String,
    val destinataire: String,
    val frequence: FrequenceSauvegarde,
)

const val LONGUEUR_MIN_MOT_DE_PASSE_SAUVEGARDE = 12

fun motDePasseSauvegardeValide(motDePasse: String) = motDePasse.length >= LONGUEUR_MIN_MOT_DE_PASSE_SAUVEGARDE

// Volontairement simple : on écarte les saisies manifestement fausses, c'est le serveur qui tranche le reste.
private val ADRESSE_EMAIL = Regex("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]{2,}$")

fun adresseEmailValide(adresse: String) = ADRESSE_EMAIL.matches(adresse.trim())

/** Renvoie le message à afficher si les réglages sont inutilisables, ou null s'ils sont corrects. */
fun erreurConfigurationEmail(c: ConfigurationEmail): String? = when {
    c.hote.isBlank() || c.hote.any { it.isWhitespace() } -> "Le serveur SMTP est obligatoire (exemple : smtp.exemple.fr)."
    c.port !in 1..65535 -> "Le port doit être compris entre 1 et 65535."
    c.utilisateur.isBlank() -> "L'identifiant du compte d'envoi est obligatoire."
    !adresseEmailValide(c.expediteur) -> "L'adresse d'envoi n'est pas une adresse e-mail valide."
    !adresseEmailValide(c.destinataire) -> "L'adresse de réception n'est pas une adresse e-mail valide."
    else -> null
}
