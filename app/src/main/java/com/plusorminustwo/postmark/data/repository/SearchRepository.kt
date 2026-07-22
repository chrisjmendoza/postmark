package com.plusorminustwo.postmark.data.repository

import com.plusorminustwo.postmark.data.db.dao.ReactionDao
import com.plusorminustwo.postmark.data.db.dao.SearchDao
import com.plusorminustwo.postmark.data.db.entity.MessageEntity
import com.plusorminustwo.postmark.data.db.entity.toDomain
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.attachReactions
import com.plusorminustwo.postmark.search.parser.FtsQueryBuilder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that delegates full-text search to [SearchDao] and distinct-emoji
 * observation to [ReactionDao].
 *
 * The [search] function translates optional Kotlin-typed parameters to the sentinel
 * integer values expected by the DAO. When there is no text query but an MMS filter
 * is present it falls through to the non-FTS [SearchDao.browseFiltered] path.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val searchDao: SearchDao,
    private val reactionDao: ReactionDao
) {
    fun observeDistinctEmojis(): Flow<List<String>> = reactionDao.observeDistinctEmojis()

    suspend fun search(
        rawQuery: String,
        threadId: Long? = null,
        isSent: Boolean? = null,
        startMs: Long? = null,
        isMms: Boolean? = null,
        reactionEmoji: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<Message> {
        val tid      = threadId ?: -1L
        val isSentInt = when (isSent) { true -> 1; false -> 0; null -> -1 }
        val sMs      = startMs ?: -1L
        val isMmsInt  = when (isMms) { true -> 1; false -> 0; null -> -1 }

        // When there's no text query but an isMms filter is set, use the plain
        // browse query instead of FTS (FTS requires a non-empty match term).
        val ftsQuery = FtsQueryBuilder.build(rawQuery)
        val entities: List<MessageEntity> = if (ftsQuery.isBlank()) {
            if (isMms == null) return emptyList()
            searchDao.browseFiltered(tid, isSentInt, sMs, isMmsInt, limit, offset)
        } else if (reactionEmoji != null) {
            searchDao.searchMessagesFilteredWithReaction(ftsQuery, tid, isSentInt, sMs, isMmsInt, reactionEmoji, limit, offset)
        } else {
            searchDao.searchMessagesFiltered(ftsQuery, tid, isSentInt, sMs, isMmsInt, limit, offset)
        }

        val messages = entities.map { it.toDomain() }
        if (messages.isEmpty()) return messages

        // One batched reactions lookup for the whole result set (not per row), then
        // the same in-memory join the thread view uses — so search rows can show pills.
        val reactions = reactionDao.getByMessageIds(messages.map { it.id }).map { it.toDomain() }
        return attachReactions(messages, reactions)
    }
}
