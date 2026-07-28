package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory MappingRuleDao. Seed rules via the constructor; getAll + setBroken are exercised. */
class FakeMappingRuleDao(private val rules: MutableList<MappingRuleEntity> = mutableListOf()) : MappingRuleDao {
    private var nextId = 1L

    override suspend fun insert(rule: MappingRuleEntity): Long {
        val id = nextId++
        rules += rule.copy(id = id)
        return id
    }

    override suspend fun update(rule: MappingRuleEntity) {
        rules.replaceAll { if (it.id == rule.id) rule else it }
    }

    override suspend fun delete(rule: MappingRuleEntity) {
        rules.removeAll { it.id == rule.id }
    }

    override suspend fun getAll(): List<MappingRuleEntity> = rules.toList()

    override fun observeAll(): Flow<List<MappingRuleEntity>> =
        flowOf(rules.sortedWith(compareBy({ it.bankName }, { it.last4 })))

    override suspend fun setBroken(bankName: String, last4: String, broken: Boolean) {
        rules.replaceAll {
            if (it.bankName == bankName && it.last4 == last4) it.copy(broken = broken) else it
        }
    }
}
