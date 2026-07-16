package com.plusorminustwo.postmark.data.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Reverse-looks-up the contact display name for [address] via
 * [ContactsContract.PhoneLookup]. Returns null when the number has no matching
 * contact, READ_CONTACTS isn't granted, or the provider throws. Uses the system's
 * built-in phone-number normalisation, so "+12065550100" and "2065550100" both
 * resolve to the same contact entry.
 *
 * Results are cached in [ContactCaches.names] (see there for the sentinel and
 * invalidation policy); only real query results are cached — a null cursor or an
 * exception leaves the cache untouched so the next call retries. Cache misses
 * perform a synchronous ContentResolver query — call from a background dispatcher.
 */
fun Context.lookupContactName(address: String): String? {
    // An empty address would produce content://com.android.contacts/phone_lookup/ with
    // no segment, which may match every contact on some ROMs. Skip the lookup entirely.
    if (address.isEmpty()) return null
    ContactCaches.names.get(address)?.let { return it.ifEmpty { null } }
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(address)
    )
    return try {
        val cursor = contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        ) ?: return null // provider unavailable — don't cache, retry next call
        val name = cursor.use {
            if (it.moveToFirst())
                it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
            else null
        }
        ContactCaches.names.put(address, name.orEmpty())
        ContactCaches.ensureInvalidationObserver(this)
        name
    } catch (_: Exception) {
        null
    }
}
