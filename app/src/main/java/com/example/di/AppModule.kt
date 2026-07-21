package com.example.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.ai.GeminiApi
import com.example.data.AppDatabase
import com.example.data.CommandDao
import com.example.data.CommandEventDao
import com.example.data.LearnedCommandDao
import com.example.data.MIGRATION_1_2
import com.example.data.MIGRATION_2_3
import com.example.data.MIGRATION_3_4
import com.example.data.MIGRATION_4_5
import com.example.data.ScheduledTaskDao
import com.example.data.SelfStatDao
import com.example.data.UserFactDao
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "hey_manager_db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }

    @Provides
    fun provideCommandDao(database: AppDatabase): CommandDao {
        return database.commandDao()
    }

    @Provides
    fun provideLearnedCommandDao(database: AppDatabase): LearnedCommandDao {
        return database.learnedCommandDao()
    }

    @Provides
    fun provideScheduledTaskDao(database: AppDatabase): ScheduledTaskDao {
        return database.scheduledTaskDao()
    }

    @Provides
    fun provideCommandEventDao(database: AppDatabase): CommandEventDao {
        return database.commandEventDao()
    }

    @Provides
    fun provideUserFactDao(database: AppDatabase): UserFactDao {
        return database.userFactDao()
    }

    @Provides
    fun provideSelfStatDao(database: AppDatabase): SelfStatDao {
        return database.selfStatDao()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    // ---------------- Network (Gemini REST) ----------------

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(retrofit: Retrofit): GeminiApi {
        return retrofit.create(GeminiApi::class.java)
    }
}
