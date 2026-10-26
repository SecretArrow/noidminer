package org.secretarrow.noidminer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.LogLine
import org.secretarrow.noidminer.pow.MinerCore

/** Merges engine logs and service logs into one live console feed. */
class LogViewModel : ViewModel() {

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    init {
        val core = ServiceLocator.minerCore()
        viewModelScope.launch {
            core.log.collect { line ->
                _lines.value = (_lines.value + line).takeLast(MAX_LINES)
            }
        }
        viewModelScope.launch {
            ServiceLocator.serviceLog.collect { line ->
                _lines.value = (_lines.value + line).takeLast(MAX_LINES)
            }
        }
    }

    companion object {
        private const val MAX_LINES = 400
    }
}
