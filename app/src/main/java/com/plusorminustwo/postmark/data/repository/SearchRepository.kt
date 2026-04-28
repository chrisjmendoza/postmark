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
        reactionEmoji: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<Message> {
        val ftsQuery = FtsQueryBuilder.build(rawQuery)
        if (ftsQuery.isBlank()) return emptyList()

        val tid = threadId ?: -1L
        val isSentInt = when (isSent) { true -> 1; false -> 0; null -> -1 }
        val sMs = startMs ?: -1L

        return if (reactionEmoji != null) {
            searchDao.searchMessagesFilteredWithReaction(ftsQuery, tid, isSentInt, sMs, reactionEmoji, limit, offset)
        } else {
            searchDao.searchMessagesFiltered(ftsQuery, tid, isSentInt, sMs, limit, offset)
        }.map { it.toDomain() }
    }
}
