package fr.mairie.badgeuse.donnees

import fr.mairie.badgeuse.domaine.ConfigurationEmail
import fr.mairie.badgeuse.domaine.SecuriteSmtp
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import java.util.logging.Logger
import javax.activation.DataHandler
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import javax.net.ssl.SSLException

/** Échec d'envoi avec un message lisible par l'administrateur, qui ne contient jamais de mot de passe. */
class EchecEnvoi(message: String, val reessayer: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** Ce que fait l'envoi, sans dire comment : permet de le remplacer par un faux dans les tests. */
fun interface Expediteur {
    @Throws(EchecEnvoi::class)
    fun envoyer(config: ConfigurationEmail, motDePasse: String, sujet: String, corps: String, nomPiece: String, piece: ByteArray)
}

/**
 * Envoi par SMTP (ADR-002). La connexion est toujours chiffrée (SSL ou STARTTLS exigé) et le
 * certificat du serveur est vérifié : sans cela, JavaMail accepte n'importe quel certificat.
 * [autoriserEnClair] ne sert qu'aux tests contre un faux serveur local.
 */
class EnvoiEmail(private val autoriserEnClair: Boolean = false) : Expediteur {
    override fun envoyer(config: ConfigurationEmail, motDePasse: String, sujet: String, corps: String, nomPiece: String, piece: ByteArray) {
        if (config.securite == SecuriteSmtp.AUCUNE && !autoriserEnClair) {
            throw EchecEnvoi("Envoi refusé : la connexion doit être chiffrée (SSL ou STARTTLS).", reessayer = false)
        }
        try {
            val session = Session.getInstance(proprietes(config))
            val message = construire(session, config, sujet, corps, nomPiece, piece)
            session.getTransport("smtp").use { transport ->
                transport.connect(config.hote, config.port, config.utilisateur, motDePasse)
                transport.sendMessage(message, message.allRecipients)
            }
        } catch (e: MessagingException) {
            throw traduire(e)
        }
    }

    private fun proprietes(c: ConfigurationEmail) = Properties().apply {
        put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", DELAI_MS)
        put("mail.smtp.timeout", DELAI_MS)
        put("mail.smtp.writetimeout", DELAI_MS)
        when (c.securite) {
            SecuriteSmtp.SSL -> {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            }
            SecuriteSmtp.STARTTLS -> {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            }
            SecuriteSmtp.AUCUNE -> Unit
        }
    }

    private fun construire(session: Session, c: ConfigurationEmail, sujet: String, corps: String, nomPiece: String, piece: ByteArray) =
        MimeMessage(session).apply {
            setFrom(InternetAddress(c.expediteur.trim()))
            setRecipient(Message.RecipientType.TO, InternetAddress(c.destinataire.trim()))
            setSubject(sujet, "UTF-8")
            setContent(
                MimeMultipart().apply {
                    addBodyPart(MimeBodyPart().apply { setText(corps, "UTF-8") })
                    addBodyPart(
                        MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(piece, "application/octet-stream"))
                            fileName = nomPiece
                        },
                    )
                },
            )
            saveChanges()
        }

    /** Remonte la chaîne des causes pour dire en français ce qui s'est passé, sans recopier le texte du serveur. */
    private fun traduire(e: MessagingException): EchecEnvoi {
        val causes = generateSequence<Throwable>(e) { it.cause }.toList()
        // Pour le support, dans le journal système seulement. Les erreurs de réseau et de TLS sont citées avec leur
        // message (ex. « ECONNREFUSED ») ; pour les autres, seul le nom de la classe : un message de serveur de
        // messagerie peut citer une adresse. Jamais de mot de passe. L'administrateur, lui, voit un texte générique.
        journal.warning(
            "Échec d'envoi, chaîne des causes : " + causes.joinToString(" <- ") {
                val nom = it.javaClass.name
                val systeme = nom.startsWith("java.") || nom.startsWith("android.system.") || nom.startsWith("javax.net.ssl.")
                if (systeme) nom + ": " + it.message?.take(160) else nom
            },
        )
        return when {
            e is AuthenticationFailedException ->
                EchecEnvoi("Identifiant ou mot de passe refusé par le serveur d'envoi.", reessayer = false, cause = e)
            causes.any { it is SSLException } ->
                EchecEnvoi("Connexion sécurisée refusée : certificat du serveur ou type de sécurité incorrect.", reessayer = false, cause = e)
            causes.any { it is UnknownHostException } ->
                EchecEnvoi("Serveur introuvable : vérifiez son adresse et la connexion Internet de la tablette.", reessayer = true, cause = e)
            causes.any { it is ConnectException || it is SocketTimeoutException } ->
                EchecEnvoi("Connexion au serveur impossible ou trop lente.", reessayer = true, cause = e)
            e is SendFailedException ->
                EchecEnvoi("Le serveur a refusé le message : vérifiez les adresses d'envoi et de réception.", reessayer = false, cause = e)
            else -> EchecEnvoi("Échec de l'envoi de l'e-mail.", reessayer = true, cause = e)
        }
    }

    private companion object {
        const val DELAI_MS = "20000"

        // java.util.logging : écrit dans logcat sur Android, et fonctionne sans simulation dans les tests JVM.
        val journal: Logger = Logger.getLogger("EnvoiEmail")
    }
}

// Transport n'implémente pas AutoCloseable : ce petit « use » garantit la fermeture, même en cas d'erreur.
private inline fun <R> Transport.use(bloc: (Transport) -> R): R =
    try { bloc(this) } finally { runCatching { close() } }
