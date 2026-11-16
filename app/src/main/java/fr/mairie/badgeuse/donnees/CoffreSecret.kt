package fr.mairie.badgeuse.donnees

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stockage de secrets (mot de passe du compte d'envoi, mot de passe de sauvegarde). Interface pour pouvoir le simuler dans les tests. */
interface CoffreSecret {
    fun chiffrer(clair: String): String

    /** Null si la valeur est illisible (clé disparue, valeur altérée). */
    fun dechiffrer(valeur: String): String?
}

/**
 * Coffre adossé à l'Android Keystore : la clé AES est créée et gardée par le système, elle ne peut
 * pas être exportée, même par l'application. Ce qui est écrit dans la base n'est que le texte chiffré,
 * inutilisable hors de cette tablette et de cette installation (ADR-002).
 */
class CoffreKeystore(private val alias: String = "badgeuse-coffre") : CoffreSecret {
    override fun chiffrer(clair: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, cle()) }
        val chiffre = cipher.doFinal(clair.toByteArray(Charsets.UTF_8))
        // Le vecteur d'initialisation est choisi par le Keystore : il faut le garder avec le texte chiffré.
        return encoder(cipher.iv) + SEPARATEUR + encoder(chiffre)
    }

    override fun dechiffrer(valeur: String): String? = try {
        val (vecteur, chiffre) = valeur.split(SEPARATEUR).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, cle(), GCMParameterSpec(128, vecteur))
        }
        String(cipher.doFinal(chiffre), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null // Base64 invalide
    } catch (e: IndexOutOfBoundsException) {
        null // valeur qui n'a pas la forme « vecteur:chiffré »
    }

    private fun cle(): SecretKey {
        val magasin = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (magasin.getKey(alias, null) as? SecretKey)?.let { return it }
        val generateur = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generateur.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generateur.generateKey()
    }

    private fun encoder(octets: ByteArray) = Base64.encodeToString(octets, Base64.NO_WRAP)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SEPARATEUR = ":"
    }
}
