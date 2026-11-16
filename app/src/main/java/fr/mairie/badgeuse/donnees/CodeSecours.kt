package fr.mairie.badgeuse.donnees

import java.security.SecureRandom

/**
 * Code de secours (EF-23) : sert uniquement à réinitialiser le mot de passe administrateur.
 * Alphabet sans caractères ambigus (pas de 0/O, 1/I) pour pouvoir le recopier depuis du papier.
 * 12 caractères parmi 32 = 60 bits : impossible à deviner, contrairement à un code à 4 chiffres.
 */
object CodeSecours {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LONGUEUR = 12

    fun generer(): String {
        val hasard = SecureRandom()
        return (1..LONGUEUR).map { ALPHABET[hasard.nextInt(ALPHABET.length)] }
            .chunked(4).joinToString("-") { it.joinToString("") }
    }

    /** Majuscules, sans tirets ni espaces : la saisie reste tolérante. */
    fun normaliser(saisie: String): String =
        saisie.uppercase().filter { it.isLetterOrDigit() }
}
