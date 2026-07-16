package com.plusorminustwo.postmark.data.contacts

import android.content.Context
import android.database.ContentObserver
import android.provider.ContactsContract
import android.util.LruCache
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped caches over the Contacts provider (name and photo lookups) and the
 * single invalidation path for both. One home so the sentinel and staleness policies
 * cannot drift between the name and photo layers.
 *
 * Conventions both caches share:
 *  - "" is the "looked up, nothing found" sentinel — LruCache rejects null values,
 *    and negative results are the common case worth caching (every unsaved number).
 *  - Failures (SecurityException before READ_CONTACTS is granted, provider returning
 *    a null cursor) are NEVER cached — callers must only put real query results, so
 *    a lookup that fails pre-permission can't pin a wrong negative for the process
 *    lifetime.
 *
 * [ensureInvalidationObserver] registers one ContentObserver on the Contacts
 * authority that evicts both caches on any contact edit, so saving or renaming a
 * contact is reflected at the next lookup instead of after process death. Callers
 * invoke it after a successful query (registration itself requires READ_CONTACTS,
 * so "a query just succeeded" is the cheapest correct gate).
 */
internal object ContactCaches {
    /** address → contact display name ("" = no contact). */
    val names = LruCache<String, String>(512)

    /** address → contact photo URI ("" = no photo). */
    val photoUris = LruCache<String, String>(256)

    private val observerRegistered = AtomicBoolean(false)

    fun ensureInvalidationObserver(context: Context) {
        if (!observerRegistered.compareAndSet(false, true)) return
        try {
            context.applicationContext.contentResolver.registerContentObserver(
                ContactsContract.AUTHORITY_URI,
                /* notifyForDescendants = */ true,
                // null handler → onChange arrives on a binder thread; evictAll is
                // internally synchronized, so no extra dispatch is needed.
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        names.evictAll()
                        photoUris.evictAll()
                    }
                }
            )
        } catch (_: Exception) {
            // Registration failed (e.g. permission revoked between query and here) —
            // allow a later lookup to retry.
            observerRegistered.set(false)
        }
    }
}
