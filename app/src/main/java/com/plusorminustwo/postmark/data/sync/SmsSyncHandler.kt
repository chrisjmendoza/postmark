package com.plusorminustwo.postmark.data.sync

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.plusorminustwo.postmark.BuildConfig
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_FAILED
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_NONE
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_SENT
import com.plusorminustwo.postmark.data.preferences.PrivacyModeRepository
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import com.plusorminustwo.postmark.domain.model.MMS_ID_OFFSET
import com.plusorminustwo.postmark.service.sms.ActiveThreadTracker
import com.plusorminustwo.postmark.service.sms.IncomingNotifier
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import com.plusorminustwo.postmark.domain.model.previewText
import com.plusorminustwo.postmark.domain.model.Reaction
import com.plusorminustwo.postmark.domain.model.SELF_ADDRESS
import com.plusorminustwo.postmark.data.reaction.ReactionFallbackParser
import com.plusorminustwo.postmark.util.isDefaultSmsApp
import com.plusorminustwo.postmark.data.reaction.findBareEmojiReactionTarget
import com.plusorminustwo.postmark.data.reaction.isBareEmojiReactionCandidate
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles incremental SMS and MMS sync whenever the system content provider changes.
 *
 * Architecture — conflated channels + mutexes:
 *  - Each transport (SMS and MMS) has its own [Channel.CONFLATED] work channel.
 *    If a sync is already running when a new content-observer notification arrives,
 *    at most one follow-up run is queued; additional notifications are dropped.
 *    This prevents O(N) coroutines piling up during burst MMS delivery.
 *  - A per-transport [Mutex] ensures only one sync path runs at a time, and that
 *    [triggerCatchUp] cannot race with the channel consumer on the same path.
 *
 * Call [onSmsContentChanged] / [onMmsContentChanged] from [SmsContentObserver].
 * Call [triggerCatchUp] after [SmsHistoryImportWorker] completes to pick up any
 * messages that arrived during the bulk import.
 */
