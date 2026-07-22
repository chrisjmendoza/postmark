package com.plusorminustwo.postmark.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.SearchRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.SearchDateRange
import com.plusorminustwo.postmark.domain.model.Thread
import com.plusorminustwo.postmark.domain.model.toBoundsMs
import com.plusorminustwo.postmark.domain.search.SearchResultGroup
import com.plusorminustwo.postmark.domain.search.groupResultsByContact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ordering applied to search results. Session-only — not persisted. */
enum class SortOrder {
    /** ORDER BY timestamp DESC — the default flat, most-recent-first list. */
    MOST_RECENT,
    /** Group by thread, section headers A–Z; newest-first within each group. */
    BY_CONTACT
}

/** Immutable snapshot of all active search filter values. */
data class SearchFilters(
    val threadId: Long? = null,
    val isSentOnly: Boolean? = null,
    val isMms: Boolean? = null,
    val reactionEmoji: String? = null,
    val dateRange: SearchDateRange = SearchDateRange.ALL_TIME
)

/** Full UI state for the Search screen, derived from several combined [StateFlow]s. */
data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val results: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val threads: List<Thread> = emptyList(),
    val selectedThread: Thread? = null,
    val reactionEmojis: List<String> = emptyList(),
    val sortOrder: SortOrder = SortOrder.MOST_RECENT,
    // Sort direction, orthogonal to [sortOrder]: false = newest-first (default),
    // true = oldest-first. Session-only, never persisted.
    val oldestFirst: Boolean = false,
    // Populated only in BY_CONTACT mode — a pure regrouping of [results] by thread.
    val groupedResults: List<SearchResultGroup> = emptyList()
)

/**
 * ViewModel for the Search screen.
 *
 * Combines a debounced text query with a set of structured filters ([SearchFilters])
 * and delegates to [SearchRepository] for FTS-backed results. Results update
 * automatically whenever the query or any filter changes.
 *
 * When launched with a `threadId` in [SavedStateHandle] (from "Search in thread"),
 * the thread filter is pre-applied in `init`.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val searchRepository: SearchRepository,
    private val threadRepository: ThreadRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _filters = MutableStateFlow(SearchFilters())
    // In-flight query job — cancelled when a newer query supersedes it (see search()).
    private var searchJob: Job? = null

    private val _results = MutableStateFlow<List<Message>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _selectedThread = MutableStateFlow<Thread?>(null)
    private val _sortOrder = MutableStateFlow(SortOrder.MOST_RECENT)
    private val _oldestFirst = MutableStateFlow(false)

    // Groups the "auxiliary" inputs so the outer combine stays within its 5-flow arity.
    private data class Aux(
        val threads: List<Thread>,
        val selectedThread: Thread?,
        val emojis: List<String>,
        val sortOrder: SortOrder,
        val oldestFirst: Boolean
    )

    val uiState: StateFlow<SearchUiState> = combine(
        _query, _filters, _results, _isLoading,
        combine(
            threadRepository.observeAll(),
            _selectedThread,
            searchRepository.observeDistinctEmojis(),
            _sortOrder,
            _oldestFirst
        ) { threads, selected, emojis, sortOrder, oldestFirst ->
            Aux(threads, selected, emojis, sortOrder, oldestFirst)
        }
    ) { query, filters, results, loading, aux ->
        SearchUiState(
            query = query,
            filters = filters,
            results = results,
            isLoading = loading,
            threads = aux.threads,
            selectedThread = aux.selectedThread,
            reactionEmojis = aux.emojis,
            sortOrder = aux.sortOrder,
            oldestFirst = aux.oldestFirst,
            groupedResults = if (aux.sortOrder == SortOrder.BY_CONTACT)
                groupResultsByContact(results, aux.threads, aux.oldestFirst) else emptyList()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        // Pre-filter to a specific thread if launched from "Search in thread" overflow menu.
        val prefilterThreadId = savedStateHandle.get<Long>("threadId") ?: -1L
        if (prefilterThreadId != -1L) {
            viewModelScope.launch {
                val thread = threadRepository.observeAll().first()
                    .find { it.id == prefilterThreadId }
                if (thread != null) setThreadFilter(thread)
            }
        }

        // Direction is part of the trigger, not just presentation: because the DAO
        // LIMITs each page, oldest-first must re-run the query (surfacing the oldest N),
        // it cannot be a display-time reversal of the newest N.
        combine(
            _query.debounce(300),
            _filters,
            _oldestFirst
        ) { query, filters, oldestFirst -> Triple(query, filters, oldestFirst) }
            .onEach { (query, filters, oldestFirst) -> search(query, filters, oldestFirst) }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) { _query.value = query }

    fun onFiltersChange(filters: SearchFilters) { _filters.value = filters }

    fun setThreadFilter(thread: Thread?) {
        _selectedThread.value = thread
        _filters.update { it.copy(threadId = thread?.id) }
    }

    fun setDateRangeFilter(range: SearchDateRange) {
        _filters.update { it.copy(dateRange = range) }
    }

    fun setReactionFilter(emoji: String?) {
        _filters.update { it.copy(reactionEmoji = emoji) }
    }

    fun setProtocolFilter(isMms: Boolean?) {
        _filters.update { it.copy(isMms = isMms) }
    }

    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }

    fun setOldestFirst(oldestFirst: Boolean) { _oldestFirst.value = oldestFirst }

    private fun search(query: String, filters: SearchFilters, oldestFirst: Boolean) {
        // Allow browse (blank query) when a protocol filter is active.
        if (query.isBlank() && filters.isMms == null) {
            searchJob?.cancel()
            _results.value = emptyList()
            return
        }
        // Cancel the in-flight query: without this, an expensive broad FTS query
        // finishing after a cheaper narrow one overwrote the newer results.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isLoading.value = true
            val (startMs, _) = filters.dateRange.toBoundsMs()
            _results.value = searchRepository.search(
                rawQuery    = query,
                threadId    = filters.threadId,
                isSent      = filters.isSentOnly,
                startMs     = startMs,
                isMms       = filters.isMms,
                reactionEmoji = filters.reactionEmoji,
                oldestFirst = oldestFirst
            )
            _isLoading.value = false
        }
    }
}

