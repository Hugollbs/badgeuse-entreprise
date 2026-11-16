package fr.mairie.badgeuse.donnees

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentEntite::class, PointageEntite::class, JournalEntite::class,
        AnomalieEntite::class, SauvegardeEntite::class,
        EvenementSecuriteEntite::class, ParametreEntite::class,
    ],
    version = 1,
    // Le schéma est écrit dans app/schemas/ : il servira à écrire et tester les migrations futures.
    exportSchema = true,
)
abstract class BaseDeDonnees : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun pointageDao(): PointageDao
    abstract fun journalDao(): JournalDao
    abstract fun anomalieDao(): AnomalieDao
    abstract fun sauvegardeDao(): SauvegardeDao
    abstract fun securiteDao(): SecuriteDao
    abstract fun parametreDao(): ParametreDao
}