@Singleton
class SmsSyncHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val threadRepository: ThreadRepository,
    private val messageRepository: MessageRepository,
    private val reactionParser: ReactionFallbackParser,
    private val privacyModeRepository: PrivacyModeRepository,
    private val incomingNotifier: IncomingNotifier,
    private val activeThreadTracker: ActiveThreadTracker,
    private val syncLogger: SyncLogger,
    private val emptyMmsBodyRepair: EmptyMmsBodyRepair,
    private val reactionResolver: ReactionResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /*
     * CONFLATED channel: if a sync is running when a new notification arrives,
     * at most one follow-up run is queued. Additional notifications while that
     * queued run waits are silently dropped (they're all equivalent — "something
     * changed, go look"). This prevents O(N) queued coroutines during burst
     * delivery (e.g. the content observer firing 50 times during MMS import).
     */
    private val smsWorkChannel = Channel<Unit>(Channel.CONFLATED)
    private val mmsWorkChannel = Channel<Unit>(Channel.CONFLATED)

    /* Mutex ensures only one SMS sync and one MMS sync run at a time.
     * triggerCatchUp() and the channel consumer both contend on the same lock,
     * so they can never run the same sync path concurrently. */
    private val smsMutex = Mutex()
    private val mmsMutex = Mutex()

    init {
        /* Long-lived coroutines consume channel signals one at a time. Each iteration
         * is individually guarded: an uncaught exception here would otherwise end the
         * for-loop and permanently stop incremental sync for the process lifetime —
         * the worst possible silent failure for an SMS app. CancellationException is
         * rethrown so scope cancellation still works. */
        scope.launch {
            for (unit in smsWorkChannel) {
                try {
                    smsMutex.withLock { syncLatestSms() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    syncLogger.logError(TAG, "SMS sync pass failed — consumer loop continues", e)
                }
            }
        }
        scope.launch {
            for (unit in mmsWorkChannel) {
                try {
                    mmsMutex.withLock { syncLatestMms() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    syncLogger.logError(TAG, "MMS sync pass failed — consumer loop continues", e)
                }
            }
        }
        // Startup marker — visible in end-of-day log review as a process restart boundary.
        syncLogger.log(TAG, "SmsSyncHandler initialized (process start)")
    }

    private fun debugLog(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }

    companion object {
        private const val TAG = "SmsSyncHandler"
        // Must match the key used by SmsHistoryImportWorker.
        private const val PREFS_NAME = "postmark_prefs"
        private const val KEY_FIRST_SYNC_DONE = "first_sync_completed"
        // One-shot flag for the roster repair pass (GROUP_MESSAGING_SPEC §1.4).
        private const val KEY_ROSTER_REPAIR_DONE = "roster_repair_v1_done"
        // One-shot flag for the non-displayable-PDU Room cleanup (GROUP_MESSAGING_SPEC §4.2).
        private const val KEY_MTYPE_CLEANUP_DONE = "mtype_cleanup_v1_done"
        // One-shot flag for the historical reaction re-resolution pass. Bumped whenever a new
        // resolution strategy lands so resolveAll() runs once more over existing installs.
        // v1: original import-time pass. v2: truncated-quote matching (ellipsized long URLs).
        // v3: bare-emoji MMS reactions (a lone-emoji media reaction archived without any
        // quoted structure) — heals the owner's existing stray ❤️ onto the cat photo.
        // v4: removal-PREFIX shape ("Removed <emoji> from "<quote>"") — heals the owner's
        // existing stray "Removed 👍 from …" bubble and clears the stale pill it never removed.
        private const val KEY_REACTION_REPROCESS_DONE = "reaction_reprocess_v4_done"
        /* How recent a synced incoming MMS must be to earn a notification banner
         * (see notifyIncomingMms). Generous enough for slow MMS downloads and
         * carrier/device clock skew; far below any app-open backlog's age. */
        private const val NOTIFY_FRESHNESS_WINDOW_MS = 15 * 60_000L
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Signal from SmsReceiver or SmsContentObserver — coalesced into the channel. */
    fun onSmsContentChanged(@Suppress("UNUSED_PARAMETER") uri: Uri) {
        smsWorkChannel.trySend(Unit)
    }

    /** Signal from MmsReceiver or SmsContentObserver — coalesced into the channel. */
    fun onMmsContentChanged(@Suppress("UNUSED_PARAMETER") uri: Uri) {
        mmsWorkChannel.trySend(Unit)
    }

    /**
     * Awaitable catch-up pass, called by [SmsHistoryImportWorker] after the full
     * historical sync completes to pick up any messages that arrived during the
     * sync window. Waits for any in-flight channel-triggered sync to finish first
     * (via the same Mutex), then runs on [Dispatchers.IO] regardless of the
     * caller's context — the sync bodies issue cross-process ContentResolver
     * queries, and ConversationsViewModel's 60-second poll used to run them on
     * Main.immediate, stalling frames on every idle pass.
     */
    suspend fun triggerCatchUp() = withContext(Dispatchers.IO) {
        repairRostersOnce()
        cleanupNonDisplayableMmsOnce()
        smsMutex.withLock { syncLatestSms() }
        mmsMutex.withLock { syncLatestMms() }
        repairEmptyMmsBodies()
        reprocessReactionsOnce()
        refreshOneToOneDisplayNames()
    }

    /*
     * Self-healing for MMS rows that imported with no readable content — see
     * [EmptyMmsBodyRepair]. Runs after the MMS sync pass so just-arrived rows are
     * candidates too; under mmsMutex so a channel-triggered sync can't interleave.
     * Runs every catch-up (not one-shot): the query returns nothing when the DB is
     * healthy, so a clean pass costs a single bounded SELECT.
     */
    private suspend fun repairEmptyMmsBodies() {
        try {
            val result = mmsMutex.withLock {
                emptyMmsBodyRepair.repair(
                    rereadParts = { rawId -> getMmsBodyIncrementalOrNull(rawId) },
                    log = { syncLogger.log("EmptyMmsRepair", it) }
                )
            }
            if (result.repaired > 0 || result.stillEmpty > 0) {
                syncLogger.log(TAG, "empty-MMS repair: repaired=${result.repaired} stillEmpty=${result.stillEmpty}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogger.logError(TAG, "empty-MMS repair failed — will retry on next catch-up", e)
        }
    }

    /*
     * One-shot full re-resolution of stored reaction fallbacks (same flag pattern as
     * [repairRostersOnce]): fallbacks that failed to match under the pre-truncation
     * matching strategies were inserted as visible bubbles and nothing ever retried
     * them. Runs ReactionResolver over every thread once; the flag is set only on a
     * clean pass so a transient failure retries on the next catch-up. Room only.
     */
    private suspend fun reprocessReactionsOnce() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REACTION_REPROCESS_DONE, false)) return
        try {
            val result = reactionResolver.resolveAll()
            syncLogger.log(TAG, "reaction reprocess v4: inserted=${result.inserted} removed=${result.removed}")
            prefs.edit().putBoolean(KEY_REACTION_REPROCESS_DONE, true).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogger.logError(TAG, "reaction reprocess failed — will retry on next catch-up", e)
        }
    }

    /*
     * Contact-name refresh (docs/TODO.md Tier 2 "Contact integration": "if contact name
     * changes in system Contacts, update Thread.displayName on next sync"). displayName is
     * resolved ONCE, at thread-creation time (ensureThread / the worker's cursor passes),
     * so a contact renamed afterwards leaves the conversation list and thread header showing
     * the old name — SmsReceiver already works around this by live-resolving names for
     * notifications; this fixes the staleness at the source in Room.
     *
     * Re-resolves each 1:1 thread's contact name on every catch-up pass and issues a
     * targeted displayName UPDATE only when it actually changed. Group threads are skipped
     * inside [resolveDisplayNameRefresh] (their comma-joined roster name is owned by the
     * roster-staleness mechanism, GROUP_MESSAGING_SPEC §4.3); nicknames are never read or
     * written (they override displayName at render time). The UPDATE column is displayName
     * alone — user settings (pin/mute/notifications/colors) are untouched.
     *
     * Cost: [lookupContactName] is served from ContactCaches (process-wide LRU). In steady
     * state — no contact edits since the last pass — every lookup is an O(1) cache hit with
     * no ContentResolver I/O, and since nothing changed there are zero DB writes; the pass
     * is a cheap in-memory scan. The one costly pass is the first after any contact edit:
     * the caches' ContentObserver evicts on edit, so that pass re-queries PhoneLookup per
     * thread and re-warms the cache. That I/O is unavoidable (a rename can't be detected
     * without a lookup), bounded to once per edit burst, and runs on Dispatchers.IO.
     */
    private suspend fun refreshOneToOneDisplayNames() {
        try {
            var updated = 0
            for (thread in threadRepository.getAll()) {
                val isGroup = thread.participants.size > 1
                val resolved = context.lookupContactName(thread.address)
                val newName = resolveDisplayNameRefresh(thread.displayName, resolved, isGroup) ?: continue
                threadRepository.updateDisplayName(thread.id, newName)
                updated++
            }
            if (updated > 0) syncLogger.log(TAG, "contact-name refresh: updated $updated 1:1 thread display name(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogger.logError(TAG, "contact-name refresh failed — will retry on next catch-up", e)
        }
    }

    /*
     * One-shot repair for Room threads whose roster was captured before the canonical
     * fix (GROUP_MESSAGING_SPEC §1.4): the old per-PDU scan included the device's own
     * number, so genuine 1:1 MMS threads were persisted as 2-person "groups" (self in
     * the title, group-reply banner). Re-derives the canonical roster for every stored
     * group thread and either demotes it back to 1:1 or replaces its roster.
     *
     * Room writes ONLY — never touches the telephony provider (CLAUDE.md CRITICAL).
     * Gated by a SharedPreferences flag; the flag is set only on a clean pass, so a
     * transient provider failure simply retries on the next catch-up.
     */
    private suspend fun repairRostersOnce() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ROSTER_REPAIR_DONE, false)) return
        try {
            val threads = threadRepository.getThreadsWithParticipants()
            var demoted = 0; var replaced = 0; var kept = 0; var skipped = 0
            for (thread in threads) {
                val current = thread.participants
                if (current.size <= 1) continue
                // null → canonical query failed; skip rather than corrupt a real group.
                val canonical = context.queryCanonicalParticipants(thread.id)
                if (canonical == null) { skipped++; continue }
                when (repairAction(canonical, current)) {
                    RepairAction.KEEP -> kept++
                    RepairAction.DEMOTE -> {
                        val name = context.lookupContactName(thread.address) ?: thread.address.ifEmpty { "Unknown" }
                        threadRepository.updateRoster(thread.id, emptyList(), name)
                        demoted++
                    }
                    RepairAction.REPLACE -> {
                        val name = canonical.joinToString(", ") { context.lookupContactName(it) ?: it }
                        threadRepository.updateRoster(thread.id, canonical, name)
                        replaced++
                    }
                }
            }
            syncLogger.log(TAG, "roster repair v1: demoted=$demoted replaced=$replaced kept=$kept skipped=$skipped of ${threads.size} group thread(s)")
            prefs.edit().putBoolean(KEY_ROSTER_REPAIR_DONE, true).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogger.logError(TAG, "roster repair failed — will retry on next catch-up", e)
        }
    }

    /*
     * One-shot cleanup of already-imported artifact rows (GROUP_MESSAGING_SPEC §4.2):
     * before the m_type filter landed, non-displayable PDUs (M-Notification.ind,
     * delivery/read reports) were imported as empty "Unknown" bubbles. Finds those rows
     * by querying content://mms (READ only — never deleted) and removes the
     * corresponding Room rows only.
     *
     * Same one-shot pattern as [repairRostersOnce]: gated by a SharedPreferences flag
     * set only on a clean pass, so a transient provider failure simply retries on the
     * next catch-up. Never touches the telephony provider (CLAUDE.md CRITICAL).
     */
    private suspend fun cleanupNonDisplayableMmsOnce() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MTYPE_CLEANUP_DONE, false)) return
        try {
            val badRawIds = mutableListOf<Long>()
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("_id"),
                "m_type NOT IN (?, ?) AND m_type IS NOT NULL",
                arrayOf(PDU_MTYPE_SEND_REQ.toString(), PDU_MTYPE_RETRIEVE_CONF.toString()),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("_id")
                while (cursor.moveToNext()) badRawIds += cursor.getLong(idIdx)
            }
            if (badRawIds.isNotEmpty()) {
                messageRepository.deleteByIds(badRawIds.map { MMS_ID_OFFSET + it })
            }
            syncLogger.log(TAG, "mtype cleanup v1: deleted ${badRawIds.size} non-displayable MMS row(s) from Room")
            prefs.edit().putBoolean(KEY_MTYPE_CLEANUP_DONE, true).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogger.logError(TAG, "mtype cleanup failed — will retry on next catch-up", e)
        }
    }
    /*
     * Fetches every SMS row whose _id is greater than the highest id already
     * stored in Room. This correctly handles burst delivery (multiple messages
     * arriving in a single ContentObserver notification) and is idempotent
     * (re-running when there are no new messages returns 0 rows instantly).
     * Bails when DB is empty (initial sync not yet done) — the worker owns that pass.
     */
    private suspend fun syncLatestSms() {
        // Nothing in DB yet → full sync not done; do not interfere with worker.
        val maxKnownId = messageRepository.getMaxId() ?: 0L
        if (maxKnownId <= 0L) return

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE
        )

        // Fetch every SMS row we haven't seen yet, oldest-first so thread
        // metadata (lastMessageAt/Preview) ends up reflecting the true latest.
        val selectionArgs = arrayOf(maxKnownId.toString())
        val sortOrder = "${Telephony.Sms._ID} ASC"
        val primaryCursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms._ID} > ?",
            selectionArgs,
            sortOrder
        )

        /* After some Android system updates (observed June 2026), content://sms excludes sent
         * messages from aggregate queries even when READ_SMS is granted and the cursor is
         * non-null. Always supplement with content://sms/sent so sent messages are never
         * silently dropped; duplicate _ids across both cursors are removed below. */
        val cursors = if (primaryCursor != null) {
            val sentCursor = context.contentResolver.query(
                Uri.parse("content://sms/sent"),
                projection,
                "${Telephony.Sms._ID} > ?",
                selectionArgs,
                sortOrder
            )
            listOfNotNull(primaryCursor, sentCursor)
        } else {
            Log.w(TAG, "syncLatestSms: primary cursor null after id=$maxKnownId — trying mailbox fallback URIs")
            syncLogger.log("IncrementalSms", "primary cursor null — trying mailbox fallback URIs after id=$maxKnownId")
            listOf("content://sms/inbox", "content://sms/sent").mapNotNull { uriStr ->
                context.contentResolver.query(
                    Uri.parse(uriStr),
                    projection,
                    "${Telephony.Sms._ID} > ?",
                    selectionArgs,
                    sortOrder
                )
            }
        }

        if (cursors.isEmpty()) return

        val newMessages = mutableListOf<Message>()
        val seenIds = mutableSetOf<Long>()
        val ensuredThreadIds = mutableSetOf<Long>()

        for (cursor in cursors) cursor.use {
            val idIdx       = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx   = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx  = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx     = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx     = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val dateSentIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            val typeIdx     = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                val id       = it.getLong(idIdx)
                if (!seenIds.add(id)) continue
                val threadId = it.getLong(threadIdx)
                /* Null address is valid for WAP push, carrier service, and some OEM ROMs.
                 * Preserve the row with an empty fallback — never skip based on missing address. */
                val address  = it.getString(addressIdx) ?: ""
                val body     = it.getString(bodyIdx) ?: ""
                val date     = it.getLong(dateIdx)
                // DATE_SENT is 0 when the provider never recorded a send time — store NULL, not 0.
                val dateSent = it.getLong(dateSentIdx).takeIf { v -> v > 0L }
                val type     = it.getInt(typeIdx)
                // Drafts (3), outbox (4), and failed sends (5) are outgoing; only inbox (1) is received.
                val isSent   = type != Telephony.Sms.MESSAGE_TYPE_INBOX

                if (threadId !in ensuredThreadIds) {
                    ensureThread(threadId, address, date)
                    ensuredThreadIds += threadId
                }
                newMessages += Message(
                    id = id,
                    threadId = threadId,
                    address = address,
                    body = body,
                    timestamp = date,
                    isSent = isSent,
                    type = type,
                    /* Sent messages arriving via incremental sync are waiting for the
                     * SmsSentDeliveryReceiver callback; start them as PENDING so the UI
                     * shows the clock icon. Received messages have no delivery tracking. */
                    deliveryStatus = if (isSent) DELIVERY_STATUS_PENDING else DELIVERY_STATUS_NONE,
                    // Incoming messages start as unread; sent messages are always read.
                    isRead = isSent,
                    sentAt = dateSent
                )
            }
        }

        if (newMessages.isEmpty()) return

        debugLog("syncLatestSms: ${newMessages.size} new message(s)")
        syncLogger.log("IncrementalSms", "${newMessages.size} new message(s) after id=$maxKnownId")

        /* Separate reaction-fallback messages from real messages before persisting.
         * Reaction fallbacks (Android: "👍 to \"text\"", Apple: "Liked 'text'") must be
         * resolved to Reaction entities and never stored as visible message bubbles. */
        val (reactionMsgs, normalMessages) = newMessages.partition { reactionParser.isReactionFallback(it.body) }

        if (normalMessages.isNotEmpty()) {
            messageRepository.insertAll(normalMessages)
            normalMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
                val latest = msgs.last()
                // isMms = false: only clear optimistic SMS rows. A pending optimistic MMS
                // row must survive until syncLatestMms() imports its real row — deleting
                // it here made a just-sent image vanish when an SMS followed it quickly.
                messageRepository.deleteOptimisticMessages(threadId, isMms = false)
                threadRepository.updateLastMessageAt(threadId, latest.timestamp)
                threadRepository.updateLastMessagePreview(threadId, latest.previewText)
            }
        }
        // Clean up optimistic SMS rows for threads that only received reaction fallbacks.
        val normalThreadIds = normalMessages.map { it.threadId }.toSet()
        reactionMsgs.map { it.threadId }.distinct()
            .filter { it !in normalThreadIds }
            .forEach { messageRepository.deleteOptimisticMessages(it, isMms = false) }

        /* Resolve reaction fallback messages into Reaction entities (deduped).
         * If the original message cannot be found within 100 messages, insert the
         * fallback as a normal visible bubble rather than silently dropping it. */
        val unresolvedReactionMsgs = mutableListOf<Message>()
        reactionMsgs.forEach { message ->
            val parsed = reactionParser.parse(message.body) ?: return@forEach
            val threadMessages = messageRepository.getByThread(message.threadId)
            val senderAddress = if (message.isSent) SELF_ADDRESS else message.address
            val reaction = reactionParser.processIncomingMessage(message, threadMessages, senderAddress)

            if (reaction != null) {
                if (parsed.isRemoval) {
                    messageRepository.deleteReaction(reaction.messageId, reaction.senderAddress, reaction.emoji)
                } else if (!messageRepository.reactionExists(reaction.messageId, reaction.senderAddress, reaction.emoji)) {
                    messageRepository.insertReaction(reaction)
                }
            } else {
                // Original not found — preserve as a normal visible bubble (only for additions).
                if (!parsed.isRemoval) {
                    unresolvedReactionMsgs += message
                }
            }
        }
        if (unresolvedReactionMsgs.isNotEmpty()) {
            messageRepository.insertAll(unresolvedReactionMsgs)
        }
    }

    /*
     * Mirrors syncLatestSms() but for content://mms. Bound is derived from the
     * highest stored MMS id (which is offset by MMS_ID_OFFSET) minus the offset,
     * giving the raw MMS _id to use in the WHERE clause.
     */
    private suspend fun syncLatestMms() {
        val maxStoredId = messageRepository.getMaxMmsId() ?: 0L
        if (maxStoredId <= 0L) {
            /* No MMS in Room yet. Only proceed if the full historical sync has
             * already completed — otherwise we'd run "_id > 0" concurrently with
             * the worker, fetching all historical MMS and racing on thread metadata.
             * Incoming MMS will be captured by the worker's catch-up pass at end of run,
             * or by the next incremental sync after the first MMS batch is flushed. */
            val firstSyncDone = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FIRST_SYNC_DONE, false)
            if (!firstSyncDone) {
                debugLog("syncLatestMms: no MMS in Room and first sync not complete — deferring to worker")
                syncLogger.log("IncrementalMms", "deferred — first sync not yet complete, worker owns MMS import")
                return
            }
        }
        // coerceAtLeast(0) prevents a negative bound when no MMS has been stored yet;
        // "_id > 0" correctly returns all rows because MMS IDs start at 1.
        val maxRawId = (maxStoredId - MMS_ID_OFFSET).coerceAtLeast(0L)
        debugLog("syncLatestMms: maxStoredId=$maxStoredId  maxRawId=$maxRawId")

        val mmsIncProjection = arrayOf("_id", "thread_id", "date", "date_sent", "msg_box", "m_type")
        // NOT IN (3, 5): exclude drafts and failed sends. Every other msg_box value
        // (inbox=1, sent=2, outbox=4 for RCS) represents a real message worth importing.
        // A single aggregate query is sufficient — no supplement cursors needed.
        val newMessages      = mutableListOf<Message>()
        val ensuredThreadIds = mutableSetOf<Long>()

        val mmsCursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            mmsIncProjection,
            "_id > ? AND msg_box NOT IN (3, 5)",
            arrayOf(maxRawId.toString()),
            "_id ASC"
        )
        if (mmsCursor != null) {
            mmsCursor.use { extractMmsMessages(it, newMessages, ensuredThreadIds) }
        } else {
            // Samsung OneUI — aggregate content://mms may return a null cursor; try per-mailbox URIs.
            Log.w(TAG, "syncLatestMms: primary cursor null — trying Samsung mailbox fallback URIs")
            syncLogger.log("IncrementalMms", "primary cursor null — trying mailbox URIs after rawId=$maxRawId")
            val seenRawIds = mutableSetOf<Long>()
            for (uriStr in listOf("content://mms/inbox", "content://mms/sent", "content://mms/outbox")) {
                context.contentResolver.query(
                    Uri.parse(uriStr), mmsIncProjection,
                    "_id > ?", arrayOf(maxRawId.toString()), "_id ASC"
                )?.use { extractMmsMessages(it, newMessages, ensuredThreadIds, seenRawIds) }
            }
        }

        if (newMessages.isEmpty()) return

        debugLog("syncLatestMms: ${newMessages.size} new MMS message(s)")
        val sentCount     = newMessages.count { it.isSent }
        val receivedCount = newMessages.count { !it.isSent }
        syncLogger.log("IncrementalMms", "${newMessages.size} new MMS after rawId=$maxRawId (sent=$sentCount received=$receivedCount)")

        /* Separate reaction-fallback MMS messages from real messages before persisting.
         * Reaction fallbacks (Android: "👍 to \"text\"", Apple: "Liked 'text'") must be
         * resolved to Reaction entities and never stored as visible message bubbles.
         *
         * Bare-emoji reaction candidates — a lone-emoji MMS archived from an RCS reaction to
         * media in a 1:1 thread (BareEmojiReaction.kt) — join the same partition: a resolved
         * one is never inserted as a bubble, and an unresolved one falls through to a normal
         * bubble below exactly like an unmatched quoted fallback. The candidate test needs the
         * thread's participant count, which isn't on Message, so it's precomputed here (the
         * thread was already ensured in extractMmsMessages, so getById is a cheap Room read). */
        val bareEmojiCandidateIds = mutableSetOf<Long>()
        for (m in newMessages) {
            if (reactionParser.isReactionFallback(m.body)) continue
            val participantCount = threadRepository.getById(m.threadId)?.participants?.size ?: 0
            if (isBareEmojiReactionCandidate(m, participantCount)) bareEmojiCandidateIds += m.id
        }
        val (reactionMsgs, normalMessages) = newMessages.partition {
            reactionParser.isReactionFallback(it.body) || it.id in bareEmojiCandidateIds
        }

        if (normalMessages.isNotEmpty()) {
            messageRepository.insertAll(normalMessages)
        }

        normalMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
            val latest = msgs.last()

            /*
             * Match each just-imported real SENT row to at most one optimistic temp row, then
             * hand off status + attachments to it and delete that temp row. persistSentMms
             * (MmsSentReceiver) wrote the provider row with the optimistic row's own send time
             * and body, so the match is exact; pickOptimisticMatch's window only tolerates
             * second-truncation and provider clock oddities. Rows that don't plausibly
             * correspond are left untouched — the old code grafted the newest optimistic row's
             * media onto the newest real row unconditionally and then blanket-deleted EVERY
             * optimistic MMS row in the thread, which let an unrelated RCS-archival text absorb
             * a memo's attachments and destroyed the correctly-timed optimistic bubble.
             */
            val realSent = msgs.filter { it.isSent }.sortedBy { it.timestamp }
            if (realSent.isNotEmpty()) {
                val candidates = messageRepository.getOptimisticSentMms(threadId)
                val consumed = mutableSetOf<Long>()
                for (real in realSent) {
                    val matchId = pickOptimisticMatch(
                        real.body, real.timestamp,
                        candidates.filter { it.id !in consumed }
                            .map { OptimisticCandidate(it.id, it.body, it.timestamp) }
                    ) ?: continue
                    consumed += matchId
                    val opt = candidates.first { it.id == matchId }

                    /*
                     * Transfer delivery status. We transfer PENDING too (not just SENT/FAILED)
                     * so the real row keeps a delivery indicator during the window between sync
                     * replacing the optimistic row and MmsSentReceiver posting the final result.
                     * Race A: MmsSentReceiver already marked the temp row FAILED/SENT → transfer
                     * that terminal status. Race B: it hasn't fired yet → transfer PENDING so the
                     * UI keeps the clock indicator instead of a blank.
                     */
                    if (opt.deliveryStatus != DELIVERY_STATUS_NONE) {
                        messageRepository.updateDeliveryStatus(real.id, opt.deliveryStatus)
                        val statusName = when (opt.deliveryStatus) {
                            DELIVERY_STATUS_SENT    -> "SENT"
                            DELIVERY_STATUS_FAILED  -> "FAILED"
                            DELIVERY_STATUS_PENDING -> "PENDING"
                            else                    -> "status=${opt.deliveryStatus}"
                        }
                        syncLogger.log("IncrementalMms", "transferred $statusName status to real row id=${real.id} threadId=$threadId")
                    }

                    /*
                     * Transfer the attachments from the locally-cached compressed media to the
                     * real row. Samsung's content://mms/part/ data for SENT rows is typically
                     * empty, so we use our own filesDir cache (mms_attach_<tempId>*.bin, written
                     * by MmsManagerWrapper BEFORE dispatch) to keep the media visible after the
                     * real row replaces the optimistic one. Falls back per-attachment to the URI
                     * already stored on the optimistic row.
                     */
                    val optId = opt.id
                    val optAttachments = opt.attachments
                    if (optAttachments.isNotEmpty()) {
                        val transferred = optAttachments.mapIndexed { index, att ->
                            val cacheFile = MmsManagerWrapper.attachmentCacheFile(context, optId, index)
                            if (cacheFile.exists()) {
                                try {
                                    // Build a FileProvider URI so Coil can load it within the app process.
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", cacheFile
                                    ).toString()
                                    MessageAttachment(uri, att.mimeType)
                                } catch (_: Exception) {
                                    att // FileProvider lookup failed — keep the DB-stored URI.
                                }
                            } else {
                                att // Cache file not found — keep whatever URI is stored on the row.
                            }
                        }
                        messageRepository.updateAttachments(real.id, transferred)
                        syncLogger.log("IncrementalMms", "transferred ${transferred.size} attachment(s) to real row id=${real.id}")
                    }

                    // Targeted delete of the matched temp row — replaces the old blanket
                    // deleteOptimisticMessages(threadId, isMms = true). Unmatched optimistic
                    // rows (e.g. a send whose provider persist failed) survive by design: a
                    // correctly-timed bubble with SENT/FAILED status beats a vanished one.
                    messageRepository.deleteById(opt.id)
                }
            }

            threadRepository.updateLastMessageAt(threadId, latest.timestamp)
            // Use emoji label for media-only MMS in the thread preview.
            threadRepository.updateLastMessagePreview(threadId, latest.previewText)

            // ── Notification for newly-arrived incoming MMS (GROUP_MESSAGING_SPEC §4.1) ──
            // One notification per thread this pass (the newest received message), mirroring
            // SmsReceiver's per-thread style. Dedup is structural, not a separate read-state
            // check: newMessages only ever contains rows with _id > maxStoredId, the watermark
            // captured at the top of this sync pass, so a given provider row can appear here
            // at most once ever — and syncLatestMms() never runs until the historical import
            // (first_sync_completed) is done, so the initial backlog import never reaches
            // this code path. Sent messages (isSent) are filtered out below.
            msgs.filter { !it.isSent }.maxByOrNull { it.timestamp }?.let { notifyIncomingMms(threadId, it) }
        }

        /* NOTE: optimistic MMS rows are deliberately NOT cleaned up for reaction-only
         * threads. A reaction fallback is never the real counterpart of a Postmark send
         * (our own reactions are local annotations, never transmitted), so deleting here
         * could only ever destroy a still-in-flight send's temp row — racing
         * MmsSentReceiver into skipping the provider persist and losing the message.
         * Temp rows are removed solely by the matched hand-off above. */

        /* Resolve MMS reaction fallback messages into Reaction entities (deduped).
         * Mirrors the same logic used in syncLatestSms() for quoted fallbacks, plus the
         * bare-emoji branch (media reactions archived as a lone emoji). If neither can be
         * attached, insert the row as a normal visible bubble. */
        val unresolvedReactionMsgs = mutableListOf<Message>()
        reactionMsgs.forEach { message ->
            val threadMessages = messageRepository.getByThread(message.threadId)
            val senderAddress = if (message.isSent) SELF_ADDRESS else message.address
            val parsed = reactionParser.parse(message.body)

            if (parsed != null) {
                // Quoted reaction fallback (Android/Apple textual form).
                val reaction = reactionParser.processIncomingMessage(message, threadMessages, senderAddress)
                if (reaction != null) {
                    if (parsed.isRemoval) {
                        messageRepository.deleteReaction(reaction.messageId, reaction.senderAddress, reaction.emoji)
                    } else if (!messageRepository.reactionExists(reaction.messageId, reaction.senderAddress, reaction.emoji)) {
                        messageRepository.insertReaction(reaction)
                    }
                } else if (!parsed.isRemoval) {
                    // Original not found — preserve as a normal visible bubble (additions only).
                    unresolvedReactionMsgs += message
                }
            } else {
                // Bare-emoji reaction candidate: attach to the immediately-preceding media
                // message, else fall through to a normal bubble. A sent candidate uses
                // SELF_ADDRESS so it dedupes against any existing local pill.
                val participantCount = threadRepository.getById(message.threadId)?.participants?.size ?: 0
                val target = findBareEmojiReactionTarget(message, threadMessages, participantCount) {
                    reactionParser.isReactionFallback(it.body)
                }
                if (target != null) {
                    val emoji = message.body.trim()
                    if (!messageRepository.reactionExists(target.id, senderAddress, emoji)) {
                        messageRepository.insertReaction(
                            Reaction(
                                id = 0,
                                messageId = target.id,
                                senderAddress = senderAddress,
                                emoji = emoji,
                                timestamp = message.timestamp,
                                rawText = message.body
                            )
                        )
                    }
                } else {
                    unresolvedReactionMsgs += message
                }
            }
        }
        if (unresolvedReactionMsgs.isNotEmpty()) {
            messageRepository.insertAll(unresolvedReactionMsgs)
        }
    }

    // Extracts MMS messages from a single cursor into [newMessages], deduplicating by rawId
    // when [seenRawIds] is supplied (used for multi-cursor Samsung mailbox fallback paths).
    private suspend fun extractMmsMessages(
        cursor: android.database.Cursor,
        newMessages: MutableList<Message>,
        ensuredThreadIds: MutableSet<Long>,
        seenRawIds: MutableSet<Long> = mutableSetOf()
    ) {
        val idIdx       = cursor.getColumnIndexOrThrow("_id")
        val threadIdx   = cursor.getColumnIndexOrThrow("thread_id")
        val dateIdx     = cursor.getColumnIndexOrThrow("date")
        // Not OrThrow: date_sent isn't guaranteed present on every OEM's mailbox URIs.
        val dateSentIdx = cursor.getColumnIndex("date_sent")
        val boxIdx      = cursor.getColumnIndexOrThrow("msg_box")
        // Not OrThrow: some OEMs omit the column even when requested in the projection;
        // isDisplayableMmsType(null) treats that as displayable (GROUP_MESSAGING_SPEC §4.2).
        val mTypeIdx  = cursor.getColumnIndex("m_type")

        while (cursor.moveToNext()) {
            val rawId    = cursor.getLong(idIdx)
            if (!seenRawIds.add(rawId)) continue
            val threadId = cursor.getLong(threadIdx)
            val dateSec  = cursor.getLong(dateIdx)
            // MMS provider times are SECONDS — ×1000. 0 means "not recorded" → NULL, not epoch.
            val sentAt   = if (dateSentIdx >= 0 && !cursor.isNull(dateSentIdx))
                cursor.getLong(dateSentIdx).takeIf { it > 0L }?.let { it * 1000L } else null
            val msgBox   = cursor.getInt(boxIdx)
            val mType    = if (mTypeIdx >= 0 && !cursor.isNull(mTypeIdx)) cursor.getInt(mTypeIdx) else null
            // Non-displayable PDU (M-Notification.ind, delivery/read reports): no text/media
            // parts, previously imported as an empty "Unknown" bubble. Skip before the
            // per-row sub-queries below — cheaper and it also means a stray notification-ind
            // PDU can no longer force-create a phantom thread for a brand-new contact.
            if (!isDisplayableMmsType(mType)) continue
            val id       = MMS_ID_OFFSET + rawId
            // Drafts (3) and outbox (4) are outgoing; only inbox (1) is received.
            val isSent   = msgBox != android.provider.Telephony.Mms.MESSAGE_BOX_INBOX
            val timestamp = dateSec * 1000L
            val parts    = getMmsBodyIncremental(rawId)
            val address  = getMmsAddressIncremental(rawId, isSent)
            debugLog("syncLatestMms: rawId=$rawId  attachments=${parts.attachments.size}  firstMime=${parts.attachments.firstOrNull()?.mimeType}")

            if (threadId !in ensuredThreadIds) {
                val existingThread = threadRepository.getById(threadId)
                if (existingThread == null) {
                    // Prefer the OS's canonical roster (self already excluded); fall back to the
                    // per-PDU addr scan only when the canonical query can't answer (GROUP_MESSAGING_SPEC §1.3).
                    ensureThread(threadId, address, timestamp) {
                        context.queryCanonicalParticipants(threadId) ?: run {
                            syncLogger.log(TAG, "canonical roster empty for threadId=$threadId — falling back to per-PDU addr rows")
                            getMmsParticipants(rawId)
                        }
                    }
                } else if (existingThread.participants.isEmpty() && !isSent) {
                    // Roster staleness hardening (GROUP_MESSAGING_SPEC §4.3): ensureThread()
                    // above only computes a roster once, at thread creation, so a thread born
                    // from SMS (or an earlier 1:1 MMS) would otherwise keep an empty roster
                    // forever even after it starts receiving group MMS. Only worth re-checking
                    // for an incoming message — a sent message can't reveal a roster the OS
                    // doesn't already know from receiving one.
                    promoteRosterIfGroup(threadId)
                }
                ensuredThreadIds += threadId
            }

            newMessages += Message(
                id = id,
                threadId = threadId,
                address = address,
                body = parts.body,
                timestamp = timestamp,
                isSent = isSent,
                type = msgBox,
                isMms = true,
                attachments = parts.attachments,
                isRead = isSent,
                sentAt = sentAt
            )
        }
    }

    // Reads text body and ALL media attachments from the given MMS part table.
    // Returns MmsParsedResult with stable content://mms/part/{id} URIs for image/video/audio.
    // Import call sites substitute a "[MMS]" placeholder when the cursor is unavailable.
    private fun getMmsBodyIncremental(mmsId: Long): MmsParsedResult =
        getMmsBodyIncrementalOrNull(mmsId) ?: MmsParsedResult("[MMS]", emptyList())

    // Null when the parts cursor is unavailable — the repair pass treats that as
    // "couldn't read, retry later" rather than writing a placeholder body.
    private fun getMmsBodyIncrementalOrNull(mmsId: Long): MmsParsedResult? {
        val cursor = context.contentResolver.query(
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, "$mmsId/part"),
            arrayOf("_id", Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
            null, null, null
        ) ?: run {
            Log.w(TAG, "getMmsBodyIncremental: parts cursor null for mmsId=$mmsId")
            return null
        }
        debugLog("getMmsBodyIncremental: mmsId=$mmsId  partCount=${cursor.count}")
        val rawParts = mutableListOf<MmsRawPart>()
        cursor.use {
            val idIdx   = it.getColumnIndexOrThrow("_id")
            val ctIdx   = it.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)
            val textIdx = it.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT)
            while (it.moveToNext()) {
                val ct = it.getString(ctIdx) ?: continue
                rawParts += MmsRawPart(it.getLong(idIdx), ct, it.getString(textIdx))
            }
        }
        // File-backed text parts (text column null) are streamed from the part URI —
        // Google Messages' RCS archival writes reaction fallbacks this way.
        return parseMmsRawParts(rawParts) { partId -> context.contentResolver.readMmsPartText(partId) }
    }

    private fun getMmsAddressIncremental(mmsId: Long, isSent: Boolean): String {
        // 137 = PduHeaders.FROM (sender of a received MMS)
        // 151 = PduHeaders.TO   (recipient of a sent MMS)
        val addrType = if (isSent) 151 else 137
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            "type = ?", arrayOf(addrType.toString()), null
        ) ?: return "Unknown"
        return cursor.use {
            if (it.moveToFirst()) {
                val addr = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                // "insert-address-token" is a Samsung PDU placeholder; treat as unknown.
                if (addr == "insert-address-token") "Unknown" else addr
            } else "Unknown"
        }
    }

    /*
     * [participants] is a lazy provider (not a plain list) because computing it requires an
     * extra content-resolver query (getMmsParticipants) that's only worth paying for the one
     * time a thread is actually created — the early return below skips it entirely for every
     * later message in an already-known thread.
     */
    private suspend fun ensureThread(
        threadId: Long,
        address: String,
        timestamp: Long,
        participants: () -> List<String> = { emptyList() }
    ) {
        if (threadRepository.getById(threadId) != null) return
        val roster = participants()
        // Group MMS: comma-join contact names so the thread list/top bar show everyone
        // without any UI change (MMS_AUDIT §2.3 — previously only one address was kept).
        val displayName = if (roster.size > 1) {
            roster.joinToString(", ") { context.lookupContactName(it) ?: it }
        } else {
            context.lookupContactName(address) ?: address.ifEmpty { "Unknown" }
        }
        threadRepository.upsert(
            com.plusorminustwo.postmark.domain.model.Thread(
                id = threadId,
                displayName = displayName,
                address = address,
                lastMessageAt = timestamp,
                backupPolicy = BackupPolicy.GLOBAL,
                participants = if (roster.size > 1) roster else emptyList()
            )
        )
    }

    /*
     * Roster staleness hardening (GROUP_MESSAGING_SPEC §4.3). Re-derives the canonical
     * roster for a thread whose stored [Thread.participants] is empty; promotes it to a
     * group only if the OS now reports more than one participant. A targeted UPDATE
     * (ThreadRepository.updateRoster) — never touches the telephony provider, and never
     * overwrites a user-set nickname (that column isn't part of this UPDATE statement),
     * matching the protection [repairRostersOnce] already relies on.
     */
    private suspend fun promoteRosterIfGroup(threadId: Long) {
        val canonical = context.queryCanonicalParticipants(threadId) ?: return
        if (canonical.size <= 1) return
        val displayName = canonical.joinToString(", ") { context.lookupContactName(it) ?: it }
        threadRepository.updateRoster(threadId, canonical, displayName)
        syncLogger.log(TAG, "roster staleness: promoted threadId=$threadId to group of ${canonical.size} participant(s)")
    }

    /*
     * Posts an incoming-message notification for a newly-synced MMS (GROUP_MESSAGING_SPEC
     * §4.1). Before this, MmsReceiver routed straight to onMmsContentChanged() and no
     * notification was ever posted for an incoming MMS — see the investigation note in
     * docs/GROUP_MESSAGING_SPEC.md §4. Reuses [IncomingNotifier] so SMS and MMS share one
     * builder/style/channel/grouping instead of a second copy drifting out of sync.
     *
     * Group threads omit the inline reply action, same reasoning as SmsReceiver's
     * suppression (a single-address reply can't reach the whole group). 1:1 MMS keeps it:
     * DirectReplyReceiver always sends a plain address-based text reply, which is exactly
     * as valid for a 1:1 MMS sender as it is for an SMS sender — no new plumbing needed.
     */
    private suspend fun notifyIncomingMms(threadId: Long, message: Message) {
        /* Sync-time notification needs two gates the SMS broadcast path gets for free
         * from SMS_DELIVER's default-app-only dispatch:
         *  — Default-app: sync runs no matter who the default messenger is (foreground
         *    catch-up poll, content observer, RCS archival broadcast). When another app
         *    holds the SMS role it is already notifying the user; every banner from us
         *    is a duplicate — and opening the app after a backlog accumulated blasted
         *    one notification per thread in a burst.
         *  — Freshness: a genuinely-new arrival (MmsReceiver-triggered sync) is seconds
         *    old when it reaches here; a backlog row (e.g. imported right after Postmark
         *    becomes default) can be hours old. Old messages belong in the thread, not
         *    in a banner.
         * The message itself still synced above; only the banner is skipped. */
        if (!context.isDefaultSmsApp()) return
        if (System.currentTimeMillis() - message.timestamp > NOTIFY_FRESHNESS_WINDOW_MS) return
        val thread = threadRepository.getById(threadId) ?: return
        // Suppress when the user is already viewing this exact thread (same id space as
        // ThreadScreen's threadId), or notifications are off / muted. The MMS still synced
        // above; only the banner is skipped.
        if (activeThreadTracker.activeThreadId == threadId) return
        if (!thread.notificationsEnabled || thread.isMuted || thread.isSpam) return
        val isGroup = thread.participants.size > 1
        val contactName = context.lookupContactName(message.address)
        val senderName = contactName
            ?: thread.displayName.ifEmpty { null }
            ?: message.address.ifEmpty { "Unknown" }
        val title = if (isGroup) {
            "$senderName — ${thread.nickname ?: thread.displayName}"
        } else {
            thread.nickname ?: senderName
        }
        incomingNotifier.notify(
            // 1:1: keyed on address so an SMS and an MMS from the same contact collapse
            // into one notification, matching SmsReceiver. Group: a thread has no single
            // stable address, so key on the thread id instead.
            notifKey = if (isGroup) threadId.hashCode() else message.address.hashCode(),
            threadId = threadId,
            address = message.address,
            title = title,
            body = message.previewText,
            privacyMode = privacyModeRepository.isEnabled(),
            allowDirectReply = !isGroup,
            // Blank/null contact lookup = unsaved number → offer Report spam (1:1 only; the
            // notifier itself also gates on not-group and a real thread id).
            senderIsKnownContact = !contactName.isNullOrBlank()
        )
    }

    // Returns every distinct participant address (FROM/TO/CC) for a group MMS PDU.
    // Ordinary 1:1 MMS also has exactly one row here — parseMmsParticipants collapses
    // to a single-element (or empty) list in that case, same cost as before.
    private fun getMmsParticipants(mmsId: Long): List<String> {
        val cursor = context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            null, null, null
        ) ?: return emptyList()
        val rows = cursor.use {
            val addrIdx = it.getColumnIndexOrThrow("address")
            val typeIdx = it.getColumnIndexOrThrow("type")
            val out = mutableListOf<MmsAddrRow>()
            while (it.moveToNext()) {
                out += MmsAddrRow(it.getString(addrIdx), it.getInt(typeIdx))
            }
            out
        }
        return parseMmsParticipants(rows)
    }

}
