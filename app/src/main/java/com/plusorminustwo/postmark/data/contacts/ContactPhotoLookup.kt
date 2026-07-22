package com.plusorminustwo.postmark.data.contacts

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract

/**
 * Reverse-looks-up the contact photo URI for [address] via [ContactsContract.PhoneLookup].
 * Returns null when the number has no matching contact, the contact has no photo,
 * READ_CONTACTS isn't granted, or the provider throws.
 *
 * Mirrors [lookupContactName] exactly — same PhoneLookup normalisation, same
 * cache-only-real-results rule (see [ContactCaches]), so a lookup that fails before
 * permission is granted can't pin a wrong negative for the process lifetime. Cache
 * misses perform a synchronous ContentResolver query — call from a background dispatcher.
 *
 * The returned URI points at the contact's *thumbnail* photo, which is the right size
 * for both a list avatar and a notification large icon.
 */
fun Context.lookupContactPhotoUri(address: String): String? {
    // An empty address would produce content://com.android.contacts/phone_lookup/ with
    // no segment, which may match every contact on some ROMs. Skip the lookup entirely.
    if (address.isEmpty()) return null
    ContactCaches.photoUris.get(address)?.let { return it.ifEmpty { null } }
    val lookupUri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(address)
    )
    return try {
        val cursor = contentResolver.query(
            lookupUri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null, null, null
        ) ?: return null // provider unavailable — don't cache, retry next call
        val resolved = cursor.use {
            if (it.moveToFirst()) {
                Uri.withAppendedPath(
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, it.getLong(0)),
                    ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
                ).toString()
            } else {
                "" // No matching contact — cache the negative
            }
        }
        ContactCaches.photoUris.put(address, resolved)
        ContactCaches.ensureInvalidationObserver(this)
        resolved.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

/**
 * Decodes the contact photo for [address], or null if there is none. The photo URI
 * resolves but the stream can still be empty — a contact row with no photo blob returns
 * a URI that opens to nothing — so a null decode is a normal outcome, not an error.
 *
 * Synchronous IO; call from a background dispatcher.
 */
fun Context.loadContactPhotoBitmap(address: String): Bitmap? {
    val photoUri = lookupContactPhotoUri(address) ?: return null
    return try {
        contentResolver.openInputStream(Uri.parse(photoUri))?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }
}
