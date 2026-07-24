package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.ScheduledMessageDao
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.data.db.entity.toEntity
import com.plusorminustwo.postmark.domain.scheduled.ScheduledMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository over the `scheduled_messages` table. Maps [ScheduledMessage] domain objects to/from
 * [com.plusorminustwo.postmark.data.db.entity.ScheduledMessageEntity]. Shared by
 * [com.plusorminustwo.postmark.ui.thread.ThreadViewModel] (create/observe/edit/cancel) and
 * [com.plusorminustwo.postmark.service.scheduled.ScheduledSendWorker] (read/delete on fire).
 */
@Singleton
class ScheduledMessageRepository @Inject constructor(
    private val dao: ScheduledMessageDao
) {
    /** Inserts a scheduled send and returns its new id (the WorkManager job key). */
    suspend fun insert(message: ScheduledMessage): Long = dao.insert(message.toEntity())

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun getById(id: Long): ScheduledMessage? = dao.getById(id)?.toDomain()

    /** Live scheduled sends for a thread, soonest first — drives the scheduled bubble section. */
    fun observeByThread(threadId: Long): Flow<List<ScheduledMessage>> =
        dao.observeByThread(threadId).map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<ScheduledMessage> = dao.getAll().map { it.toDomain() }
}
