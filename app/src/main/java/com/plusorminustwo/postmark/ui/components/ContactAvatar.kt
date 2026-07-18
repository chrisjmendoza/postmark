package com.plusorminustwo.postmark.ui.components

import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.plusorminustwo.postmark.data.contacts.ContactCaches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows a circular contact photo if one exists in the system Contacts provider,
 * falling back to [LetterAvatar] when the number is unknown or has no photo.
 *
 * The lookup runs once per unique [address] per process on [Dispatchers.IO] and
 * is cached in [ContactCaches.photoUris] (shared sentinel/invalidation policy
 * lives there) — no DB write, no migration. Rows scrolling back into composition
 * never re-hit the Contacts provider or flash letter→photo.
 *
 * @param address   Raw phone number used for [ContactsContract.PhoneLookup].
 * @param name      Display name; forwarded to [LetterAvatar] as the letter source.
 * @param colorSeed Color seed for [LetterAvatar] (usually the raw address for stability).
 * @param size      Diameter of the avatar circle.
 * @param overrideColor When non-null, used instead of the hash-derived avatar color —
 *                      the per-contact accent color, when the caller has a Thread in hand.
 */
@Composable
fun ContactAvatar(
    address: String,
    name: String,
    colorSeed: String = address,
    size: Dp = 44.dp,
    overrideColor: Color? = null
) {
    // Null = loading/unknown, empty string = no photo found, non-empty = photo URI.
    // Seeded synchronously from the cache so a previously resolved address renders
    // its final state on first composition — zero flash on re-scroll.
    var photoUri by remember(address) { mutableStateOf(ContactCaches.photoUris.get(address)) }
    val context = LocalContext.current

    // Cache miss only: resolve the photo URI in the background, then cache it.
    // Failures (SecurityException before READ_CONTACTS is granted, null cursor) are
    // NOT cached — photoUri stays null and the next composition retries, so a photo
    // denied during onboarding isn't pinned to a letter avatar for the process life.
    LaunchedEffect(address) {
        if (address.isBlank() || photoUri != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            val resolved: String? = try {
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(ContactsContract.PhoneLookup._ID),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val contactId = cursor.getLong(0)
                        // Build the standard contact photo URI. Coil will call
                        // openAssetFileDescriptor() which the Contacts provider
                        // handles efficiently, reading from the photo store.
                        ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI, contactId
                        ).let { baseUri ->
                            Uri.withAppendedPath(
                                baseUri,
                                ContactsContract.Contacts.Photo.CONTENT_DIRECTORY
                            ).toString()
                        }
                    } else {
                        "" // No matching contact — use LetterAvatar
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (resolved != null) {
                ContactCaches.photoUris.put(address, resolved)
                ContactCaches.ensureInvalidationObserver(context)
                photoUri = resolved
            }
        }
    }

    when {
        // Photo found — load with Coil, circular clip, fall back to LetterAvatar on error
        photoUri?.isNotEmpty() == true -> {
            SubcomposeAsyncImage(
                model = photoUri,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                error = {
                    // Photo URI resolved but image failed to load (e.g. contact deleted)
                    LetterAvatar(name = name, colorSeed = colorSeed, size = size, overrideColor = overrideColor)
                },
                loading = {
                    // Show a plain circle in the avatar background color while loading
                    Box(
                        modifier = Modifier
                            .size(size)
                            .background(overrideColor ?: avatarColor(colorSeed), CircleShape)
                    )
                }
            )
        }
        // Still resolving OR no photo — show LetterAvatar (no flash, same visual)
        else -> LetterAvatar(name = name, colorSeed = colorSeed, size = size, overrideColor = overrideColor)
    }
}
