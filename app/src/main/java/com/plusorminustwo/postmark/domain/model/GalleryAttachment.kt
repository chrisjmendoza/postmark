package com.plusorminustwo.postmark.domain.model

/**
 * One media attachment flattened out of a [Message] for display in a photo/video gallery
 * grid (contact profile Photos/Videos sections and their full-screen routes).
 *
 * A single MMS message can carry several attachments (e.g. three photos in one text);
 * [messageId] + [attachmentIndex] together give every tile a stable, unique grid key even
 * when two attachments share the same [uri] scheme/message.
 *
 * @param messageId       The owning message's id.
 * @param attachmentIndex Index of this attachment within its message's attachment list
 *                        (PDU order) — part of the stable grid key alongside [messageId].
 * @param uri             Content URI of the media part.
 * @param mimeType        MIME type of the part (an image or video attachment).
 */
data class GalleryAttachment(
    val messageId: Long,
    val attachmentIndex: Int,
    val uri: String,
    val mimeType: String
)

/**
 * Flattens every attachment across [this] list of messages whose [MessageAttachment.mimeType]
 * starts with [mimePrefix] (`"image/"` or `"video/"`), preserving message order — callers
 * pass an already newest-first list (e.g. [MessageRepository][com.plusorminustwo.postmark.data.repository.MessageRepository.observeMediaMessages])
 * so the result stays newest-first: attachments within one message keep their original PDU
 * order, which is the only order multiple parts of a single message have (they share one
 * timestamp).
 */
fun List<Message>.toGalleryAttachments(mimePrefix: String): List<GalleryAttachment> =
    flatMap { message ->
        message.attachments.mapIndexedNotNull { index, attachment ->
            if (attachment.mimeType.startsWith(mimePrefix, ignoreCase = true)) {
                GalleryAttachment(
                    messageId = message.id,
                    attachmentIndex = index,
                    uri = attachment.uri,
                    mimeType = attachment.mimeType
                )
            } else null
        }
    }
