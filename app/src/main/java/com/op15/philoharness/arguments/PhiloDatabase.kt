package com.op15.philoharness.arguments

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class EnumConverters {
    @TypeConverter
    fun fromStatus(value: EpistemicStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): EpistemicStatus = EpistemicStatus.valueOf(value)

    @TypeConverter
    fun fromReasoning(value: ReasoningType): String = value.name

    @TypeConverter
    fun toReasoning(value: String): ReasoningType = ReasoningType.valueOf(value)

    @TypeConverter
    fun fromEvidenceKind(value: EvidenceKind): String = value.name

    @TypeConverter
    fun toEvidenceKind(value: String): EvidenceKind = EvidenceKind.valueOf(value)
}

/**
 * Taille attendue : milliers a dizaines de milliers d'arguments (audit Android,
 * conclusion agent faisabilite : "terrain solide et eprouve, pas de risque technique
 * majeur identifie"). WAL active au niveau du Builder applicatif, pas ici.
 */
@Database(
    entities = [ArgumentEntity::class, ObjectionEntity::class, EvidenceEntity::class, RevisionEntity::class],
    version = 6, // v6 : ajout lastSmtLib2/lastSolverVerdict/lastSolverDetail/lastSolverUsed (vue detail d'un argument, TODO.md)
    // false pour ce squelette (pas de room.schemaLocation configure) -- a activer
    // avec le plugin Gradle androidx.room avant toute migration reelle en production.
    exportSchema = false,
)
@TypeConverters(EnumConverters::class)
abstract class PhiloDatabase : RoomDatabase() {
    abstract fun argumentDao(): ArgumentDao
}
