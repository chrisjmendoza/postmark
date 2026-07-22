package com.plusorminustwo.postmark.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plusorminustwo.postmark.data.contacts.lookupContactName
import com.plusorminustwo.postmark.data.repository.BlockedNumbersRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One blocked number as shown in the list — raw number plus an optional resolved contact name. */
data class BlockedNumberRow(
    val id: Long,
    val number: String,
    val contactName: String?
)

data class BlockedNumbersUiState(
    /** False when Postmark isn't the default SMS app — the screen shows an explanation instead. */
    val canBlock: Boolean = true,
    val loading: Boolean = true,
    val numbers: List<BlockedNumberRow> = emptyList()
)

/** ViewModel for [BlockedNumbersScreen] — lists the system blocked numbers and unblocks them. */
@HiltViewModel
class BlockedNumbersViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: BlockedNumbersRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlockedNumbersUiState())
    val uiState: StateFlow<BlockedNumbersUiState> = _uiState.asStateFlow()

    init { reload() }

    /** Re-queries the provider and resolves contact names off the main thread. */
    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!repository.canBlock()) {
                _uiState.value = BlockedNumbersUiState(canBlock = false, loading = false)
                return@launch
            }
            val rows = repository.getBlockedNumbers().map { blocked ->
                BlockedNumberRow(
                    id = blocked.id,
                    number = blocked.number,
                    contactName = context.lookupContactName(blocked.number)
                )
            }
            _uiState.value = BlockedNumbersUiState(canBlock = true, loading = false, numbers = rows)
        }
    }

    /** Unblocks [id] (deletes its provider row) then reloads the list. */
    fun unblock(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.unblock(id)
            reload()
        }
    }
}
