package com.eenglish.listening

import androidx.lifecycle.SavedStateHandle
import com.eenglish.listening.viewmodel.ShellViewModel
import com.eenglish.listening.viewmodel.TranscriptMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellViewModelTest {
    @Test
    fun defaultsToEnglish() {
        assertEquals(TranscriptMode.ENGLISH, ShellViewModel(SavedStateHandle()).uiState.value.transcriptMode)
    }

    @Test
    fun selectedModeCanBeRestoredIntoNewViewModel() {
        val handle = SavedStateHandle()
        val model = ShellViewModel(handle)
        model.selectTranscriptMode(TranscriptMode.BILINGUAL)
        assertEquals(TranscriptMode.BILINGUAL, model.uiState.value.transcriptMode)
        assertEquals(TranscriptMode.BILINGUAL, ShellViewModel(handle).uiState.value.transcriptMode)
    }

    @Test
    fun unknownStoredModeFallsBackToEnglish() {
        val handle = SavedStateHandle(mapOf("transcriptMode" to "invalid"))
        assertEquals(TranscriptMode.ENGLISH, ShellViewModel(handle).uiState.value.transcriptMode)
    }
}
