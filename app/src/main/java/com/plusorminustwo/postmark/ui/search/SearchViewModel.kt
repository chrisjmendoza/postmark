package com.plusorminustwo.postmark.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.repository.SearchRepository
import com.plusorminustwo.postmark.domain.model.Message
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
    val isLoading: Boolean = false
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _filters = MutableStateFlow(SearchFilters())
    private val _results = MutableStateFlow<List<Message>>(emptyList())
    private val _isLoading = MutableStateFlow(false)

    val uiState: StateFlow<SearchUiState> = combine(
        _query, _filters, _results, _isLoading
    ) { query, filters, results, loading ->
        SearchUiState(query, filters, results, loading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        _query.debounce(300)
            .combine(_filters) { q, f -> q to f }
            .onEach { (query, filters) -> search(query, filters) }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) { _query.value = query }

    fun onFiltersChange(filters: SearchFilters) { _filters.value = filters }

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
