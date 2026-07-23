package com.plusorminustwo.postmark.ui.conversations

import android.content.Context
import android.provider.Telephony
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.contacts.ContactResult
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.data.contacts.searchContacts
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.formatter.formatPhoneNumber
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.RecipientChip
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.model.addRecipient
import com.plusorminustwo.postmark.domain.model.normalizeAddressForDedupe
import com.plusorminustwo.postmark.domain.model.removeRecipient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * ViewModel for [NewConversationScreen].
 *
 * Drives live contact search as the user types, and resolves or creates a Room
 * [Thread] entity before signalling the screen to navigate to [ThreadScreen].
 */
@HiltViewModel
class NewConversationViewModel @Inject constructor(
    private val threadRepository: ThreadRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    // ── Search query ──────────────────────────────────────────────────────────

    private val _query = MutableStateFlow("")
    /** Current text in the recipient field; drives [contacts] search. */
    val query: StateFlow<String> = _query.asStateFlow()

    /** Called on every keystroke from the recipient TextField. */
    fun onQueryChange(value: String) {
        _query.value = value
    }

    // ── Contact search results ─────────────────────────────────────────────────

    /**
     * Debounced live contact search results. Empty when [query] is shorter than
     * 2 characters to avoid querying the contacts DB on every keystroke.
     */
    @OptIn(FlowPreview::class)
    val contacts: StateFlow<List<ContactResult>> = _query
        .debounce(200)
        .mapLatest { q -> if (q.length < 2) emptyList() else context.searchContacts(q) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Navigation event ───────────────────────────────────────────────────────

    private val _navigateToThread = MutableStateFlow<Long?>(null)
    /**
     * Emits a threadId once the thread has been created or found. The screen
     * observes this in a [LaunchedEffect] and calls [consumeNavigation] after
     * reading the value to reset it.
     */
    val navigateToThread: StateFlow<Long?> = _navigateToThread.asStateFlow()

    /** Reset after the screen has consumed the navigation event. */
    fun consumeNavigation() {
        _navigateToThread.value = null
    }

    // ── Multi-recipient selection (GROUP_MESSAGING_SPEC §3) ────────────────────────
    // selectionMode is an explicit flag, not derived from "strip non-empty" — a strip
    // can legitimately be empty while selection mode is on (right after entering, or
    // after removing the last chip) and the mode must still show a visible, obvious
    // UI (empty chip strip) rather than silently reverting to the single-recipient
    // affordances. Mode off = every entry point below behaves exactly as it did before
    // this feature (picking one contact or typing one number sends immediately — no
    // extra step, per standing feedback that a hidden gesture must never be the only
    // way in — see the pinned "Start group conversation" row in the screen). Long-press
    // on a contact row remains a power-user shortcut that also enters the mode.

    private val _selectionMode = MutableStateFlow(false)
    /** True while the user is building a group (entered via the pinned row or a
     *  long-press); drives the chip strip, row-tap routing, and the top bar. */
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedRecipients = MutableStateFlow<List<RecipientChip>>(emptyList())
    /** Chips currently staged for a new group conversation. */
    val selectedRecipients: StateFlow<List<RecipientChip>> = _selectedRecipients.asStateFlow()

    /** Visible entry point (pinned list row): turns on selection mode with an empty
     *  strip. A no-op if already in the mode. */
    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    /** Top-bar close/X or system back while selecting: leaves selection mode and drops
     *  whatever was staged — there's no "save for later", so a half-built group is
     *  simply discarded, matching a cancel action. */
    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedRecipients.value = emptyList()
    }

    /** Long-press (also enters selection mode if it wasn't already on) or a tap while
     *  already selecting: adds [contact] to the strip, or removes it if it's already
     *  there. */
    fun toggleContact(contact: ContactResult) {
        _selectionMode.value = true
        val chip = RecipientChip(contact.address, contact.displayName)
        val current = _selectedRecipients.value
        val alreadySelected = current.any {
            normalizeAddressForDedupe(it.address) == normalizeAddressForDedupe(chip.address)
        }
        _selectedRecipients.value =
            if (alreadySelected) removeRecipient(current, chip.address) else addRecipient(current, chip)
    }

    /** Removes a chip by address — used by the chip strip's own close icon. Selection
     *  mode itself is untouched, so an emptied strip still shows (per the standing
     *  visible-entry-point rule, the mode should never silently disappear). */
    fun removeChip(address: String) {
        _selectedRecipients.value = removeRecipient(_selectedRecipients.value, address)
    }

    /** Adds a manually-typed number as a chip and clears the search field, rather than
     *  sending immediately — used only while selection mode is on. */
    private fun addManualChip(address: String) {
        _selectedRecipients.value =
            addRecipient(_selectedRecipients.value, RecipientChip(address, formatPhoneNumber(address)))
        _query.value = ""
    }

    /**
     * Handles the recipient field's Go/Search keyboard action and the send icon.
     * Single-recipient path (selection mode off): starts the conversation immediately,
     * identical to the pre-group-messaging behavior. Group-building path (selection
     * mode on): adds the number as a chip instead, so it doesn't jump away from the
     * group being built.
     */
    fun onManualEntrySubmitted(address: String) {
        if (address.isBlank()) return
        if (!_selectionMode.value) startConversation(address) else addManualChip(address)
    }

    /** Confirms the staged chips: creates (or reuses) the thread and navigates to it.
     *  A single staged chip resolves to an ordinary 1:1 thread — [startGroupConversation]
     *  handles that case the same way [startConversation] does, since a one-address
     *  roster is equivalent to today's single-recipient create ([Thread.participants]
     *  of size <= 1 already collapses to a 1:1 thread at persistence, see
     *  [com.plusorminustwo.postmark.domain.model.encodeParticipantsJson]). */
    fun confirmSelection() {
        val addresses = _selectedRecipients.value.map { it.address }
        if (addresses.isEmpty()) return
        startGroupConversation(addresses)
    }

    // ── Thread creation ────────────────────────────────────────────────────────

    /**
     * Triggered when the user selects a contact from the list or taps compose
     * with a raw number. Calls [Telephony.Threads.getOrCreateThreadId] to get
     * the system thread ID, ensures a Room entity exists, then signals the
     * screen to navigate via [navigateToThread].
     *
     * @param address The raw phone number to open or create a thread for.
     */
    fun startConversation(address: String) {
        if (address.isBlank()) return
        viewModelScope.launch {
            val threadId = withContext(Dispatchers.IO) {
                // ── Step 1: Resolve system thread ID ──────────────────────────
                // The system SMS provider gives us a stable ID for this address.
                val sysThreadId = Telephony.Threads.getOrCreateThreadId(context, address)

                // ── Step 2: Ensure Room entity exists ─────────────────────────
                // SmsSyncHandler will fill this in properly on the next sync, but
                // we create the entity now so ThreadScreen can observe it
                // immediately without a null flash.
                if (threadRepository.getById(sysThreadId) == null) {
                    val displayName = context.lookupContactName(address) ?: address
                    threadRepository.upsert(
                        Thread(
                            id           = sysThreadId,
                            displayName  = displayName,
                            address      = address,
                            lastMessageAt = 0L,
                            backupPolicy = BackupPolicy.GLOBAL
                        )
                    )
                }
                sysThreadId
            }
            _navigateToThread.value = threadId
        }
    }

    /**
     * Group counterpart of [startConversation] (GROUP_MESSAGING_SPEC §3): resolves the
     * canonical thread id for the whole roster via the [Telephony.Threads.getOrCreateThreadId]
     * Set overload (same precedent as [MmsManagerWrapper.persistSentMms] and
     * [com.plusorminustwo.postmark.service.backup.RestoreWorker.resolveThreadId]), then
     * upserts a Room entity shaped exactly like [SmsSyncHandler.ensureThread] produces for
     * an incoming group MMS: [participants] set, [displayName] comma-joined from contact
     * lookups. P1's send path takes over from there.
     *
     * @param addresses The full recipient roster (already deduped by the chip strip).
     */
    private fun startGroupConversation(addresses: List<String>) {
        viewModelScope.launch {
            val threadId = withContext(Dispatchers.IO) {
                val sysThreadId = Telephony.Threads.getOrCreateThreadId(context, addresses.toSet())
                if (threadRepository.getById(sysThreadId) == null) {
                    val displayName = addresses.joinToString(", ") { context.lookupContactName(it) ?: it }
                    threadRepository.upsert(
                        Thread(
                            id            = sysThreadId,
                            displayName   = displayName,
                            address       = addresses.first(),
                            lastMessageAt = 0L,
                            backupPolicy  = BackupPolicy.GLOBAL,
                            participants  = addresses
                        )
                    )
                }
                sysThreadId
            }
            _selectionMode.value = false
            _selectedRecipients.value = emptyList()
            _navigateToThread.value = threadId
        }
    }

}
