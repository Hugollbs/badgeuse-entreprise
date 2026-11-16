package fr.mairie.badgeuse.donnees

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Les dates sont stockées en millisecondes UTC (Long) ou en texte ISO pour un jour :
// pas de convertisseur à maintenir, et le format reste lisible dans une sauvegarde.

@Entity(tableName = "agent")
data class AgentEntite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val prenom: String,
    val pole: String,
    val codeEmpreinte: String,
    val codeSel: String,
    val actif: Boolean = true,
    val echecsConsecutifs: Int = 0,
    val bloqueJusqua: Long? = null,
)

@Entity(
    tableName = "pointage",
    foreignKeys = [ForeignKey(AgentEntite::class, ["id"], ["agentId"])],
    indices = [Index("agentId"), Index("horodatage")],
)
data class PointageEntite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: Long,
    val type: String,
    val horodatage: Long,
    val fuseau: String,
    val statut: String,
    val origine: String,
    val envoyeLe: Long? = null,
)

@Entity(
    tableName = "journal_modification",
    foreignKeys = [ForeignKey(PointageEntite::class, ["id"], ["pointageId"])],
    indices = [Index("pointageId")],
)
data class JournalEntite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pointageId: Long,
    val action: String,
    val ancienneValeur: String?,
    val nouvelleValeur: String?,
    val auteur: String,
    val date: Long,
    val motif: String? = null,
)

/** Index unique : une même anomalie ne peut pas être créée deux fois (T-19). */
@Entity(
    tableName = "anomalie",
    foreignKeys = [ForeignKey(AgentEntite::class, ["id"], ["agentId"])],
    indices = [Index(value = ["agentId", "jour", "type"], unique = true)],
)
data class AnomalieEntite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: Long,
    val jour: String,
    val type: String,
    val statut: String = "OUVERTE",
    val detecteeLe: Long,
)

@Entity(tableName = "sauvegarde")
data class SauvegardeEntite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val statut: String,
    val messageErreur: String? = null,
)

@Entity(tableName = "evenement_securite")
data class EvenementSecuriteEntite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val date: Long,
    val detail: String? = null,
)

@Entity(tableName = "parametre")
data class ParametreEntite(
    @PrimaryKey val cle: String,
    val valeur: String,
)
