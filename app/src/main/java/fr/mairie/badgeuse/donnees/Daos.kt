package fr.mairie.badgeuse.donnees

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Insert suspend fun inserer(agent: AgentEntite): Long
    @Update suspend fun mettreAJour(agent: AgentEntite)

    @Query("SELECT * FROM agent WHERE actif = 1 ORDER BY nom, prenom")
    suspend fun actifs(): List<AgentEntite>

    /** Se réémet à chaque changement : un agent créé ou désactivé apparaît tout de suite à l'accueil. */
    @Query("SELECT * FROM agent WHERE actif = 1 ORDER BY nom, prenom")
    fun actifsEnContinu(): Flow<List<AgentEntite>>

    @Query("SELECT * FROM agent ORDER BY nom, prenom")
    suspend fun tous(): List<AgentEntite>

    @Query("SELECT * FROM agent WHERE id = :id")
    suspend fun parId(id: Long): AgentEntite?
}

@Dao
interface PointageDao {
    @Insert suspend fun inserer(pointage: PointageEntite): Long
    @Update suspend fun mettreAJour(pointage: PointageEntite)

    @Query("SELECT * FROM pointage WHERE id = :id")
    suspend fun parId(id: Long): PointageEntite?

    /** Un agent ne voit que ses propres pointages (T-15). */
    @Query("SELECT * FROM pointage WHERE agentId = :agentId ORDER BY horodatage DESC")
    suspend fun deLAgent(agentId: Long): List<PointageEntite>

    @Query("SELECT * FROM pointage WHERE agentId = :agentId ORDER BY horodatage DESC LIMIT :limite")
    suspend fun derniersDeLAgent(agentId: Long, limite: Int): List<PointageEntite>

    @Query("SELECT * FROM pointage WHERE horodatage >= :debut AND horodatage < :fin ORDER BY horodatage")
    suspend fun entre(debut: Long, fin: Long): List<PointageEntite>

    @Query("SELECT * FROM pointage WHERE agentId = :agentId AND horodatage >= :debut AND horodatage < :fin ORDER BY horodatage")
    suspend fun deLAgentEntre(agentId: Long, debut: Long, fin: Long): List<PointageEntite>

    @Query("SELECT * FROM pointage WHERE envoyeLe IS NULL")
    suspend fun nonEnvoyes(): List<PointageEntite>

    @Query("UPDATE pointage SET envoyeLe = :date WHERE envoyeLe IS NULL")
    suspend fun marquerEnvoyes(date: Long)

    /** Marque seulement les pointages réellement contenus dans le fichier envoyé (ceux de [ids]). */
    @Query("UPDATE pointage SET envoyeLe = :date WHERE id IN (:ids)")
    suspend fun marquerEnvoyesParIds(ids: List<Long>, date: Long)

    @Query("SELECT * FROM pointage ORDER BY id")
    suspend fun tous(): List<PointageEntite>

    @Query("SELECT COUNT(*) FROM pointage")
    suspend fun compter(): Int

    @Query("SELECT * FROM pointage WHERE envoyeLe IS NOT NULL")
    suspend fun dejaEnvoyes(): List<PointageEntite>

    /** Seule suppression de l'application : la purge technique (modele-donnees, section 3). */
    @Query("DELETE FROM pointage WHERE id IN (:ids)")
    suspend fun supprimerPourPurge(ids: List<Long>)
}

@Dao
interface JournalDao {
    @Insert suspend fun inserer(entree: JournalEntite): Long

    @Query("SELECT * FROM journal_modification WHERE pointageId = :pointageId ORDER BY date")
    suspend fun duPointage(pointageId: Long): List<JournalEntite>

    @Query("SELECT * FROM journal_modification ORDER BY id")
    suspend fun tous(): List<JournalEntite>

    @Query("SELECT * FROM journal_modification ORDER BY date DESC, id DESC LIMIT :limite")
    suspend fun recents(limite: Int): List<JournalEntite>

    @Query("DELETE FROM journal_modification WHERE pointageId IN (:pointageIds)")
    suspend fun supprimerPourPurge(pointageIds: List<Long>)
}

@Dao
interface AnomalieDao {
    /** IGNORE : si l'anomalie existe déjà (index unique), rien n'est inséré (T-19). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun inserer(anomalie: AnomalieEntite): Long

    @Query("SELECT * FROM anomalie WHERE statut = 'OUVERTE' ORDER BY jour DESC")
    suspend fun ouvertes(): List<AnomalieEntite>

    @Query("SELECT * FROM anomalie ORDER BY id")
    suspend fun toutes(): List<AnomalieEntite>

    @Query("SELECT * FROM anomalie WHERE jour = :jour")
    suspend fun duJour(jour: String): List<AnomalieEntite>

    @Query("UPDATE anomalie SET statut = 'TRAITEE' WHERE id = :id")
    suspend fun marquerTraitee(id: Long)
}

@Dao
interface SauvegardeDao {
    @Insert suspend fun inserer(sauvegarde: SauvegardeEntite): Long

    @Query("SELECT * FROM sauvegarde ORDER BY date DESC LIMIT 1")
    suspend fun derniere(): SauvegardeEntite?

    @Query("SELECT * FROM sauvegarde ORDER BY date DESC, id DESC LIMIT :limite")
    suspend fun recentes(limite: Int): List<SauvegardeEntite>
}

@Dao
interface SecuriteDao {
    @Insert suspend fun inserer(evenement: EvenementSecuriteEntite): Long

    @Query("SELECT * FROM evenement_securite ORDER BY date DESC")
    suspend fun tous(): List<EvenementSecuriteEntite>

    @Query("SELECT * FROM evenement_securite ORDER BY date DESC, id DESC LIMIT :limite")
    suspend fun recents(limite: Int): List<EvenementSecuriteEntite>
}

@Dao
interface ParametreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enregistrer(parametre: ParametreEntite)

    @Query("SELECT valeur FROM parametre WHERE cle = :cle")
    suspend fun valeur(cle: String): String?
}
