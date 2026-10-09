package com.eenglish.listening.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.eenglish.listening.ListeningApplication
import com.eenglish.listening.audio.ListeningAudioController
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.domain.grading.Grader
import android.net.Uri
import android.provider.OpenableColumns
import com.eenglish.listening.data.repository.BatchImportUiState
import com.eenglish.listening.data.repository.BatchPackImporter
import com.eenglish.listening.data.repository.PackImportSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PracticeUiState(
    val loading: Boolean = true,
    val parts: List<PartSummary> = emptyList(),
    val part: ListeningPart? = null,
    val attempt: PracticeAttempt? = null,
    val history: List<PracticeAttempt> = emptyList(),
    val answers: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val submitting: Boolean = false,
    val confirmMissing: Int? = null,
    val error: String? = null,
    val importMessage: String? = null,
    val batchImport: BatchImportUiState? = null,
) {
    val questions: List<Question> get() = attempt?.questions ?: part?.questions.orEmpty()
    val submitted: Boolean get() = attempt?.session?.status == SessionStatus.SUBMITTED
}

class PracticeViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as ListeningApplication
    private val repository = app.practices
    val audio = ListeningAudioController(application, viewModelScope)
    private val mutableState = MutableStateFlow(PracticeUiState())
    val uiState = mutableState.asStateFlow()
    // Preserve event order even for rapid selection changes; display only committed answers.
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var pendingOperations = 0

    init {
        viewModelScope.launch {
            for (action in commands) {
                try { action(); refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableState.update { it.copy(error = "本地保存失败，请重试。页面只显示已保存的答案。") }
                } finally {
                    pendingOperations--
                    mutableState.update { it.copy(saving = pendingOperations > 0, submitting = false) }
                }
            }
        }
        viewModelScope.launch {
            try {
                val parts = app.parts.loadParts()
                val selected = parts.firstOrNull { it.id == savedStateHandle.get<String>(PART_KEY) } ?: parts.firstOrNull()
                val part = selected?.let { app.parts.loadPart(it.id) }
                mutableState.update { it.copy(parts = parts, part = part) }
                part?.let { audio.load(app.parts.audioPath(it)) }
                repository.observeAll().collect { attempts -> reconcile(attempts) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = "题库或记录校验失败，请检查本地数据") }
            }
        }
    }
    private fun reconcile(attempts: List<PracticeAttempt>) {
        val state = mutableState.value
        val matching = attempts.filter { it.session.partId == state.part?.id }
        val selectedId = savedStateHandle.get<String>(SESSION_KEY)
        val attempt = matching.firstOrNull { it.session.id == selectedId } ?: matching.firstOrNull()
        savedStateHandle[SESSION_KEY] = attempt?.session?.id
        mutableState.update { it.copy(loading = false, history = attempts, attempt = attempt,
            answers = attempt?.selectedAnswers.orEmpty()) }
    }
    private suspend fun refresh() { reconcile(repository.all()) }
    private fun enqueue(action: suspend () -> Unit) {
        pendingOperations++
        mutableState.update { it.copy(saving = true, error = null) }
        check(commands.trySend(action).isSuccess)
    }
    fun openPart(partId: String) {
        savedStateHandle[SESSION_KEY] = null
        savedStateHandle[PART_KEY] = partId
        audio.pause()
        mutableState.update { it.copy(part = null, attempt = null, answers = emptyMap(), loading = true, confirmMissing = null) }
        enqueue {
            val part = app.parts.loadPart(partId)
            mutableState.update { it.copy(part = part) }
            audio.load(app.parts.audioPath(part))
            savedStateHandle[SESSION_KEY] = repository.resumeOrCreate(part)
        }
    }
    fun importPack(uri: Uri) = importPacks(listOf(uri))

    fun importPacks(uris: List<Uri>) {
        if (uris.isEmpty() || mutableState.value.saving || mutableState.value.batchImport != null) return
        // One command contains the whole queue. Set running synchronously to block another launch.
        mutableState.update { it.copy(batchImport = BatchImportUiState(uris.size), importMessage = null) }
        enqueue {
            val sources = uris.mapIndexed { position, uri ->
                PackImportSource(name = {
                    val displayName = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    }
                    displayName?.filterNot { it.isISOControl() }?.take(120)?.takeIf { it.isNotBlank() }
                        ?: "资料包 ${position + 1}"
                }, open = {
                    app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Document unavailable")
                })
            }
            BatchPackImporter(app.parts::loadParts, app.parts::importPack).run(sources,
                onState = { batch -> mutableState.update { it.copy(batchImport = batch) } },
                onParts = { parts -> mutableState.update { it.copy(parts = parts) } })
        }
    }
    fun dismissBatchImport() {
        val batch = mutableState.value.batchImport ?: return
        if (batch.isRunning) return
        mutableState.update { it.copy(batchImport = null,
            importMessage = "导入完成：新增 ${batch.addedParts} 个 Part，已存在 ${batch.existingParts} 个 Part，失败 ${batch.failed} 个资料包") }
    }
    fun selectAnswer(questionId: String, optionId: String) {
        val state = mutableState.value
        val session = state.attempt?.session ?: return
        if (state.submitted || state.submitting) return
        enqueue { repository.saveAnswer(session.id, questionId, optionId) }
    }
    fun requestSubmit() {
        val state = mutableState.value
        if (state.saving || state.submitted || state.submitting || state.attempt == null) return
        val missing = Grader.missingCount(state.questions, state.answers)
        if (missing > 0) mutableState.update { it.copy(confirmMissing = missing) } else confirmSubmit()
    }
    fun dismissSubmit() { mutableState.update { it.copy(confirmMissing = null) } }
    fun confirmSubmit() {
        val state = mutableState.value
        val id = state.attempt?.session?.id ?: return
        if (state.saving || state.submitted || state.submitting) return
        mutableState.update { it.copy(submitting = true, confirmMissing = null) }
        enqueue { repository.submit(id) }
    }
    fun newPractice() {
        val state = mutableState.value
        val part = state.part ?: return
        if (state.saving || !state.submitted) return
        enqueue {
            savedStateHandle[SESSION_KEY] = repository.startNew(part)
            audio.pause()
            audio.seekTo(0)
        }
    }
    fun viewHistory(sessionId: String) {
        if (mutableState.value.saving) return
        val attempt = mutableState.value.history.firstOrNull { it.session.id == sessionId } ?: return
        if (attempt.session.partId != mutableState.value.part?.id) return
        savedStateHandle[SESSION_KEY] = sessionId
        reconcile(mutableState.value.history)
    }
    override fun onCleared() { commands.close(); audio.release(); super.onCleared() }
    private companion object {
        const val SESSION_KEY = "practiceSessionId"
        const val PART_KEY = "practicePartId"
    }
}
