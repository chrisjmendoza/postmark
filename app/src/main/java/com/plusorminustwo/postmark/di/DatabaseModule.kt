package com.plusorminustwo.postmark.di

import android.content.Context
import androidx.room.Room
import com.plusorminustwo.postmark.data.db.PostmarkDatabase
import com.plusorminustwo.postmark.data.db.dao.*
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
    fun provideDatabase(@ApplicationContext context: Context): PostmarkDatabase =
        Room.databaseBuilder(context, PostmarkDatabase::class.java, PostmarkDatabase.DATABASE_NAME)
            .addCallback(PostmarkDatabase.FTS_CALLBACK)
            .build()

    @Provides fun provideThreadDao(db: PostmarkDatabase): ThreadDao = db.threadDao()
    @Provides fun provideMessageDao(db: PostmarkDatabase): MessageDao = db.messageDao()
    @Provides fun provideReactionDao(db: PostmarkDatabase): ReactionDao = db.reactionDao()
    @Provides fun provideThreadStatsDao(db: PostmarkDatabase): ThreadStatsDao = db.threadStatsDao()
    @Provides fun provideSearchDao(db: PostmarkDatabase): SearchDao = db.searchDao()
}
