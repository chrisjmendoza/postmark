package com.plusorminustwo.postmark.service.sms

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide record of which thread (if any) the user is currently looking at in
 * [com.plusorminustwo.postmark.ui.thread.ThreadScreen].
 *
 * Used by the two incoming-message notification entry points ([SmsReceiver] for SMS and
 * [com.plusorminustwo.postmark.data.sync.SmsSyncHandler] for MMS) to suppress the banner
 * for a message that lands in the exact conversation already on screen — the standard
 * messaging-app behaviour. The message still syncs and persists normally; only the
 * notification is skipped.
 *
 * The stored id is the telephony/Room thread id (they share one id space: every write to
 * [com.plusorminustwo.postmark.domain.model.Thread].id uses `Telephony.Sms.THREAD_ID`, and
 * SmsReceiver resolves the same value via `Telephony.Threads.getOrCreateThreadId`).
 *
 * [activeThreadId] is [Volatile] because it is written from the main thread (ThreadScreen's
 * lifecycle callbacks) and read from background IO threads (the notification code paths).
 *
 * Process death resets the singleton to null, so the default after a restart is to notify.
 */
@Singleton
class ActiveThreadTracker @Inject constructor() {

    @Volatile
    var activeThreadId: Long? = null
        private set

    /** Records [id] as the on-screen thread (ThreadScreen ON_RESUME). */
    fun setActive(id: Long) {
        activeThreadId = id
    }

    /**
     * Clears the active thread on ThreadScreen ON_PAUSE — but only if [id] still matches
     * the stored value. Two ThreadScreen instances can overlap during a
     * pause-old/resume-new navigation, and their lifecycle events can interleave; guarding
     * on equality stops a departing screen's ON_PAUSE from wiping the arriving screen's
     * newer registration.
     */
    fun clearActive(id: Long) {
        if (activeThreadId == id) activeThreadId = null
    }
}
