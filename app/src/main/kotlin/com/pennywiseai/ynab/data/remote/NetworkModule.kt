package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.token.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

/**
 * Provides the single-destination YNAB client. There is deliberately NO logging
 * interceptor — it could record the Authorization header (the PAT). The Json is
 * lenient (ignoreUnknownKeys) because YNAB returns far more per object than the
 * app models.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://api.ynab.com/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true // send approved/cleared even at their defaults
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenStore: TokenStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()

    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideRetrofit(json: Json, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideYnabApi(retrofit: Retrofit): YnabApi = retrofit.create(YnabApi::class.java)
}
