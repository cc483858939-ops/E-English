package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eenglish.listening.R
import com.eenglish.listening.ui.components.AudioControls
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.ui.components.QuestionCard
import com.eenglish.listening.viewmodel.PracticeUiState
import com.eenglish.listening.viewmodel.TranscriptMode

@Composable
fun TranscriptScreen(state: PracticeUiState, audio: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    mode: TranscriptMode, onModeChange: (TranscriptMode) -> Unit, onBack: () -> Unit,
    onSelect: (String, String) -> Unit) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val questions = state.questions
    val sessionId = state.attempt?.session?.id
    var questionNumber by rememberSaveable(sessionId, state.part?.id) {
        mutableIntStateOf(questions.firstOrNull()?.number ?: 0)
    }
    var expanded by rememberSaveable(sessionId, state.part?.id) { mutableStateOf(true) }
    var showJump by rememberSaveable(sessionId, state.part?.id) { mutableStateOf(false) }
    val index = questions.indexOfFirst { it.number == questionNumber }.coerceAtLeast(0)
    val question = questions.getOrNull(index)
    val transcriptScroll = rememberScrollState()
    var readingOffsetToRestore by remember { mutableStateOf<Int?>(null) }
    var cardHeaderHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(expanded) {
        readingOffsetToRestore?.let { offset ->
            // A child may briefly measure with its previous viewport during a resize.
            // Restore only after the new card and transcript bounds have been laid out.
            withFrameNanos { }
            transcriptScroll.scrollTo(offset)
            readingOffsetToRestore = null
        }
    }

    if (showJump && question != null) {
        AlertDialog(onDismissRequest = { showJump = false },
            title = { Text("跳转题号") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    questions.chunked(5).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { item ->
                                TextButton(onClick = { questionNumber = item.number; showJump = false },
                                    modifier = Modifier.weight(1f).testTag("jump-${item.number}")) {
                                    Text(item.number.toString())
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showJump = false }) { Text("关闭") } })
    }

    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag("transcript-header"), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_to_practice)) }
            Text(stringResource(R.string.transcript_title), style = MaterialTheme.typography.titleMedium)
        }
    }, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("transcript-player")) {
                AudioControls(audio, onToggle, onSeek, compact = true)
            }
            state.error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
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
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("intensive-content")) {
                val expandedHeight = maxHeight * 0.5f
                val headerHeight = if (cardHeaderHeightPx == 0) 48.dp else with(density) { cardHeaderHeightPx.toDp() }
                // Keep the scroll range unchanged on collapse, including when reading near the end.
                // Extra space after the transcript prevents the larger viewport from clamping its offset.
                val readingEndSpace = if (question != null && !expanded)
                    (expandedHeight - headerHeight).coerceAtLeast(0.dp) else 0.dp
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().testTag("transcript-body")
                            .verticalScroll(transcriptScroll)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + readingEndSpace),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val part = state.part
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
                    if (question != null) {
                        TranscriptQuestionPanel(question, state.answers[question.id], expanded, expandedHeight,
                            editable = !state.submitted && !state.submitting && state.attempt != null,
                            submitted = state.submitted, hasPrevious = index > 0, hasNext = index < questions.lastIndex,
                            onExpand = {
                                if (readingOffsetToRestore == null) readingOffsetToRestore = transcriptScroll.value
                                expanded = !expanded
                            },
                            onPrevious = { questionNumber = questions[index - 1].number },
                            onNext = { questionNumber = questions[index + 1].number },
                            onJump = { showJump = true }, onSelect = { onSelect(question.id, it) },
                            onHeaderSize = { cardHeaderHeightPx = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptQuestionPanel(question: Question, selected: String?, expanded: Boolean, expandedHeight: Dp,
    editable: Boolean, submitted: Boolean, hasPrevious: Boolean, hasNext: Boolean,
    onExpand: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onJump: () -> Unit,
    onSelect: (String) -> Unit, onHeaderSize: (Int) -> Unit) {
    val questionScroll = rememberScrollState()
    LaunchedEffect(question.id) { questionScroll.scrollTo(0) }
    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().testTag("transcript-question-panel")) {
        Column(Modifier.then(if (expanded) Modifier.height(expandedHeight) else Modifier)
            .padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).onSizeChanged { onHeaderSize(it.height) },
                verticalAlignment = Alignment.CenterVertically) {
                Text("Q.${question.number} · ${selected?.let { "已选 $it" } ?: "未作答"}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).testTag("transcript-question-summary"))
                TextButton(onClick = onExpand, modifier = Modifier.testTag("transcript-card-toggle")) {
                    Text(if (expanded) "收起" else "展开")
                }
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onPrevious, enabled = hasPrevious,
                        modifier = Modifier.testTag("transcript-previous")) { Text("上一题") }
                    TextButton(onClick = onJump, modifier = Modifier.testTag("transcript-jump")) { Text("跳转题号") }
                    TextButton(onClick = onNext, enabled = hasNext,
                        modifier = Modifier.testTag("transcript-next")) { Text("下一题") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).fillMaxWidth().testTag("transcript-question-body")
                    .verticalScroll(questionScroll).padding(horizontal = 4.dp)) {
                    if (submitted) Text("已提交 · 只读", style = MaterialTheme.typography.labelMedium)
                    QuestionCard(question, selected, editable, showResult = submitted, onSelect = onSelect)
                }
            }
        }
    }
}
