package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.viewmodel.PracticeUiState

@Composable
fun PracticeScreen(state: PracticeUiState, audio: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onSelect: (String, String) -> Unit, onBack: () -> Unit, onViewTranscript: () -> Unit) {
    Scaffold(topBar = { PageHeader("听力答题", onBack) }, bottomBar = {
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Text("提交答案")
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("practice-list"), contentPadding = PaddingValues(20.dp)) {
            val part = state.part
            if (part != null) {
                item {
                    Text(part.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(20.dp))
                    AudioControls(audio, onToggle, onSeek)
                    TextButton(onClick = onViewTranscript) { Text("查看原文") }
                    Text(part.instructions, style = MaterialTheme.typography.titleMedium)
                }
                items(part.questions, key = { it.id }) { question ->
                    QuestionCard(question, state.answers[question.id], true, onSelect = { onSelect(question.id, it) })
                }
            } else item { Text("暂无已导入试题") }
        }
    }
}
