package com.plusorminustwo.postmark.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.db.dao.ThreadStatsDao
import com.plusorminustwo.postmark.data.db.entity.ThreadStatsEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class StatsDisplayStyle { NUMBERS, CHARTS, HEATMAP }

data class StatsUiState(
    val allStats: List<ThreadStatsEntity> = emptyList(),
    val displayStyle: StatsDisplayStyle = StatsDisplayStyle.NUMBERS
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsDao: ThreadStatsDao
) : ViewModel() {

    private val _displayStyle = kotlinx.coroutines.flow.MutableStateFlow(StatsDisplayStyle.NUMBERS)

    val allStats: StateFlow<List<ThreadStatsEntity>> = statsDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val displayStyle: StateFlow<StatsDisplayStyle> = _displayStyle

    fun setDisplayStyle(style: StatsDisplayStyle) { _displayStyle.value = style }
}
