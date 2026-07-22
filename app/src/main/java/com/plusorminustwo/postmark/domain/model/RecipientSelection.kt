package com.plusorminustwo.postmark.domain.model

/**
 * A single chip in the New Conversation multi-recipient strip (GROUP_MESSAGING_SPEC §3).
 * [address] is the raw phone number passed on to thread creation; [displayName] is what
 * the chip and any selected-contact row show (contact name, or a formatted number for a
 * manually-typed entry).
 */
data class RecipientChip(val address: String, val displayName: String)

/**
 * Normalizes a raw phone number for dedupe comparison only — never used for display or
 * persistence. Strips every non-digit character, then drops a leading NANP country code
 * ("1" prefix on an 11-digit number) so "+12065551234", "12065551234", and
 * "(206) 555-1234" all collapse to the same 10-digit key. This is what lets the same
 * person be recognized whether they're picked twice via search or once via search and
 * once by typing their number with different punctuation.
 */
fun normalizeAddressForDedupe(address: String): String {
    val digits = address.filter { it.isDigit() }
    return if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
}

/**
 * Adds [candidate] to [current], deduped by [normalizeAddressForDedupe]. A blank or
 * already-present (by normalized address) candidate leaves [current] unchanged — first
 * pick wins rather than adding a second chip for the same person.
 */
fun addRecipient(current: List<RecipientChip>, candidate: RecipientChip): List<RecipientChip> {
    val key = normalizeAddressForDedupe(candidate.address)
    if (key.isBlank() || current.any { normalizeAddressForDedupe(it.address) == key }) return current
    return current + candidate
}

/** Removes the chip whose address normalizes to the same key as [address], if any. */
fun removeRecipient(current: List<RecipientChip>, address: String): List<RecipientChip> {
    val key = normalizeAddressForDedupe(address)
    return current.filterNot { normalizeAddressForDedupe(it.address) == key }
}
