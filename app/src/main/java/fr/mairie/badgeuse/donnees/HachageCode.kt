package fr.mairie.badgeuse.donnees

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Empreinte d'un code ou d'un mot de passe : PBKDF2 avec sel aléatoire. Le code en clair
 * n'est jamais stocké. Les 10 000 codes à 4 chiffres restent faciles à retrouver si quelqu'un
 * obtient la base, quel que soit le nombre de tours : la protection réelle d'un code agent est le
 * blocage après 3 essais et le chiffrement des sauvegardes (ADR-003).
 *
 * L'empreinte s'écrit « tours$valeur » : le nombre de tours voyage avec elle, donc on peut le
 * changer plus tard sans invalider les empreintes existantes. Sans « $ », c'est une empreinte
 * d'avant ce format, calculée avec [TOURS_ANCIENNES_EMPREINTES].
 */
object HachageCode {
    /** Mot de passe admin et code de secours : peu de saisies, longue chaîne de caractères, on garde un coût élevé. */
    const val TOURS_MOT_DE_PASSE = 120_000

    /** Code agent : saisi à chaque pointage, il faut une réponse rapide même sur une tablette modeste. */
    const val TOURS_CODE_AGENT = 10_000

    private const val TOURS_ANCIENNES_EMPREINTES = 120_000

    // Un nombre de tours lu dans une base ou une sauvegarde est une donnée extérieure : sans borne,
    // un fichier trafiqué pourrait bloquer la tablette avec un calcul interminable.
    private const val TOURS_MIN = 1_000
    private const val TOURS_MAX = 1_000_000
    private const val LONGUEUR_BITS = 256
    private const val TAILLE_SEL = 16

    fun genererSel(): String {
        val octets = ByteArray(TAILLE_SEL).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(octets)
    }

    fun hacher(code: String, sel: String, tours: Int = TOURS_MOT_DE_PASSE): String {
        require(tours in TOURS_MIN..TOURS_MAX) { "nombre de tours hors limites" }
        return "$tours$" + calculer(code, sel, tours)
    }

    /** Comparaison en temps constant, pour ne pas révéler où les empreintes diffèrent. */
    fun verifier(code: String, sel: String, empreinte: String): Boolean {
        val separateur = empreinte.indexOf('$')
        val tours = if (separateur < 0) TOURS_ANCIENNES_EMPREINTES else empreinte.substring(0, separateur).toIntOrNull()
        if (tours == null || tours !in TOURS_MIN..TOURS_MAX) return false
        val attendue = if (separateur < 0) empreinte else empreinte.substring(separateur + 1)
        return MessageDigest.isEqual(calculer(code, sel, tours).toByteArray(), attendue.toByteArray())
    }

    private fun calculer(code: String, sel: String, tours: Int): String {
        val spec = PBEKeySpec(code.toCharArray(), Base64.getDecoder().decode(sel), tours, LONGUEUR_BITS)
        val octets = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(octets)
    }
}
