package com.plusorminustwo.postmark.data.db.entity

import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.Thread
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the hand-written `toEntity()`/`toDomain()` mappers in [MessageEntity], [ThreadEntity]
 * and [ReactionEntity] as inverses of each other.
 *
 * Every entity constructor parameter has a default, so a domain model that gains a new field
 * still compiles fine even if `toEntity()` forgets to pass it along — the new field would
 * silently persist as the entity's default instead of the real value. Round-tripping a fully
 * populated domain object through `toEntity().toDomain()` and asserting equality is what turns
 * that silent data loss into a failing test.
 */
class EntityMappingRoundTripTest {

    // ---- Message -----------------------------------------------------------------------

    @Test fun `minimal SMS message round trips through entity`() {
        val message = Message(
            id = 1L,
            threadId = 1L,
            address = "+15551234567",
            body = "hello",
            timestamp = 1_000L,
            isSent = true,
            type = 2
        )
        assertEquals(message, message.toEntity().toDomain())
    }

    @Test fun `fully populated MMS message round trips through entity`() {
        // uri/mimeType below deliberately contain a double-quote and a backslash to
        // exercise the escapeJson path inside encodeAttachmentsJson/decodeAttachmentsJson.
        val attachments = listOf(
            MessageAttachment(uri = "content://mms/part/1", mimeType = "image/jpeg"),
            MessageAttachment(
                uri = "content://mms/part/2\\weird\"name",
                mimeType = "video/mp4\"quoted\\type"
            ),
            MessageAttachment(uri = "content://mms/part/3", mimeType = "audio/amr")
        )
        val message = Message(
            id = MMS_ID_OFFSET + 42L,
            threadId = 7L,
            address = "+15559876543",
            body = "check this out",
            timestamp = 5_000L,
            isSent = false,
            type = 2,
            deliveryStatus = DELIVERY_STATUS_QUEUED,
            isMms = true,
            attachments = attachments,
            isRead = false,
            isStarred = true,
            isPinned = true,
            sentAt = 5_100L,
            deliveredAt = 5_200L,
            remindAt = 5_300L
        )
        assertEquals(message, message.toEntity().toDomain())
    }

    @Test fun `single attachment message persists denormalized attachmentUri and mimeType columns`() {
        val attachment = MessageAttachment(uri = "content://mms/part/9", mimeType = "image/png")
        val message = Message(
            id = 2L,
            threadId = 1L,
            address = "+15551234567",
            body = "",
            timestamp = 2_000L,
            isSent = false,
            type = 1,
            isMms = true,
            attachments = listOf(attachment)
        )
        val entity = message.toEntity()
        assertEquals(message, entity.toDomain())
        // toEntity() also persists Message's computed first-attachment properties into
        // the denormalized columns (used by pre-v12 fallback and single-media queries).
        assertEquals(attachment.uri, entity.attachmentUri)
        assertEquals(attachment.mimeType, entity.mimeType)
    }

    @Test fun `reactions are not persisted by the message mapper`() {
        // Reactions live in a separate ReactionEntity table and are assembled onto a
        // Message by the repository layer, not by MessageEntity's toEntity()/toDomain().
        // So a Message with reactions round-trips to itself MINUS its reactions, not to
        // an identical copy — that asymmetry is intentional, not a bug to fix.
        val message = Message(
            id = 3L,
            threadId = 1L,
            address = "+15551234567",
            body = "nice",
            timestamp = 3_000L,
            isSent = false,
            type = 1,
            reactions = listOf(
                Reaction(
                    id = 1L,
                    messageId = 3L,
                    senderAddress = "+15559876543",
                    emoji = "❤️",
                    timestamp = 3_050L,
                    rawText = "Loved “nice”"
                )
            )
        )
        assertEquals(message.copy(reactions = emptyList()), message.toEntity().toDomain())
    }

    @Test fun `legacy pre-v12 row with null mimeType falls back to octet-stream`() {
        val entity = MessageEntity(
            id = 4L,
            threadId = 1L,
            address = "+15551234567",
            body = "",
            timestamp = 4_000L,
            isSent = false,
            isMms = true,
            attachmentUri = "content://mms/part/1",
            mimeType = null,
            attachmentsJson = null
        )
        assertEquals(
            listOf(MessageAttachment("content://mms/part/1", "application/octet-stream")),
            entity.toDomain().attachments
        )
    }

    @Test fun `legacy pre-v12 row with known mimeType preserves it`() {
        val entity = MessageEntity(
            id = 5L,
            threadId = 1L,
            address = "+15551234567",
            body = "",
            timestamp = 4_100L,
            isSent = false,
            isMms = true,
            attachmentUri = "content://mms/part/2",
            mimeType = "image/jpeg",
            attachmentsJson = null
        )
        assertEquals(
            listOf(MessageAttachment("content://mms/part/2", "image/jpeg")),
            entity.toDomain().attachments
        )
    }

    // ---- Thread --------------------------------------------------------------------------

    @Test fun `minimal thread round trips through entity, empty participants stay empty`() {
        // encodeParticipantsJson(emptyList()) returns null (size <= 1 keeps the column
        // NULL for ordinary 1:1 threads); decodeParticipantsJson(null) returns emptyList(),
        // so the round trip holds without ever storing "[]".
        val thread = Thread(
            id = 1L,
            displayName = "Alice",
            address = "+15551234567",
            lastMessageAt = 1_000L
        )
        assertEquals(thread, thread.toEntity().toDomain())
    }

    @Test fun `single participant thread normalizes to empty participants`() {
        // Deliberate non-inverse: encodeParticipantsJson treats size <= 1 as "not a group"
        // and stores NULL, so a lone participant does NOT survive the round trip. This is
        // load-bearing for send routing — recipientsFor() falls back to Thread.address
        // (plain SMS path) whenever participants.size <= 1.
        val thread = Thread(
            id = 3L,
            displayName = "Bob",
            address = "+15559876543",
            lastMessageAt = 3_000L,
            participants = listOf("+15559876543")
        )
        assertEquals(thread.copy(participants = emptyList()), thread.toEntity().toDomain())
    }

    @Test fun `fully populated thread round trips through entity`() {
        val thread = Thread(
            id = 2L,
            displayName = "Group Chat",
            address = "+15551234567",
            lastMessageAt = 2_000L,
            lastMessagePreview = "see you then",
            backupPolicy = BackupPolicy.NEVER_INCLUDE,
            isMuted = true,
            isPinned = true,
            notificationsEnabled = false,
            nickname = "The Crew",
            participants = listOf("+15551234567", "+15559876543", "+15551112222"),
            accentColorArgb = 0xFF00FF00.toInt(),
            chatBackgroundId = "sunset",
            sentColorArgb = 0xFF0000FF.toInt(),
            isSpam = true
        )
        assertEquals(thread, thread.toEntity().toDomain())
    }

    // ---- Reaction ------------------------------------------------------------------------

    @Test fun `fully populated reaction round trips in both directions`() {
        val reaction = Reaction(
            id = 9L,
            messageId = 3L,
            senderAddress = "+15559876543",
            emoji = "😂",
            timestamp = 3_050L,
            rawText = "Laughed at “nice”"
        )
        assertEquals(reaction, reaction.toEntity().toDomain())

        val entity = ReactionEntity(
            id = 9L,
            messageId = 3L,
            senderAddress = "+15559876543",
            emoji = "😂",
            timestamp = 3_050L,
            rawText = "Laughed at “nice”"
        )
        assertEquals(entity, entity.toDomain().toEntity())
    }
}
