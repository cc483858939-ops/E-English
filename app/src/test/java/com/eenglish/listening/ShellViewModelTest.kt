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
    fun selectedModeCanBeRestoredFromIndependentSavedState() {
        val handle = SavedStateHandle()
        val model = ShellViewModel(handle)
        model.selectTranscriptMode(TranscriptMode.BILINGUAL)
        assertEquals(TranscriptMode.BILINGUAL, model.uiState.value.transcriptMode)
        val snapshot = handle.keys().associateWith { key -> handle.get<Any?>(key) }
        val restoredHandle = SavedStateHandle(snapshot)
        assertEquals(TranscriptMode.BILINGUAL, ShellViewModel(restoredHandle).uiState.value.transcriptMode)
        // The reconstructed state must not be another reference to the original handle.
        model.selectTranscriptMode(TranscriptMode.CHINESE)
        assertEquals(TranscriptMode.BILINGUAL, ShellViewModel(restoredHandle).uiState.value.transcriptMode)
    }

    @Test
    fun unknownStoredModeFallsBackToEnglish() {
        val handle = SavedStateHandle(mapOf("transcriptMode" to "invalid"))
        assertEquals(TranscriptMode.ENGLISH, ShellViewModel(handle).uiState.value.transcriptMode)
    }
}
