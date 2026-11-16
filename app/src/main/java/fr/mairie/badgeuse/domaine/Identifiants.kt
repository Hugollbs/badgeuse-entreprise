package fr.mairie.badgeuse.domaine

const val LONGUEUR_CODE_AGENT = 4
const val LONGUEUR_MIN_MOT_DE_PASSE = 8

/** Un code agent a exactement 4 chiffres (EF-15). */
fun codeAgentValide(code: String): Boolean =
    code.length == LONGUEUR_CODE_AGENT && code.all { it in '0'..'9' }

/**
 * Le mot de passe administrateur doit être distinct de tout code d'agent (EF-22) : on exige au
 * moins 8 caractères, ce qui exclut d'office un code à 4 chiffres. Les codes agent sont hachés,
 * on ne peut donc pas les comparer un par un.
 */
fun motDePasseAdminValide(motDePasse: String): Boolean =
    motDePasse.length >= LONGUEUR_MIN_MOT_DE_PASSE && motDePasse.isNotBlank()
