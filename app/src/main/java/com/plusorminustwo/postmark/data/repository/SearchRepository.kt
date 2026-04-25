package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.SearchDao
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.search.parser.FtsQueryBuilder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val searchDao: SearchDao
) {
    suspend fun search(
        rawQuery: String,
        threadId: Long? = null,
        isSent: Boolean? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<Message> {
        val ftsQuery = FtsQueryBuilder.build(rawQuery)
        if (ftsQuery.isBlank()) return emptyList()

        return when {
            threadId != null && startMs != null && endMs != null ->
                searchDao.searchMessagesInDateRange(ftsQuery, startMs, endMs, limit, offset)
                    .filter { it.threadId == threadId }
                    .map { it.toDomain() }

            threadId != null ->
                searchDao.searchMessagesInThread(ftsQuery, threadId, limit, offset)
                    .map { it.toDomain() }

            isSent != null ->
                searchDao.searchMessagesBySent(ftsQuery, isSent, limit, offset)
                    .map { it.toDomain() }

            startMs != null && endMs != null ->
                searchDao.searchMessagesInDateRange(ftsQuery, startMs, endMs, limit, offset)
                    .map { it.toDomain() }

            else ->
                searchDao.searchMessages(ftsQuery, limit, offset)
                    .map { it.toDomain() }
        }
    }
}
