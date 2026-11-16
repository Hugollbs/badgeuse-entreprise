package fr.mairie.badgeuse.domaine

import java.time.Duration
import java.time.Instant

/** Compteur d'échecs de code d'un agent, tel que stocké dans la table agent. */
data class EtatCode(
    val echecsConsecutifs: Int = 0,
    val bloqueJusqua: Instant? = null,
)

/** Blocage temporaire après 3 codes erronés de suite (EF-21). */
object BlocageCode {
    const val MAX_ECHECS = 3
    val DUREE_BLOCAGE: Duration = Duration.ofMinutes(5)

    fun estBloque(etat: EtatCode, maintenant: Instant): Boolean =
        etat.bloqueJusqua != null && maintenant.isBefore(etat.bloqueJusqua)

    /**
     * Enregistre un code erroné. Au 3e échec on bloque et on remet le compteur à 0 :
     * à la fin du blocage l'agent retrouve 3 essais.
     * Pendant un blocage, un essai n'est pas compté : l'état ne change pas.
     */
    fun apresEchec(etat: EtatCode, maintenant: Instant): EtatCode {
        if (estBloque(etat, maintenant)) return etat
        val echecs = etat.echecsConsecutifs + 1
        return if (echecs >= MAX_ECHECS) {
            EtatCode(echecsConsecutifs = 0, bloqueJusqua = maintenant.plus(DUREE_BLOCAGE))
        } else {
            EtatCode(echecsConsecutifs = echecs, bloqueJusqua = null)
        }
    }

    /** Un code correct remet tout à zéro (T-14). */
    fun apresSucces(): EtatCode = EtatCode()

    /** Déblocage par l'administrateur (T-13). */
    fun debloquer(): EtatCode = EtatCode()
}
