package com.plusorminustwo.postmark.ui.conversations

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Thread
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

// ── Data model ────────────────────────────────────────────────────────────────

/** A single contact entry returned by the live search. */
data class ContactResult(
    /** Human-readable contact name (or raw number if no match). */
    val displayName: String,
    /** Raw phone number — passed to [NewConversationViewModel.startConversation]. */
    val address: String
)

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
        .mapLatest { q -> if (q.length < 2) emptyList() else searchContacts(q) }
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
                    val displayName = lookupContactName(address) ?: address
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

    // ── Contacts helpers ───────────────────────────────────────────────────────

    /**
     * Queries [ContactsContract.CommonDataKinds.Phone] for entries whose display
     * name or number contains [query]. Returns at most 20 deduplicated results,
     * sorted by display name.
     *
     * Runs on whichever dispatcher [mapLatest] uses — caller must ensure this
     * is not the main thread (the [stateIn] + [mapLatest] pipeline runs on the
     * default dispatcher inside viewModelScope).
     */
    private suspend fun searchContacts(query: String): List<ContactResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<ContactResult>()
            val seen    = mutableSetOf<String>()
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                        "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                    arrayOf("%$query%", "%$query%"),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx  = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext() && results.size < 20) {
                        val name = cursor.getString(nameIdx) ?: continue
                        // Strip whitespace so the de-dup set is number-normalised.
                        val num  = cursor.getString(numIdx)?.replace("\\s".toRegex(), "") ?: continue
                        if (seen.add(num)) {
                            results += ContactResult(name, num)
                        }
                    }
                }
            } catch (_: SecurityException) {
                // READ_CONTACTS not granted — return empty list; screen handles gracefully.
            }
            results
        }

    /** Reverse-lookup the contact display name for a given phone number. */
    private fun lookupContactName(address: String): String? {
        if (address.isEmpty()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (_: SecurityException) { null }
    }
}
