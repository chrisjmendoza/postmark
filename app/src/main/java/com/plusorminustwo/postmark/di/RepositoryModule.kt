package com.plusorminustwo.postmark.di

import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.SearchRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.data.db.dao.MessageDao
import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.dao.SearchDao
import com.plusorminustwo.postmark.data.db.dao.ThreadDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that wires DAOs into the repository layer.
 *
 * All repositories are singletons so the same [StateFlow] / [Flow] instances
 * are shared across the entire process lifetime.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideThreadRepository(dao: ThreadDao): ThreadRepository = ThreadRepository(dao)

    @Provides
    @Singleton
    fun provideMessageRepository(
        messageDao: MessageDao,
        reactionDao: ReactionDao
    ): MessageRepository = MessageRepository(messageDao, reactionDao)

    @Provides
    @Singleton
    fun provideSearchRepository(dao: SearchDao, reactionDao: ReactionDao): SearchRepository = SearchRepository(dao, reactionDao)
}
