package com.example.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.data.AppDatabase
import com.example.data.CommandDao
import com.example.data.ScheduledTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
        ).build()
    }

    @Provides
    fun provideCommandDao(database: AppDatabase): CommandDao {
        return database.commandDao()
    }

    @Provides
    fun provideScheduledTaskDao(database: AppDatabase): ScheduledTaskDao {
        return database.scheduledTaskDao()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }
}
