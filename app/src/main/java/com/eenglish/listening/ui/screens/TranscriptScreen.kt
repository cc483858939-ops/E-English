package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eenglish.listening.R
import com.eenglish.listening.ui.components.AudioControls
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.ui.components.ResizableQuestionPanel
import com.eenglish.listening.ui.components.AnnotatableText
import com.eenglish.listening.domain.annotation.AnnotationDocument
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
    var panelRatio by rememberSaveable(sessionId, state.part?.id) { mutableFloatStateOf(0.45f) }
    var lastExpandedRatio by rememberSaveable(sessionId, state.part?.id) { mutableFloatStateOf(0.45f) }
    var showJump by rememberSaveable(sessionId, state.part?.id) { mutableStateOf(false) }
    val index = questions.indexOfFirst { it.number == questionNumber }.coerceAtLeast(0)
    val question = questions.getOrNull(index)
    val transcriptScroll = rememberScrollState()
    val questionScroll = rememberScrollState()
    var readingOffsetToRestore by remember { mutableStateOf<Int?>(null) }
    var questionOffsetToRestore by remember { mutableStateOf<Int?>(null) }
    var resizeReadingAnchor by remember { mutableStateOf<Int?>(null) }
    var resizeQuestionAnchor by remember { mutableStateOf<Int?>(null) }
    var restoreRequest by remember { mutableIntStateOf(0) }
    var cardHeaderHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(question?.id) { questionScroll.scrollTo(0) }
    LaunchedEffect(restoreRequest) {
        // Only restore after a resize gesture or toggle, never on every drag frame/recomposition.
        if (readingOffsetToRestore == null && questionOffsetToRestore == null) return@LaunchedEffect
        withFrameNanos { }
        readingOffsetToRestore?.let { if (!transcriptScroll.isScrollInProgress) transcriptScroll.scrollTo(it) }
        questionOffsetToRestore?.let { if (!questionScroll.isScrollInProgress) questionScroll.scrollTo(it) }
        readingOffsetToRestore = null
        questionOffsetToRestore = null
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
            ScrollableTabRow(selectedTabIndex = mode.ordinal, edgePadding = 0.dp,
                modifier = Modifier.testTag("transcript-language-tabs")) {
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
                val headerHeight = if (cardHeaderHeightPx == 0) 48.dp else with(density) { cardHeaderHeightPx.toDp() }
                // On unusually short viewports, navigation joins the scrollable question body.
                val fixedNavigation = maxHeight >= headerHeight + 48.dp + 1.dp + 96.dp
                val chromeHeight = headerHeight + 1.dp + if (fixedNavigation) 48.dp else 0.dp
                val minimumViewport = ((maxHeight - chromeHeight) / 2f).coerceIn(1.dp, 48.dp)
                val minimumPanel = (maxHeight * 0.25f).coerceAtLeast(chromeHeight + minimumViewport)
                    .coerceAtMost((maxHeight - minimumViewport).coerceAtLeast(headerHeight))
                val maximumPanel = (maxHeight * 0.75f).coerceAtMost(maxHeight - minimumViewport)
                    .coerceAtLeast(minimumPanel)
                val panelHeight = when {
                    question == null -> 0.dp
                    !expanded -> headerHeight
                    else -> (maxHeight * panelRatio).coerceIn(minimumPanel, maximumPanel)
                }
                val readingHeight = (maxHeight - panelHeight).coerceAtLeast(1.dp)
                // Pair an explicit viewport height with tail padding. Both scroll ranges remain
                // constant throughout a drag, so growing viewports cannot truncate end offsets.
                val readingEndSpace = if (question != null) (maximumPanel - panelHeight).coerceAtLeast(0.dp) else 0.dp
                val availablePx = with(density) { maxHeight.toPx() }
                val availableHeight = maxHeight
                val minimumRatio = minimumPanel / maxHeight
                val maximumRatio = maximumPanel / maxHeight
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.height(readingHeight).fillMaxWidth().testTag("transcript-body")
                            .verticalScroll(transcriptScroll)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + readingEndSpace),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val part = state.part
                        if (part != null) {
                            Text(part.title, style = MaterialTheme.typography.headlineSmall)
                            if (part.transcript.english.isBlank()) Text("英文原文资料缺失")
                            if (part.transcript.chinese.isBlank()) Text("中文翻译资料缺失")
                            when (mode) {
                                TranscriptMode.ENGLISH -> AnnotatableText(remember(part.transcript) {
                                    AnnotationDocument.transcript(part.transcript, "en") }, viewTag = "transcript-en")
                                TranscriptMode.CHINESE -> AnnotatableText(remember(part.transcript) {
                                    AnnotationDocument.transcript(part.transcript, "zh") }, viewTag = "transcript-zh")
                                TranscriptMode.BILINGUAL -> {
                                  Text("双语模式按语言段选择；跨段选择请切换英文或中文。", style = MaterialTheme.typography.labelSmall)
                                  part.transcript.segments.forEachIndexed { index, segment ->
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (segment.english.isNotBlank()) AnnotatableText(remember(segment.english, index) {
                                            AnnotationDocument.single(segment.english.trim(), "transcript", "segment:$index", "en") },
                                            viewTag = "transcript-en-$index")
                                        if (segment.chinese.isNotBlank()) AnnotatableText(remember(segment.chinese, index) {
                                            AnnotationDocument.single(segment.chinese.trim(), "transcript", "segment:$index", "zh") },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, viewTag = "transcript-zh-$index")
                                    }
                                  }
                                }
                            }
                        } else Text("暂无已导入原文")
                    }
                    if (question != null) {
                        ResizableQuestionPanel(question, state.answers[question.id], expanded, panelHeight, minimumPanel,
                            headerHeight = headerHeight, fixedNavigation = fixedNavigation, questionScroll = questionScroll,
                            heightRatio = panelHeight / availableHeight, ratioRange = minimumRatio..maximumRatio,
                            editable = !state.submitted && !state.submitting && state.attempt != null,
                            submitted = state.submitted, hasPrevious = index > 0, hasNext = index < questions.lastIndex,
                            onExpand = {
                                if (readingOffsetToRestore == null) readingOffsetToRestore = transcriptScroll.value
                                questionOffsetToRestore = questionScroll.value
                                if (expanded) {
                                    lastExpandedRatio = panelHeight / availableHeight
                                    panelRatio = headerHeight / availableHeight
                                } else panelRatio = lastExpandedRatio
                                expanded = !expanded
                                restoreRequest++
                            },
                            onPrevious = { questionNumber = questions[index - 1].number },
                            onNext = { questionNumber = questions[index + 1].number },
                            onJump = { showJump = true }, onSelect = { onSelect(question.id, it) },
                            onHeaderSize = { cardHeaderHeightPx = it },
                            onResizeStarted = {
                                resizeReadingAnchor = transcriptScroll.value
                                resizeQuestionAnchor = questionScroll.value
                            },
                            onResizeDelta = { delta ->
                                panelRatio = (panelRatio.coerceIn(minimumRatio, maximumRatio) - delta / availablePx)
                                    .coerceIn(minimumRatio, maximumRatio)
                                lastExpandedRatio = panelRatio
                            },
                            onResizeStopped = {
                                readingOffsetToRestore = resizeReadingAnchor
                                questionOffsetToRestore = resizeQuestionAnchor
                                resizeReadingAnchor = null
                                resizeQuestionAnchor = null
                                restoreRequest++
                            },
                            onSetRatio = { ratio ->
                                readingOffsetToRestore = transcriptScroll.value
                                questionOffsetToRestore = questionScroll.value
                                panelRatio = ratio.coerceIn(minimumRatio, maximumRatio)
                                lastExpandedRatio = panelRatio
                                restoreRequest++
                            })
                    }
                }
            }
        }
    }
}
