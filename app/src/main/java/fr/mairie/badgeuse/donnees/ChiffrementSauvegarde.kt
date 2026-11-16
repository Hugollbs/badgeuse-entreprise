package fr.mairie.badgeuse.donnees

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement du fichier de sauvegarde (ADR-003) : AES-256-GCM, clé dérivée d'un mot de passe.
 *
 * Disposition du fichier : SIGNATURE (5 octets) | sel (16) | vecteur d'initialisation (12) | données chiffrées + étiquette.
 * GCM est un chiffrement authentifié : un fichier modifié, ou ouvert avec un mauvais mot de passe,
 * est refusé au lieu de produire des données fausses. Le sel et le vecteur sont tirés au hasard à
 * chaque fichier : deux sauvegardes identiques donnent deux fichiers différents.
 */
object ChiffrementSauvegarde {
    private val SIGNATURE = "BDGS1".toByteArray(Charsets.US_ASCII)
    private const val TAILLE_SEL = 16
    private const val TAILLE_VECTEUR = 12
    private const val TAILLE_ETIQUETTE_BITS = 128
    private const val TOURS = 120_000
    private const val LONGUEUR_CLE_BITS = 256

    /** Un fichier chiffré commence par la signature ; une sauvegarde en clair commence par « { ». */
    fun estChiffre(octets: ByteArray): Boolean =
        octets.size > SIGNATURE.size && SIGNATURE.indices.all { octets[it] == SIGNATURE[it] }

    fun chiffrer(contenu: ByteArray, motDePasse: String): ByteArray {
        val sel = aleatoire(TAILLE_SEL)
        val vecteur = aleatoire(TAILLE_VECTEUR)
        val chiffre = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, deriverCle(motDePasse, sel), GCMParameterSpec(TAILLE_ETIQUETTE_BITS, vecteur))
            // La signature est authentifiée aussi : on ne peut pas changer l'en-tête sans casser l'étiquette.
            updateAAD(SIGNATURE)
            doFinal(contenu)
        }
        return SIGNATURE + sel + vecteur + chiffre
    }

    /** Renvoie le contenu en clair, ou null si le mot de passe est faux, le fichier altéré ou trop court. */
    fun dechiffrer(fichier: ByteArray, motDePasse: String): ByteArray? {
        val debutDonnees = SIGNATURE.size + TAILLE_SEL + TAILLE_VECTEUR
        if (!estChiffre(fichier) || fichier.size <= debutDonnees) return null
        val sel = fichier.copyOfRange(SIGNATURE.size, SIGNATURE.size + TAILLE_SEL)
        val vecteur = fichier.copyOfRange(SIGNATURE.size + TAILLE_SEL, debutDonnees)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, deriverCle(motDePasse, sel), GCMParameterSpec(TAILLE_ETIQUETTE_BITS, vecteur))
                updateAAD(SIGNATURE)
                doFinal(fichier, debutDonnees, fichier.size - debutDonnees)
            }
        } catch (e: GeneralSecurityException) {
            // Étiquette invalide : mauvais mot de passe ou fichier modifié. On ne distingue pas les deux, volontairement.
            null
        }
    }

    private fun deriverCle(motDePasse: String, sel: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(motDePasse.toCharArray(), sel, TOURS, LONGUEUR_CLE_BITS)
        val octets = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(octets, "AES")
    }

    private fun aleatoire(taille: Int) = ByteArray(taille).also { SecureRandom().nextBytes(it) }
}
