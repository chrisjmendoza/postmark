package com.plusorminustwo.postmark.data.sync

/**
 * Decision logic for the contact-name refresh pass (docs/TODO.md Tier 2 "Contact
 * integration": "if contact name changes in system Contacts, update Thread.displayName
 * on next sync").
 *
 * Given a thread's currently-stored [storedDisplayName] and the name the system Contacts
 * provider resolves for its address right now ([resolvedName]; null when no contact matches,
 * or READ_CONTACTS is unavailable / the provider throws), returns the display name to
 * persist, or null when nothing should change.
 *
 * Rules:
 *  - Group thread ([isGroup]) → null. A group's displayName is a comma-joined roster owned
 *    by the roster-staleness mechanism (GROUP_MESSAGING_SPEC §4.3); never touched here.
 *  - resolvedName null/blank → null (KEEP the stored name). [Context.lookupContactName]
 *    returns null for BOTH a genuinely-deleted contact and a transient permission/provider
 *    failure, and cannot distinguish them. Reverting to the raw number on null would churn
 *    good names into numbers on every hiccup, so we keep what we have; a real rename
 *    (non-null and different) still updates. Deliberate deviation from Google Messages —
 *    which shows the number again after a contact is deleted — chosen for safety given the
 *    null conflation.
 *  - resolvedName equal to the stored name → null (no write).
 *  - otherwise → resolvedName (the contact was renamed, or a bare number just gained a
 *    contact).
 *
 * Nicknames are deliberately NOT an input: a Postmark nickname overrides displayName at
 * render time (`nickname ?: displayName`), so refreshing displayName underneath a nickname
 * is invisible and harmless. This function neither reads nor writes the nickname column.
 */
internal fun resolveDisplayNameRefresh(
    storedDisplayName: String,
    resolvedName: String?,
    isGroup: Boolean
): String? {
    if (isGroup) return null
    if (resolvedName.isNullOrBlank()) return null
    return if (resolvedName != storedDisplayName) resolvedName else null
}
