package com.eenglish.listening.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eenglish.listening.audio.ListeningAudioController
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.domain.model.ListeningPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PracticeUiState(
    val loading: Boolean = true,
    val parts: List<ListeningPart> = emptyList(),
    val part: ListeningPart? = null,
    val answers: Map<String, String> = emptyMap(),
    val error: String? = null,
)

class PracticeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PartRepository(application.assets)
    val audio = ListeningAudioController(application, viewModelScope)
    private val mutableState = MutableStateFlow(PracticeUiState())
    val uiState = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val parts = repository.loadParts()
                mutableState.update { it.copy(loading = false, parts = parts, part = parts.firstOrNull()) }
                parts.firstOrNull()?.let { audio.load(it.audioPath) }
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = "题库校验失败，请重新导入本地资源") }
            }
        }
    }
    fun openPart(part: ListeningPart) {
        mutableState.update { it.copy(part = part) }
        audio.load(part.audioPath)
    }
    fun selectAnswer(questionId: String, optionId: String) {
        val question = mutableState.value.part?.questions?.find { it.id == questionId } ?: return
        require(question.options.any { it.id == optionId })
        mutableState.update { it.copy(answers = it.answers + (questionId to optionId)) }
    }
    override fun onCleared() { audio.release(); super.onCleared() }
}
