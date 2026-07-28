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
 * The app's single local database. Entities are added task-by-task in Plan 2;
 * version stays at 1 (pre-release, no migrations) and schema export is off until
 * v1 ships.
 */
@Database(
    entities = [
        ProcessedMessageEntity::class,
        MappingRuleEntity::class,
        BudgetEntity::class,
        AccountEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class PennyWiseDatabase : RoomDatabase() {
    abstract fun processedMessageDao(): ProcessedMessageDao
    abstract fun mappingRuleDao(): MappingRuleDao
    abstract fun snapshotDao(): SnapshotDao
}
