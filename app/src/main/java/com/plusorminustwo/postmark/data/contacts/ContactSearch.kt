package com.plusorminustwo.postmark.data.contacts

import android.content.Context
import android.provider.ContactsContract
import com.plusorminustwo.postmark.domain.model.normalizeAddressForDedupe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One phone-number entry returned by [Context.searchContacts]: a contact's display name, the
 * raw number to act on, and — when the OS classifies the number (Mobile/Home/Work/...) or the
 * user gave it a custom label — [label], the human-readable number-type text. A contact with
 * more than one number therefore surfaces as distinguishable rows instead of duplicate-looking
 * ones with only raw digits telling them apart.
 */
data class ContactResult(
    val displayName: String,
    val address: String,
    val label: String? = null
)

/**
 * Combines a contact's number-type label with an already-formatted phone number into the
 * contact-picker row's supporting text (e.g. "Mobile · (555) 123-4567"). Falls back to the
 * number alone when there's no type label (a ROM that doesn't classify the number, or a
 * manually-typed entry with no contact behind it).
 */
fun formatContactSupportingText(typeLabel: String?, formattedNumber: String): String =
    if (typeLabel.isNullOrBlank()) formattedNumber else "$typeLabel · $formattedNumber"

/**
 * Queries [ContactsContract.CommonDataKinds.Phone] for entries whose display name or number
 * contains [query]. Returns at most [limit] rows, deduped by [normalizeAddressForDedupe] —
 * not the raw whitespace-stripped string — so the same number stored in two formats (e.g. a
 * merged contact with "+12065551234" and "(206) 555-1234" both on file) collapses to one row,
 * and sorted by display name.
 *
 * Shared by [com.plusorminustwo.postmark.ui.conversations.NewConversationViewModel] and
 * [com.plusorminustwo.postmark.ui.forward.ForwardPickerViewModel] (previously a verbatim copy
 * in both). Each row's [ContactResult.label] resolves the OS's TYPE/LABEL columns via
 * [ContactsContract.CommonDataKinds.Phone.getTypeLabel], so a contact with two numbers (e.g.
 * Mobile + Home) is distinguishable in the picker, not just by raw digits.
 */
suspend fun Context.searchContacts(query: String, limit: Int = 20): List<ContactResult> =
    withContext(Dispatchers.IO) {
        val results = mutableListOf<ContactResult>()
        val seen = mutableSetOf<String>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$query%", "%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx  = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx   = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx  = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(nameIdx) ?: continue
                    // Strip whitespace so the raw address stored on the result is clean;
                    // de-dup itself keys on the fully normalized number (see [seen] below).
                    val num = cursor.getString(numIdx)?.replace("\\s".toRegex(), "") ?: continue
                    if (!seen.add(normalizeAddressForDedupe(num))) continue
                    val type = cursor.getInt(typeIdx)
                    val customLabel = cursor.getString(labelIdx)
                    val typeLabel = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(resources, type, customLabel)
                        ?.toString()
                    results += ContactResult(name, num, typeLabel)
                }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS not granted — return empty list; screen handles gracefully.
        }
        results
    }
