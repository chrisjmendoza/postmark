package com.plusorminustwo.postmark.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.sync.SyncLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backs [SyncLogScreen]. Reads the raw log text from [SyncLogger] and exposes
 * it as a list of trimmed lines so the screen can render and color-code each entry.
 */
@HiltViewModel
class SyncLogViewModel @Inject constructor(
    private val syncLogger: SyncLogger
) : ViewModel() {

    // Each element is one raw log line (e.g. "2026-05-07 07:22:41 [MmsSentReceiver] …").
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    init {
        refresh()
    }

    /** Re-reads the log file and updates [lines]. */
    fun refresh() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { syncLogger.readLog() }
            _lines.value = text.lines().filter { it.isNotBlank() }
        }
    }

    /** Clears the on-disk log and empties the list. */
    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { syncLogger.clearLog() }
            _lines.value = emptyList()
        }
    }
}
