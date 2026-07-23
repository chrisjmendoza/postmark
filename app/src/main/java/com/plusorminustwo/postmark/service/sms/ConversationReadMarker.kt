package com.plusorminustwo.postmark.service.sms

/**
 * Decides which telephony provider(s) to mark read, and with what selection/args, when the
 * user taps "Mark as read" on a notification.
 *
 * Pulled out of [MarkAsReadReceiver] as a plain-Kotlin pure function (no `android.*` imports)
 * so the one piece of real logic here — thread-scoped update covering SMS + MMS vs. the
 * address-only SMS fallback when no thread id is available — is unit-testable without a
 * ContentResolver or Robolectric. [MarkAsReadReceiver] maps [Table] to the actual
 * `Telephony.Sms`/`Telephony.Mms` URI and column constants at the ContentResolver call site.
 */
object ConversationReadMarker {

    enum class Table { SMS, MMS }

    /** One provider update: which table, its `WHERE`, and bind args. */
    data class ReadUpdate(
        val table: Table,
        val selection: String,
        val selectionArgs: List<String>
    )

    /**
     * @param threadId telephony thread id (Room [com.plusorminustwo.postmark.data.db.entity.ThreadEntity.id]
     *   IS the telephony thread id in this codebase — see SmsSyncHandler/SmsReceiver, which
     *   both read `threadId` straight off the provider cursor and use it verbatim as the
     *   Room id). `<= 0` means it wasn't available to the caller (older notification,
     *   deep-link edge case).
     * @param address  sender address; only used for the SMS fallback when [threadId] is missing.
     *
     * When [threadId] is positive, both SMS and MMS unread rows in that thread are targeted —
     * this is what actually fixes the "MMS stays unread" bug, since MMS has no single stable
     * address column to filter on. When [threadId] is missing, only the address-scoped SMS
     * update runs (the historical behavior) since there's no thread to scope an MMS update to.
     */
    fun buildUpdates(threadId: Long, address: String): List<ReadUpdate> {
        return if (threadId > 0L) {
            val threadArgs = listOf(threadId.toString())
            listOf(
                ReadUpdate(Table.SMS, "thread_id = ? AND read = 0", threadArgs),
                ReadUpdate(Table.MMS, "thread_id = ? AND read = 0", threadArgs)
            )
        } else {
            listOf(
                ReadUpdate(Table.SMS, "address = ? AND read = 0", listOf(address))
            )
        }
    }
}
