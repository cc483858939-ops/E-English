package com.eenglish.listening.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eenglish.listening.ListeningApplication
import com.eenglish.listening.domain.annotation.AnnotationChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AnnotationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ListeningApplication).annotations
    val highlights = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val commands = Channel<Pair<List<AnnotationChange>, Boolean>>(Channel.UNLIMITED)
    init {
        viewModelScope.launch {
            for ((changes, add) in commands) {
                try { repository.change(changes, add); mutableError.value = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableError.value = "标记保存失败，请重新选择文字后重试。" }
            }
        }
    }
    fun change(changes: List<AnnotationChange>, add: Boolean) { check(commands.trySend(changes to add).isSuccess) }
    fun dismissError() { mutableError.value = null }
}
