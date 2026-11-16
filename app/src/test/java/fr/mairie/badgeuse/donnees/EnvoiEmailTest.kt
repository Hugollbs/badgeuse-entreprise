package fr.mairie.badgeuse.donnees

import fr.mairie.badgeuse.domaine.ConfigurationEmail
import fr.mairie.badgeuse.domaine.FrequenceSauvegarde
import fr.mairie.badgeuse.domaine.SecuriteSmtp
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.Properties
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.internet.MimeMessage
import kotlin.concurrent.thread

/**
 * Faux serveur SMTP : une seule conversation, sur la machine locale, en clair. Il suffit à vérifier ce
 * que l'application envoie (message, pièce jointe, identifiants) sans compte de messagerie réel.
 */
private class FauxServeurSmtp(private val refuserIdentifiants: Boolean = false) : AutoCloseable {
    private val serveur = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = serveur.localPort

    @Volatile var message: ByteArray? = null
    @Volatile var identifiants: String? = null
    private val fil = thread(isDaemon = true) { conversation() }

    private fun conversation() = try {
        serveur.accept().use { socket ->
            val entree = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val sortie = socket.getOutputStream().bufferedWriter(Charsets.ISO_8859_1)
            fun dire(ligne: String) { sortie.write(ligne + "\r\n"); sortie.flush() }
            fun decoder(b64: String) = String(Base64.getDecoder().decode(b64.trim()), Charsets.UTF_8)

            dire("220 localhost ESMTP test")
            while (true) {
                val ligne = entree.readLine() ?: break
                when {
                    ligne.startsWith("EHLO", true) || ligne.startsWith("HELO", true) -> { dire("250-localhost"); dire("250 AUTH LOGIN PLAIN") }
                    ligne.startsWith("AUTH LOGIN", true) -> {
                        var utilisateur = ligne.substring(10).trim()
                        if (utilisateur.isEmpty()) { dire("334 VXNlcm5hbWU6"); utilisateur = entree.readLine() }
                        dire("334 UGFzc3dvcmQ6")
                        val motDePasse = entree.readLine()
                        identifiants = decoder(utilisateur) + "\u0000" + decoder(motDePasse)
                        dire(if (refuserIdentifiants) "535 5.7.8 Authentication failed" else "235 2.7.0 ok")
                    }
                    ligne.startsWith("AUTH PLAIN", true) -> {
                        var jeton = ligne.substring(10).trim()
                        if (jeton.isEmpty()) { dire("334 "); jeton = entree.readLine() }
                        identifiants = decoder(jeton).trimStart('\u0000')
                        dire(if (refuserIdentifiants) "535 5.7.8 Authentication failed" else "235 2.7.0 ok")
                    }
                    ligne.startsWith("DATA", true) -> {
                        dire("354 go")
                        val brut = StringBuilder()
                        while (true) {
                            val l = entree.readLine() ?: break
                            if (l == ".") break
                            brut.append(l).append("\r\n")
                        }
                        message = brut.toString().toByteArray(Charsets.ISO_8859_1)
                        dire("250 2.0.0 queued")
                    }
                    ligne.startsWith("QUIT", true) -> { dire("221 bye"); break }
                    else -> dire("250 ok")
                }
            }
        }
    } catch (e: java.io.IOException) {
        // Le client a coupé : normal en fin de test.
    }

    override fun close() { serveur.close(); fil.join(2000) }
}

class EnvoiEmailTest {
    private val serveurs = mutableListOf<FauxServeurSmtp>()

    @After fun fermer() = serveurs.forEach { it.close() }

    private fun faux(refuser: Boolean = false) = FauxServeurSmtp(refuser).also { serveurs += it }

    private fun config(port: Int, securite: SecuriteSmtp = SecuriteSmtp.AUCUNE) = ConfigurationEmail(
        "localhost", port, securite, "badgeuse@exemple.fr", "badgeuse@exemple.fr",
        "dgs@exemple.fr", FrequenceSauvegarde.QUOTIDIENNE,
    )

    @Test fun `le message arrive avec sa piece jointe intacte`() {
        val serveur = faux()
        val piece = ByteArray(300_000) { (it * 7).toByte() }
        EnvoiEmail(autoriserEnClair = true).envoyer(
            config(serveur.port), "Mot-de-passe-secret", "Sauvegarde Badgeuse - 2026-11-04", "Corps du message",
            "sauvegarde.bdgs", piece,
        )

        val recu = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(serveur.message!!))
        assertEquals("Sauvegarde Badgeuse - 2026-11-04", recu.subject)
        assertEquals("dgs@exemple.fr", recu.allRecipients.single().toString())
        assertEquals("badgeuse@exemple.fr", recu.from.single().toString())
        val parties = recu.content as Multipart
        assertEquals(2, parties.count)
        assertEquals("sauvegarde.bdgs", parties.getBodyPart(1).fileName)
        assertArrayEquals(piece, parties.getBodyPart(1).inputStream.readBytes())
        assertEquals("badgeuse@exemple.fr\u0000Mot-de-passe-secret", serveur.identifiants)
    }

    @Test fun `des identifiants refuses donnent un echec sans nouvel essai et sans fuite du mot de passe`() {
        val serveur = faux(refuser = true)
        try {
            EnvoiEmail(autoriserEnClair = true).envoyer(config(serveur.port), "Mot-de-passe-secret", "s", "c", "f.bdgs", ByteArray(10))
            fail("l'envoi aurait dû échouer")
        } catch (e: EchecEnvoi) {
            assertFalse("inutile de réessayer avec les mêmes identifiants", e.reessayer)
            assertTrue(e.message!!.contains("refusé"))
            assertFalse(e.message!!.contains("Mot-de-passe-secret"))
        }
    }

    @Test fun `un serveur injoignable donne un echec a reessayer`() {
        val portFerme = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            EnvoiEmail(autoriserEnClair = true).envoyer(config(portFerme), "x", "s", "c", "f.bdgs", ByteArray(10))
            fail("l'envoi aurait dû échouer")
        } catch (e: EchecEnvoi) {
            assertTrue(e.reessayer)
            assertNotNull(e.message)
        }
    }

    @Test fun `l'envoi en clair est refuse en usage normal`() {
        val serveur = faux()
        try {
            EnvoiEmail().envoyer(config(serveur.port, SecuriteSmtp.AUCUNE), "x", "s", "c", "f.bdgs", ByteArray(10))
            fail("l'envoi en clair aurait dû être refusé")
        } catch (e: EchecEnvoi) {
            assertFalse(e.reessayer)
            assertTrue(e.message!!.contains("chiffrée"))
        }
        assertEquals("rien ne doit avoir été envoyé", null, serveur.message)
    }
}
