package com.pennywiseai.ynab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity

/**
 * The app's single local database. Pre-release, the version bumps freely on any
 * schema change — Room recreates the tables via fallbackToDestructiveMigration
 * (no hand-written migrations), and schema export stays off until v1 ships.
 */
@Database(
    entities = [
        ProcessedMessageEntity::class,
        MappingRuleEntity::class,
        BudgetEntity::class,
        AccountEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class PennyWiseDatabase : RoomDatabase() {
    abstract fun processedMessageDao(): ProcessedMessageDao
    abstract fun mappingRuleDao(): MappingRuleDao
    abstract fun snapshotDao(): SnapshotDao
}
