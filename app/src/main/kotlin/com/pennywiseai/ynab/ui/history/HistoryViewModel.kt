package com.pennywiseai.ynab.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Narrow seam over CaptureScheduler.retryMessage so the VM stays unit-testable. */
fun interface MessageRetrier {
    fun retry(timestamp: Long)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val dao: ProcessedMessageDao,
    private val retrier: MessageRetrier,
) : ViewModel() {

    private val _filter = MutableStateFlow<MessageStatus?>(null)
    val filter: StateFlow<MessageStatus?> = _filter

    val items: StateFlow<List<ProcessedMessageEntity>> =
        _filter.flatMapLatest { status ->
            if (status == null) dao.observeAll() else dao.observeByStatus(status)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(status: MessageStatus?) { _filter.value = status }

    /** Manual retry, available on any FAILED row: re-drive its inbox window (idempotent). */
    fun retry(item: ProcessedMessageEntity) = retrier.retry(item.timestamp)
}
