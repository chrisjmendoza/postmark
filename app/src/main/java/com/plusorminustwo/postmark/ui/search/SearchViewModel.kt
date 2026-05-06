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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchFilters(
    val threadId: Long? = null,
    val isSentOnly: Boolean? = null,
    val isMms: Boolean? = null,
    val reactionEmoji: String? = null,
    val dateRange: SearchDateRange = SearchDateRange.ALL_TIME
)

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val results: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val threads: List<Thread> = emptyList(),
    val selectedThread: Thread? = null,
    val reactionEmojis: List<String> = emptyList()
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val searchRepository: SearchRepository,
    private val threadRepository: ThreadRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _filters = MutableStateFlow(SearchFilters())
    private val _results = MutableStateFlow<List<Message>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _selectedThread = MutableStateFlow<Thread?>(null)

    val uiState: StateFlow<SearchUiState> = combine(
        _query, _filters, _results, _isLoading,
        combine(
            threadRepository.observeAll(),
            _selectedThread,
            searchRepository.observeDistinctEmojis()
        ) { threads, selected, emojis -> Triple(threads, selected, emojis) }
    ) { query, filters, results, loading, triple ->
        SearchUiState(
            query = query,
            filters = filters,
            results = results,
            isLoading = loading,
            threads = triple.first,
            selectedThread = triple.second,
            reactionEmojis = triple.third
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

        _query.debounce(300)
            .combine(_filters) { q, f -> q to f }
            .onEach { (query, filters) -> search(query, filters) }
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

    private fun search(query: String, filters: SearchFilters) {
        // Allow browse (blank query) when a protocol filter is active.
        if (query.isBlank() && filters.isMms == null) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val (startMs, _) = filters.dateRange.toBoundsMs()
            _results.value = searchRepository.search(
                rawQuery    = query,
                threadId    = filters.threadId,
                isSent      = filters.isSentOnly,
                startMs     = startMs,
                isMms       = filters.isMms,
                reactionEmoji = filters.reactionEmoji
            )
            _isLoading.value = false
        }
    }
}

