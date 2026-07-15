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
 * Performs a synchronous ContentResolver query — call from a background dispatcher.
 */
fun Context.lookupContactName(address: String): String? {
    // An empty address would produce content://com.android.contacts/phone_lookup/ with
    // no segment, which may match every contact on some ROMs. Skip the lookup entirely.
    if (address.isEmpty()) return null
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(address)
    )
    return try {
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
            else null
        }
    } catch (_: Exception) {
        null
    }
}
