package com.pennywiseai.ynab.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity

@Dao
interface MappingRuleDao {

    /**
     * Insert a route. ABORT (the default) so a duplicate (bankName, last4) raises
     * SQLiteConstraintException rather than silently overwriting — the UI validates
     * before calling, and the crash is a last-line integrity guarantee. Returns the
     * new row id.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: MappingRuleEntity): Long

    @Update
    suspend fun update(rule: MappingRuleEntity)

    @Delete
    suspend fun delete(rule: MappingRuleEntity)

    @Query("SELECT * FROM mapping_rules ORDER BY bankName, last4")
    suspend fun getAll(): List<MappingRuleEntity>
}
