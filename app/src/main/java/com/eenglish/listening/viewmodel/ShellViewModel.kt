package com.eenglish.listening.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranscriptMode { ENGLISH, CHINESE, BILINGUAL }

data class ShellUiState(val transcriptMode: TranscriptMode = TranscriptMode.ENGLISH)

/** Only presentation state exists at CP1. Practice records will be persisted in Room. */
class ShellViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ShellUiState(
            TranscriptMode.entries.firstOrNull {
                it.name == savedStateHandle.get<String>(MODE_KEY)
            } ?: TranscriptMode.ENGLISH,
        ),
    )
    val uiState = mutableState.asStateFlow()

    fun selectTranscriptMode(mode: TranscriptMode) {
        savedStateHandle[MODE_KEY] = mode.name
        mutableState.value = ShellUiState(mode)
    }

    private companion object { const val MODE_KEY = "transcriptMode" }
}
