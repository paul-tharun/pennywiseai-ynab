package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.bank.BankParserFactory
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.data.remote.YnabApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the pipeline's collaborators that aren't @Inject-constructable:
 * the parser seam (delegating to parser-core), and Plan 1's plain classes
 * (TransactionMapper uses the device default time zone for the YNAB date;
 * MappingResolver is stateless). TransactionPoster is bound to its YNAB impl here.
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    @Provides
    @Singleton
    fun provideSmsParser(): SmsParser =
        SmsParser { body, sender, timestamp -> BankParserFactory.parse(body, sender, timestamp) }

    @Provides
    @Singleton
    fun provideTransactionMapper(): TransactionMapper = TransactionMapper()

    @Provides
    @Singleton
    fun provideMappingResolver(): MappingResolver = MappingResolver()

    @Provides
    @Singleton
    fun provideTransactionPoster(api: YnabApi): TransactionPoster = YnabTransactionPoster(api)
}
