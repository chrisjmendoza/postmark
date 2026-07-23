package com.plusorminustwo.postmark.ui.forward

import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.data.db.entity.DELIVERY_STATUS_PENDING
import com.plusorminustwo.postmark.data.repository.MessageRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.BackupPolicy
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.service.sms.MmsManagerWrapper
import com.plusorminustwo.postmark.service.sms.SmsManagerWrapper
import com.plusorminustwo.postmark.ui.conversations.ContactResult
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

/**
 * ViewModel for [ForwardPickerScreen] — picks a destination (existing conversation or
 * contact) and sends a fresh copy of one message there.
 *
 * Deliberately simpler than [com.plusorminustwo.postmark.ui.thread.ThreadViewModel]'s
 * live-compose send path: no PendingIntent-driven fast delivery-status callback, no
 * FileProvider re-caching of the optimistic row's attachments. The forwarded copy still
 * sends correctly and still gets reconciled by the normal incremental sync
 * (`SmsSyncHandler.syncLatestMms`/`syncLatestSms`) once the real row lands — it just
 * takes the ordinary sync path to pick up its final delivery status instead of the
 * PendingIntent shortcut, which is an acceptable trade-off for a forward (not the
 * primary compose flow) rather than duplicating that whole subsystem here.
 */
@HiltViewModel
class ForwardPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val threadRepository: ThreadRepository,
    private val mmsManagerWrapper: MmsManagerWrapper,
    private val smsManagerWrapper: SmsManagerWrapper
) : ViewModel() {

    // One or more source messages to forward, parsed from the comma-joined nav arg. A whole
    // selection forwards in one trip; each message is sent to the chosen destination in
    // timestamp order (see [forward]).
    private val sourceMessageIds: List<Long> =
        checkNotNull(savedStateHandle.get<String>("messageIds"))
            .split(",").filter { it.isNotBlank() }.map { it.toLong() }

    /** How many messages this picker will forward — drives the confirm-dialog wording. */
    val messageCount: Int = sourceMessageIds.size

    // ── Recent conversations ──────────────────────────────────────────────────
    val recentThreads: StateFlow<List<Thread>> = threadRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Contact search (same pattern as NewConversationViewModel) ────────────
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun onQueryChange(value: String) { _query.value = value }

    @OptIn(FlowPreview::class)
    val contacts: StateFlow<List<ContactResult>> = _query
        .debounce(200)
        .mapLatest { q -> if (q.length < 2) emptyList() else searchContacts(q) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Forward result ────────────────────────────────────────────────────────
    private val _forwardedToThreadId = MutableStateFlow<Long?>(null)
    /** Emits the destination threadId once the forward has been dispatched. The screen
     *  navigates there (popping this picker off the back stack) and consumes it. */
    val forwardedToThreadId: StateFlow<Long?> = _forwardedToThreadId.asStateFlow()
    fun consumeForwardResult() { _forwardedToThreadId.value = null }

    /** Forwards the source message to an existing thread. */
    fun forwardToThread(thread: Thread) {
        viewModelScope.launch { forward(thread.id, thread.address) }
    }

    /** Forwards the source message to a raw address, creating a thread if none exists yet. */
    fun forwardToAddress(address: String) {
        if (address.isBlank()) return
        viewModelScope.launch {
            val threadId = withContext(Dispatchers.IO) {
                val sysThreadId = Telephony.Threads.getOrCreateThreadId(context, address)
                if (threadRepository.getById(sysThreadId) == null) {
                    threadRepository.upsert(
                        Thread(
                            id = sysThreadId,
                            displayName = context.lookupContactName(address) ?: address,
                            address = address,
                            lastMessageAt = 0L,
                            backupPolicy = BackupPolicy.GLOBAL
                        )
                    )
                }
                sysThreadId
            }
            forward(threadId, address)
        }
    }

    private suspend fun forward(destThreadId: Long, destAddress: String) {
        // Load every source message and send them in timestamp order (oldest first) so the
        // destination thread preserves the original sequence. A missing row is skipped.
        val sources = sourceMessageIds
            .mapNotNull { messageRepository.getById(it) }
            .sortedBy { it.timestamp }
        val now = System.currentTimeMillis()
        sources.forEachIndexed { index, source ->
            // Distinct tempId + timestamp per message so rapid-fire optimistic inserts don't
            // collide on the primary key or reorder within the same millisecond.
            val stamp = now + index
            val tempId = -stamp
            val optimistic = Message(
                id = tempId,
                threadId = destThreadId,
                address = destAddress,
                body = source.body,
                timestamp = stamp,
                isSent = true,
                type = Telephony.Sms.MESSAGE_TYPE_SENT,
                deliveryStatus = DELIVERY_STATUS_PENDING,
                isMms = source.attachments.isNotEmpty(),
                attachments = source.attachments
            )
            messageRepository.insert(optimistic)
            if (source.attachments.isNotEmpty()) {
                mmsManagerWrapper.sendMms(
                    toAddresses = listOf(destAddress),
                    textBody = source.body,
                    attachments = source.attachments,
                    messageId = tempId,
                    sentIntent = null
                )
            } else {
                smsManagerWrapper.sendTextMessage(destAddress, source.body, tempId)
            }
        }
        _forwardedToThreadId.value = destThreadId
    }

    // ── Contacts helpers (copied from NewConversationViewModel — see its doc for why
    // this queries system Contacts directly rather than going through Room) ──────────

    private suspend fun searchContacts(query: String): List<ContactResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<ContactResult>()
            val seen = mutableSetOf<String>()
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
                    val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext() && results.size < 20) {
                        val name = cursor.getString(nameIdx) ?: continue
                        val num = cursor.getString(numIdx)?.replace("\\s".toRegex(), "") ?: continue
                        if (seen.add(num)) results += ContactResult(name, num)
                    }
                }
            } catch (_: SecurityException) {
                // READ_CONTACTS not granted — return empty list; screen handles gracefully.
            }
            results
        }

}
