package com.forgery.app.core.database

import android.content.Context
import androidx.room.Room
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
    fun provideDatabase(@ApplicationContext ctx: Context): ForgeryDatabase =
        Room.databaseBuilder(ctx, ForgeryDatabase::class.java, "forgery.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideHistoryDao(db: ForgeryDatabase) = db.historyDao()
    @Provides fun provideComfyDao(db: ForgeryDatabase) = db.comfyTemplateDao()
    @Provides fun provideStyleDao(db: ForgeryDatabase) = db.styleDao()
    @Provides fun provideQueueDao(db: ForgeryDatabase) = db.queueStateDao()
}
