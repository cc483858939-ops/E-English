package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eenglish.listening.R
import com.eenglish.listening.ui.components.AudioControls
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.ui.components.PageHeader
import com.eenglish.listening.viewmodel.TranscriptMode

@Composable
fun TranscriptScreen(part: ListeningPart?, audio: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    mode: TranscriptMode, onModeChange: (TranscriptMode) -> Unit, onBack: () -> Unit) {
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp)) {
            PageHeader(stringResource(R.string.transcript_title), onBack)
            ScrollableTabRow(selectedTabIndex = mode.ordinal, edgePadding = 0.dp) {
                TranscriptMode.entries.forEach { item ->
                    val label = when (item) {
                        TranscriptMode.ENGLISH -> R.string.transcript_english
                        TranscriptMode.CHINESE -> R.string.transcript_chinese
                        TranscriptMode.BILINGUAL -> R.string.transcript_bilingual
                    }
                    Tab(selected = mode == item, onClick = { onModeChange(item) }, text = { Text(stringResource(label)) })
                }
            }
            Column(
                Modifier.weight(1f).testTag("transcript-body").verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (part != null) {
                    Text(part.title, style = MaterialTheme.typography.headlineSmall)
                    when (mode) {
                        TranscriptMode.ENGLISH -> Text(part.transcript.english, style = MaterialTheme.typography.bodyLarge)
                        TranscriptMode.CHINESE -> Text(part.transcript.chinese, style = MaterialTheme.typography.bodyLarge)
                        TranscriptMode.BILINGUAL -> part.transcript.segments.forEach { segment ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (segment.english.isNotBlank()) Text(segment.english, style = MaterialTheme.typography.bodyLarge)
                                if (segment.chinese.isNotBlank()) Text(segment.chinese, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else Text("暂无已导入原文")
            }
            AudioControls(audio, onToggle, onSeek)
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_to_practice)) }
        }
    }
}
