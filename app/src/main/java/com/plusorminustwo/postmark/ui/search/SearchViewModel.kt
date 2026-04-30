package com.plusorminustwo.postmark.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.SearchRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.model.Message
import com.plusorminustwo.postmark.domain.model.Thread
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchFilters(
    val threadId: Long? = null,
    val isSentOnly: Boolean? = null,
    val hasReaction: Boolean = false,
    val startMs: Long? = null,
    val endMs: Long? = null
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

    private fun search(query: String, filters: SearchFilters) {
        if (query.isBlank()) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _results.value = searchRepository.search(
                rawQuery = query,
                threadId = filters.threadId,
                isSent = filters.isSentOnly,
                startMs = filters.startMs,
                endMs = filters.endMs
            )
            _isLoading.value = false
        }
    }
}

