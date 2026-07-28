package com.pennywiseai.ynab.data.state

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PostingStateModule {

    @Binds
    @Singleton
    abstract fun bindPostingStateStore(impl: SharedPrefsPostingStateStore): PostingStateStore
}
