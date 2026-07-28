package com.pennywiseai.ynab.data.local

import android.content.Context
import androidx.room.Room
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PennyWiseDatabase =
        Room.databaseBuilder(context, PennyWiseDatabase::class.java, "pennywise.db").build()

    @Provides
    fun provideProcessedMessageDao(db: PennyWiseDatabase): ProcessedMessageDao =
        db.processedMessageDao()

    @Provides
    fun provideMappingRuleDao(db: PennyWiseDatabase): MappingRuleDao =
        db.mappingRuleDao()
}
