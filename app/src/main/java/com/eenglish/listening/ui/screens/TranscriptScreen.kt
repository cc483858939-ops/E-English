package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eenglish.listening.R
import com.eenglish.listening.ui.components.AudioControls
import com.eenglish.listening.audio.AudioState
import com.eenglish.listening.ui.components.ResizableQuestionPanel
import com.eenglish.listening.ui.components.AnnotatableText
import com.eenglish.listening.ui.components.LocalQuestionReview
import com.eenglish.listening.ui.components.ReviewInteraction
import com.eenglish.listening.ui.components.hasV4QuestionImage
import com.eenglish.listening.ui.components.PlaybackTranscriptContent
import com.eenglish.listening.ui.components.TranscriptPlaybackBounds
import com.eenglish.listening.ui.components.GlobalAnswerSheet
import com.eenglish.listening.ui.components.AnswerSheetButton
import com.eenglish.listening.ui.components.LocalQuestionJump
import com.eenglish.listening.ui.components.QuestionJump
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.gapfill.QuestionLayoutDisplayParser
import com.eenglish.listening.domain.gapfill.QuestionDisplayItem
import com.eenglish.listening.viewmodel.PracticeUiState
import com.eenglish.listening.viewmodel.TranscriptMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.first
import com.eenglish.listening.domain.grading.questionReviews

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptScreen(state: PracticeUiState, audio: AudioState, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    mode: TranscriptMode, onModeChange: (TranscriptMode) -> Unit, onBack: () -> Unit,
    onSelect: (String, String) -> Unit, onSeekBy: (Long) -> Unit = {}, onSpeed: (Float) -> Unit = {},
    onInlineEdit: (String, String) -> Unit = onSelect, onInlineCommit: (String) -> Unit = {},
    onFlushInline: () -> Unit = {}, onSubmit: (() -> Unit)? = null,
    pageState: TranscriptPageState = key(state.attempt?.session?.id, state.part?.id) {
        rememberTranscriptPageState(state.questions.firstOrNull()?.number ?: 0)
    }) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val questions = state.questions
    val displayItems = remember(questions, state.questionLayout) {
        QuestionLayoutDisplayParser.parse(questions, state.questionLayout?.groups.orEmpty())
    }
    var questionNumber by pageState.questionNumber
    var expanded by pageState.expanded
    var showJump by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var jumpSequence by remember { mutableIntStateOf(0) }
    var pendingJump by remember { mutableStateOf<QuestionJump?>(null) }
    val index = questions.indexOfFirst { it.number == questionNumber }.coerceAtLeast(0)
    val question = questions.getOrNull(index)
    val reviews = remember(questions, state.answers, state.submitted) {
        if (state.submitted) questionReviews(questions, state.answers) else emptyList()
    }
    val currentReview = reviews.firstOrNull { question?.id in it.questionIds }
    val reviewIndex = reviews.indexOf(currentReview)
    val reviewPositions = currentReview?.questionIds.orEmpty().map { id -> questions.indexOfFirst { it.id == id } + 1 }.sorted()
    val reviewProgress = reviewPositions.firstOrNull()?.let { first ->
        val last = reviewPositions.last()
        "${if (first == last) first.toString() else "$first–$last"}/${questions.size}"
    }
    val choiceReview = currentReview?.questions?.all { !it.isTextInput } == true
    val fillReview = currentReview?.questions?.all { it.isTextInput } == true
    var panelRatio by pageState.panelRatio
    var lastExpandedRatio by pageState.lastExpandedRatio
    val previousNumber = if (currentReview != null) reviews.getOrNull(reviewIndex - 1)?.questions?.firstOrNull()?.number
        else questions.getOrNull(index - 1)?.number
    val nextNumber = if (currentReview != null) reviews.getOrNull(reviewIndex + 1)?.questions?.firstOrNull()?.number
        else questions.getOrNull(index + 1)?.number
    var reviewAnswers by pageState.reviewAnswers
    val compactInput = WindowInsets.isImeVisible && expanded && question?.isTextInput == true && !state.submitted
    val largePanel = question != null && expanded && !compactInput && panelRatio >= 0.8f
    val activeV4Group = remember(state.questionLayoutV4, question?.id) {
        state.questionLayoutV4?.get("groups")?.jsonArray?.firstOrNull { element ->
            val ids = (element as? JsonObject)?.get("questionIds") as? kotlinx.serialization.json.JsonArray
            ids?.any { it.jsonPrimitive.content == question?.id } == true
        } as? JsonObject
    }
    val v4QuestionsById = remember(state.part) { state.part?.questions.orEmpty().associateBy { it.id } }
    val activeV4Questions = remember(activeV4Group, v4QuestionsById) {
        activeV4Group?.get("questionIds")?.jsonArray?.mapNotNull { v4QuestionsById[it.jsonPrimitive.content] }.orEmpty()
    }
    val activeGroup = displayItems.asSequence().filterIsInstance<QuestionDisplayItem.InlineGroup>()
        .firstOrNull { item -> item.group.questions.any { it.id == question?.id } }?.group
    val activeStructured = displayItems.asSequence().filterIsInstance<QuestionDisplayItem.StructuredLayout>()
        .firstOrNull { item -> item.questions.any { it.id == question?.id } }
    val activeImageGroup = displayItems.asSequence().filterIsInstance<QuestionDisplayItem.ImageGroup>()
        .firstOrNull { item -> item.group.questions.any { it.id == question?.id } }?.group
    val activeMatching = displayItems.asSequence().filterIsInstance<QuestionDisplayItem.SharedMatching>()
        .firstOrNull { item -> item.group.questions.any { it.id == question?.id } }?.group
    val activeMultipleChoice = displayItems.asSequence().filterIsInstance<QuestionDisplayItem.SharedMultipleChoice>()
        .firstOrNull { item -> item.group.questions.any { it.id == question?.id } }?.group
    val activeTextFallback = displayItems.asSequence().filterIsInstance<QuestionDisplayItem.SharedTextFallback>()
        .firstOrNull { item -> item.group.questions.any { it.id == question?.id } }?.group
    val transcriptScroll = pageState.transcriptScroll
    val boundTimeline = remember(state.part, state.transcriptTimeline) {
        state.part?.let { part -> state.transcriptTimeline?.takeIf { it.matches(part) } }
    }
    val timeline = remember(boundTimeline, audio.durationMs) {
        if (audio.durationMs == 0L) boundTimeline else boundTimeline?.forDuration(audio.durationMs)
    }
    var following by pageState.followPlayback
    var seekSequence by remember(pageState) { mutableIntStateOf(0) }
    var handledSeek by remember(pageState) { mutableIntStateOf(0) }
    var pendingSeek by remember(pageState) { mutableStateOf<Long?>(null) }
    var playbackBounds by remember(pageState, mode) { mutableStateOf<TranscriptPlaybackBounds?>(null) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val effectivePosition = pendingSeek ?: audio.positionMs
    val activeSegment = timeline?.activeIndex(effectivePosition)
    val anchorSegment = activeSegment ?: if (seekSequence != handledSeek) timeline?.nearestIndex(effectivePosition) else null
    fun locatePlayback(position: Long) {
        following = true
        pendingSeek = position.coerceIn(0L, audio.durationMs.coerceAtLeast(0L))
        seekSequence++
    }
    LaunchedEffect(audio.positionMs, pendingSeek) {
        pendingSeek?.let { if (kotlin.math.abs(audio.positionMs - it) < 1000) pendingSeek = null }
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            kotlinx.coroutines.delay(1000)
            pendingSeek = null
        }
    }
    LaunchedEffect(anchorSegment, playbackBounds, mode, following, audio.isPlaying, seekSequence, viewportHeight) {
        val bounds = playbackBounds ?: return@LaunchedEffect
        val index = anchorSegment ?: return@LaunchedEffect
        if (!following || bounds.index != index || viewportHeight <= 0) return@LaunchedEffect
        val explicit = seekSequence != handledSeek
        val key = "${mode.name}:$index"
        if (!explicit && pageState.lastFollowedSegment == key) return@LaunchedEffect
        val top = transcriptScroll.value
        if (bounds.top < top || bounds.bottom > top + viewportHeight) {
            transcriptScroll.scrollTo((bounds.top - viewportHeight / 4).coerceAtLeast(0))
        }
        pageState.lastFollowedSegment = key
        handledSeek = seekSequence
    }
    val questionGroupKey = activeV4Group?.get("groupId")?.jsonPrimitive?.content
        ?: activeGroup?.questions?.first()?.id ?: activeStructured?.group?.groupId
        ?: activeImageGroup?.questions?.first()?.id ?: activeMatching?.questions?.first()?.id
        ?: activeMultipleChoice?.groupId ?: activeTextFallback?.questions?.first()?.id ?: question?.id
    val questionScroll = pageState.questionScroll
    val panelScroll = if (choiceReview || currentReview != null && !fillReview && reviewAnswers) pageState.reviewScroll else questionScroll
    LaunchedEffect(currentReview?.id) {
        if (pageState.reviewId != currentReview?.id) {
            pageState.reviewScroll.scrollTo(0)
            pageState.reviewId = currentReview?.id
        }
    }
    LaunchedEffect(questionGroupKey) {
        // Reset only when the selected group changes, not when the route is reopened.
        if (pageState.questionGroupKey != questionGroupKey) {
            questionScroll.scrollTo(0)
            pageState.questionGroupKey = questionGroupKey
        }
    }
    var readingOffsetToRestore by remember { mutableStateOf<Int?>(null) }
    var questionOffsetToRestore by remember { mutableStateOf<Int?>(null) }
    var resizeReadingAnchor by remember { mutableStateOf<Int?>(null) }
    var resizeQuestionAnchor by remember { mutableStateOf<Int?>(null) }
    var restoreRequest by remember { mutableIntStateOf(0) }
    var cardHeaderHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var imeReadingOffset by remember(pageState) { mutableStateOf<Int?>(null) }
    val imeInsets = WindowInsets.ime
    LaunchedEffect(compactInput) {
        if (compactInput) imeReadingOffset = transcriptScroll.value
        else imeReadingOffset?.let {
            snapshotFlow { imeInsets.getBottom(density) }.first { bottom -> bottom == 0 }
            withFrameNanos { }
            transcriptScroll.scrollTo(it)
            imeReadingOffset = null
        }
    }
    LaunchedEffect(restoreRequest) {
        // Only restore after a resize gesture or toggle, never on every drag frame/recomposition.
        if (readingOffsetToRestore == null && questionOffsetToRestore == null) return@LaunchedEffect
        withFrameNanos { }
        readingOffsetToRestore?.let { if (!transcriptScroll.isScrollInProgress) transcriptScroll.scrollTo(it) }
        questionOffsetToRestore?.let { if (!panelScroll.isScrollInProgress) panelScroll.scrollTo(it) }
        readingOffsetToRestore = null
        questionOffsetToRestore = null
    }

    if (showJump) GlobalAnswerSheet(questions, state.questionLayoutV4, state.answers + state.inlineDrafts,
        question?.number, state.submitted, state.saving || state.submitting,
        onDismiss = { showJump = false }, onSubmit = onSubmit?.let { submit ->
            { showJump = false; onFlushInline(); submit() }
        }, onQuestion = { number ->
            questionNumber = number; showJump = false
            expanded = true; panelRatio = lastExpandedRatio
            jumpSequence++
            val sequence = jumpSequence
            pendingJump = QuestionJump(number, sequence) {
                if (pendingJump?.sequence == sequence) pendingJump = null
            }
        })

    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag("transcript-header"), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onFlushInline(); onBack() }) { Text(stringResource(R.string.back_to_practice)) }
            Text(stringResource(R.string.transcript_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AnswerSheetButton(state.attempt != null && questions.isNotEmpty()) {
                focusManager.clearFocus(); onFlushInline(); showJump = true
            }
        }
    }, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("transcript-player")) {
                AudioControls(audio, onToggle, { position -> locatePlayback(position); onSeek(position) }, compact = true,
                    onSeekBy = { delta -> locatePlayback(audio.positionMs + delta); onSeekBy(delta) }, onSpeed = onSpeed,
                    singleRow = compactInput || largePanel)
            }
            state.error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (!compactInput && !largePanel) ScrollableTabRow(selectedTabIndex = mode.ordinal, edgePadding = 0.dp,
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
            if (timeline != null && !compactInput && !largePanel) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .padding(horizontal = 16.dp).testTag("transcript-follow-bar")
                .semantics { stateDescription = activeSegment?.let { "播放段落:$it" } ?: "播放段落:无" },
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (following) "原文跟随播放" else "自由阅读", style = MaterialTheme.typography.labelMedium)
                    if (audio.ready && audio.error == null) Text("点击文字定位音频", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { locatePlayback(audio.positionMs) }, enabled = !following,
                    modifier = Modifier.testTag("transcript-follow-resume")) { Text(if (following) "跟随中" else "回到当前播放") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("intensive-content")) {
                val headerHeight = if (cardHeaderHeightPx == 0) 48.dp else with(density) { cardHeaderHeightPx.toDp() }
                // On unusually short viewports, navigation joins the scrollable question body.
                val reviewTabsHeight = if (currentReview != null && !choiceReview && !fillReview) 48.dp else 0.dp
                val fixedNavigation = !compactInput && maxHeight >= headerHeight + reviewTabsHeight + 48.dp + 1.dp + 96.dp
                val chromeHeight = headerHeight + reviewTabsHeight + 1.dp + if (fixedNavigation) 48.dp else 0.dp
                val minimumViewport = ((maxHeight - chromeHeight) / 2f).coerceIn(1.dp, 48.dp)
                val minimumPanel = (maxHeight * 0.25f).coerceAtLeast(chromeHeight + minimumViewport)
                    .coerceAtMost((maxHeight - minimumViewport).coerceAtLeast(headerHeight))
                // Every question type shares the large-card range. Keep the established IME viewport budget.
                val minimumReading = if (compactInput) minimumViewport else minOf(24.dp, maxHeight * 0.08f)
                val maximumPanel = (maxHeight * if (compactInput) 0.75f else 0.96f).coerceAtMost(maxHeight - minimumReading)
                    .coerceAtLeast(minimumPanel)
                val panelHeight = when {
                    question == null -> 0.dp
                    !expanded -> headerHeight
                    else -> (maxHeight * if (compactInput) 0.65f else panelRatio).coerceIn(minimumPanel, maximumPanel)
                }
                val readingHeight = (maxHeight - panelHeight).coerceAtLeast(1.dp)
                // Pair an explicit viewport height with tail padding. Both scroll ranges remain
                // constant throughout a drag, so growing viewports cannot truncate end offsets.
                // Keep the scroll limit stable when large-card mode changes the player and tabs.
                val readingEndSpace = if (question != null) {
                    (if (compactInput) maximumPanel - panelHeight else readingHeight - minimumReading)
                        .coerceAtLeast(0.dp)
                } else 0.dp
                val availablePx = with(density) { maxHeight.toPx() }
                val availableHeight = maxHeight
                val minimumRatio = minimumPanel / maxHeight
                val maximumRatio = maximumPanel / maxHeight
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.height(readingHeight).fillMaxWidth().testTag("transcript-body")
                            .onGloballyPositioned { viewportTop = it.positionInRoot().y; viewportHeight = it.size.height }
                            .pointerInput(pageState, timeline != null) {
                                if (timeline != null) awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.any { it.pressed && !it.previousPressed }) following = false
                                    }
                                }
                            }
                            .verticalScroll(transcriptScroll)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + readingEndSpace),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val part = state.part
                        if (part != null) {
                            Text(part.title, style = MaterialTheme.typography.headlineSmall)
                            if (part.transcript.english.isBlank()) Text("英文原文资料缺失")
                            if (part.transcript.chinese.isBlank()) Text("中文翻译资料缺失")
                            if (timeline != null) PlaybackTranscriptContent(part.transcript, mode, activeSegment, anchorSegment,
                                transcriptScroll, viewportTop, onBounds = { playbackBounds = it },
                                onUserInteraction = { following = false },
                                seekableIndices = if (audio.ready && audio.error == null)
                                    timeline.segments.indices.filter { timeline.segments[it] != null }.toSet() else emptySet(),
                                onSegmentTap = { index -> timeline.segments.getOrNull(index)?.let { cue ->
                                    if (audio.ready && audio.error == null) {
                                        locatePlayback(cue.startMs)
                                        onSeek(cue.startMs)
                                    }
                                } }) else when (mode) {
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
                        CompositionLocalProvider(LocalQuestionJump provides pendingJump,
                            LocalQuestionReview provides currentReview?.let { item ->
                            ReviewInteraction(reviews, item.id, { id ->
                                reviews.firstOrNull { it.id == id }?.questions?.firstOrNull()?.let { questionNumber = it.number }
                            }, locateActive = pageState.locatedQuestionNumber != questionNumber &&
                                activeV4Group?.let { !hasV4QuestionImage(it) } != false,
                                onLocated = { pageState.locatedQuestionNumber = questionNumber })
                        }) {
                        ResizableQuestionPanel(question, state.answers[question.id], expanded, panelHeight, minimumPanel,
                            headerHeight = headerHeight, fixedNavigation = fixedNavigation, questionScroll = questionScroll,
                            heightRatio = panelHeight / availableHeight, ratioRange = minimumRatio..maximumRatio,
                            editable = !state.submitted && !state.submitting && state.attempt != null,
                            submitted = state.submitted, hasPrevious = previousNumber != null, hasNext = nextNumber != null,
                            onExpand = {
                                if (readingOffsetToRestore == null) readingOffsetToRestore = transcriptScroll.value
                                questionOffsetToRestore = panelScroll.value
                                if (expanded) {
                                    lastExpandedRatio = panelHeight / availableHeight
                                    panelRatio = headerHeight / availableHeight
                                } else panelRatio = lastExpandedRatio
                                expanded = !expanded
                                restoreRequest++
                            },
                            onPrevious = { onFlushInline(); previousNumber?.let { questionNumber = it } },
                            onNext = { onFlushInline(); nextNumber?.let { questionNumber = it } },
                            onJump = { focusManager.clearFocus(); onFlushInline(); showJump = true }, onSelect = { onSelect(question.id, it) },
                            inlineGroup = activeGroup, structuredGroup = activeStructured?.group,
                            structuredQuestions = activeStructured?.questions.orEmpty(),
                            answers = state.answers + state.inlineDrafts,
                            saving = state.saving, dirty = state.inlineDrafts.isNotEmpty(),
                            onGroupSelect = onSelect, onGroupEdit = onInlineEdit,
                            onGroupCommit = onInlineCommit, onActivate = { questionNumber = it },
                            imageGroup = activeImageGroup,
                            sharedMatchingGroup = activeMatching,
                            sharedMultipleChoiceGroup = activeMultipleChoice,
                            sharedTextFallbackGroup = activeTextFallback,
                            v4Group = activeV4Group,
                            v4GroupQuestions = activeV4Questions,
                            reviewItem = currentReview,
                            reviewProgress = reviewProgress,
                            reviewAnswers = reviewAnswers, onReviewAnswers = { reviewAnswers = it },
                            reviewScroll = pageState.reviewScroll,
                            compact = compactInput,
                            onHeaderSize = { cardHeaderHeightPx = it },
                            onResizeStarted = {
                                resizeReadingAnchor = transcriptScroll.value
                                resizeQuestionAnchor = panelScroll.value
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
                                questionOffsetToRestore = panelScroll.value
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
}

/** Saved by the listening shell so popping the transcript does not discard reading state. */
class TranscriptPageState(firstQuestionNumber: Int, readingOffset: Int = 0, questionOffset: Int = 0, reviewOffset: Int = 0) {
    val questionNumber = mutableIntStateOf(firstQuestionNumber)
    val expanded = mutableStateOf(true)
    val panelRatio = mutableFloatStateOf(0.45f)
    val lastExpandedRatio = mutableFloatStateOf(0.45f)
    val transcriptScroll = ScrollState(readingOffset)
    val questionScroll = ScrollState(questionOffset)
    val reviewAnswers = mutableStateOf(false)
    val reviewScroll = ScrollState(reviewOffset)
    val followPlayback = mutableStateOf(true)
    var lastFollowedSegment: String? = null
    var reviewId: String? = null
    var locatedQuestionNumber: Int? = null
    var questionGroupKey: String? = null

    companion object {
        val Saver = listSaver<TranscriptPageState, Any?>(
            save = { listOf(it.questionNumber.intValue, it.expanded.value,
                it.panelRatio.floatValue, it.lastExpandedRatio.floatValue,
                it.transcriptScroll.value, it.questionScroll.value, it.questionGroupKey,
                it.reviewAnswers.value, it.reviewScroll.value, it.reviewId, it.locatedQuestionNumber,
                it.followPlayback.value, it.lastFollowedSegment) },
            restore = { values ->
                TranscriptPageState(values[0] as Int, values[4] as Int, values[5] as Int, values.getOrNull(8) as? Int ?: 0).apply {
                    expanded.value = values[1] as Boolean
                    panelRatio.floatValue = values[2] as Float
                    lastExpandedRatio.floatValue = values[3] as Float
                    questionGroupKey = values[6] as String?
                    reviewAnswers.value = values.getOrNull(7) as? Boolean ?: false
                    reviewId = values.getOrNull(9) as? String
                    locatedQuestionNumber = values.getOrNull(10) as? Int
                    followPlayback.value = values.getOrNull(11) as? Boolean ?: true
                    lastFollowedSegment = values.getOrNull(12) as? String
                }
            },
        )
    }
}

@Composable
fun rememberTranscriptPageState(firstQuestionNumber: Int): TranscriptPageState =
    rememberSaveable(saver = TranscriptPageState.Saver) { TranscriptPageState(firstQuestionNumber) }
