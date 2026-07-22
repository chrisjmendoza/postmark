package com.plusorminustwo.postmark.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.BlockedNumberContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One row from the system [BlockedNumberContract] provider. */
data class BlockedNumber(
    /** Provider row id — used to delete the exact row on unblock. */
    val id: Long,
    /** The number as the user originally blocked it (raw, unformatted). */
    val number: String
)

/**
 * Read/unblock access to the system [BlockedNumberContract] provider.
 *
 * The provider is only readable/writable while Postmark is the default SMS app
 * (or default dialer / carrier-privileged) — callers must gate UI on [canBlock];
 * every method here degrades gracefully (empty list / no-op) when it isn't, so a
 * non-default app never crashes.
 *
 * The block *write* still lives inline in ThreadViewModel; this repository covers
 * only the list + unblock the new Blocked-numbers screen needs.
 */
@Singleton
class BlockedNumbersRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    /** True when the current user/app may read and modify the blocked-numbers list. */
    fun canBlock(): Boolean = BlockedNumberContract.canCurrentUserBlockNumbers(context)

    /**
     * Every currently-blocked number, most-recently-blocked first (the provider
     * assigns ascending ids, so descending id ≈ newest first). Returns an empty
     * list when Postmark isn't the default SMS app or the provider throws.
     * Performs a synchronous ContentResolver query — call off the main thread.
     */
    fun getBlockedNumbers(): List<BlockedNumber> {
        if (!canBlock()) return emptyList()
        return try {
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(
                    BlockedNumberContract.BlockedNumbers.COLUMN_ID,
                    BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER
                ),
                null, null,
                "${BlockedNumberContract.BlockedNumbers.COLUMN_ID} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(
                    BlockedNumberContract.BlockedNumbers.COLUMN_ID
                )
                val numberCol = cursor.getColumnIndexOrThrow(
                    BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER
                )
                buildList {
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(numberCol) ?: continue
                        add(BlockedNumber(cursor.getLong(idCol), number))
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Deletes the blocked-numbers row with [id] — the explicit user "unblock" action.
     * This is the [BlockedNumberContract] provider, never content://sms. No-op (returns
     * false) when not default or the provider throws.
     */
    fun unblock(id: Long): Boolean {
        if (!canBlock()) return false
        return try {
            val rowUri = ContentUris.withAppendedId(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI, id
            )
            context.contentResolver.delete(rowUri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }
}
