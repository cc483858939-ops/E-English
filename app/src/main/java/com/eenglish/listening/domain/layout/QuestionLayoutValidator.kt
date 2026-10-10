package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.model.LayoutBlock
import com.eenglish.listening.domain.model.LayoutBlockSource
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionLayoutDocument
import com.eenglish.listening.domain.model.QuestionLayoutGroup
import com.eenglish.listening.domain.model.ListeningPart
import java.security.MessageDigest

/** Cross-validates display metadata against immutable Part JSON using Android UTF-16 offsets. */
object QuestionLayoutValidator {
    private val shaPattern = Regex("[a-f0-9]{64}")
    private val partPattern = Regex("cambridge-(?:[5-9]|1[0-9]|2[01])-test-[1-4]-part-[1-4]")
    private val groupIdPattern = Regex("[a-z0-9-]+-layout-[a-z0-9-]{1,80}")
    private val kinds = setOf("summaryCompletion", "notesCompletion", "formCompletion",
        "sentenceCompletion", "linearTextCompletion", "shortAnswerInline")
    private const val MAX_GROUPS = 64
    private const val MAX_BLOCKS_PER_GROUP = 512
    private const val MAX_TOTAL_BLOCKS = 2_048
    private const val MAX_DEPTH = 6
    private const val MAX_TEXT = 100_000
    private const val MAX_TOTAL_TEXT = 1_000_000
    private const val MAX_LOCATOR = 512

    fun validate(layout: QuestionLayoutDocument, part: ListeningPart, partJsonBytes: ByteArray,
        sourceHtmlSha256: String? = null): QuestionLayoutDocument {
        require(layout.schemaVersion == 1) { "UNSUPPORTED_LAYOUT_SCHEMA" }
        require(partPattern.matches(layout.partId)) { "INVALID_PART_ID" }
        require(layout.partId == part.id) { "PART_ID_MISMATCH" }
        require(shaPattern.matches(layout.basePartSha256)) { "INVALID_BASE_PART_HASH" }
        require(sha256(partJsonBytes) == layout.basePartSha256) { "BASE_PART_HASH_MISMATCH" }
        require(layout.groups.size <= MAX_GROUPS) { "INVALID_GROUPS" }
        val byId = part.questions.associateBy { it.id }
        val byNumber = part.questions.associateBy { it.number }
        require(byId.size == part.questions.size && byNumber.size == part.questions.size) { "INVALID_PART_QUESTION_MAP" }
        val groupIds = mutableSetOf<String>()
        val occupiedQuestions = mutableSetOf<String>()
        var totalBlocks = 0
        var totalText = 0

        layout.groups.forEach { group ->
            validateGroup(group, layout, part, byId, sourceHtmlSha256, groupIds, occupiedQuestions) { count, chars ->
                totalBlocks += count
                totalText += chars
            }
            require(totalBlocks <= MAX_TOTAL_BLOCKS) { "TOO_MANY_BLOCKS" }
            require(totalText <= MAX_TOTAL_TEXT) { "LAYOUT_TEXT_TOO_LARGE" }
        }
        return layout
    }

    private fun validateGroup(group: QuestionLayoutGroup, layout: QuestionLayoutDocument, part: ListeningPart,
        byId: Map<String, Question>, sourceHtmlSha256: String?, groupIds: MutableSet<String>,
        occupiedQuestions: MutableSet<String>, addTotals: (Int, Int) -> Unit) {
        require(groupIdPattern.matches(group.groupId)) { "INVALID_GROUP_ID" }
        require(group.groupId.startsWith("${layout.partId}-layout-")) { "GROUP_ID_PART_MISMATCH" }
        require(groupIds.add(group.groupId)) { "DUPLICATE_GROUP_ID" }
        require(group.layoutKind in kinds) { "UNSUPPORTED_LAYOUT_KIND" }
        require(group.questionIds.isNotEmpty() && group.questionIds.size <= 10) { "INVALID_GROUP_QUESTIONS" }
        require(group.questionIds.distinct().size == group.questionIds.size) { "DUPLICATE_GROUP_QUESTION" }
        require(group.questionIds.none { it in occupiedQuestions }) { "QUESTION_IN_MULTIPLE_GROUPS" }
        require(group.questionIds.all { it in byId }) { "UNKNOWN_QUESTION_ID" }
        occupiedQuestions += group.questionIds
        require(group.promptHashes.keys == group.questionIds.toSet()) { "PROMPT_HASH_SET_MISMATCH" }
        group.questionIds.forEach { questionId ->
            val question = byId.getValue(questionId)
            require(question.isTextInput && question.wordLimit != null && question.images.isEmpty()) {
                "UNSUPPORTED_QUESTION_TYPE"
            }
            val prompt = question.prompt
            require(group.promptHashes[questionId] == sha256(prompt.toByteArray(Charsets.UTF_8))) { "PROMPT_HASH_MISMATCH" }
        }
        require(group.source.resource == "content.html") { "INVALID_SOURCE_RESOURCE" }
        require(shaPattern.matches(group.source.sha256)) { "INVALID_SOURCE_HASH" }
        if (sourceHtmlSha256 != null) require(group.source.sha256 == sourceHtmlSha256) { "SOURCE_HTML_HASH_MISMATCH" }
        require(group.source.locator.isNotBlank() && group.source.locator.length <= MAX_LOCATOR) { "INVALID_SOURCE_LOCATOR" }
        require(group.source.sourceKind.isNotBlank() && group.source.sourceKind.length <= 64) { "INVALID_SOURCE_KIND" }
        require(group.blocks.isNotEmpty() && group.blocks.size <= MAX_BLOCKS_PER_GROUP) { "INVALID_BLOCKS" }

        val slots = group.questionIds.associateWith { mutableListOf<Int>() }
        var blockCount = 0
        var textCount = 0

        fun visit(blocks: List<LayoutBlock>, depth: Int) {
            require(depth <= MAX_DEPTH) { "LAYOUT_NESTING_TOO_DEEP" }
            blocks.forEach { block ->
                blockCount++
                require(blockCount <= MAX_BLOCKS_PER_GROUP) { "TOO_MANY_BLOCKS" }
                when (block.type) {
                    "text", "fixedText" -> {
                        require(block.text != null && block.text.isNotEmpty() && block.text.length <= MAX_TEXT) { "INVALID_TEXT_BLOCK" }
                        require(block.source != null && block.children.isEmpty() && block.questionId == null
                            && block.questionNumber == null && block.slotIndex == null && block.marker == null && block.label == null) {
                            "INVALID_TEXT_BLOCK_FIELDS"
                        }
                        textCount += block.text.length
                        require(textCount <= MAX_TOTAL_TEXT) { "LAYOUT_TEXT_TOO_LARGE" }
                        validateTextSource(block.source, block.text, group, byId)
                    }
                    "blank" -> {
                        val questionId = requireNotNull(block.questionId) { "INVALID_BLANK_QUESTION" }
                        require(questionId in group.questionIds) { "BLANK_QUESTION_OUTSIDE_GROUP" }
                        val question = byId.getValue(questionId)
                        require(block.questionNumber == question.number) { "QUESTION_NUMBER_MISMATCH" }
                        val slot = requireNotNull(block.slotIndex) { "INVALID_SLOT_INDEX" }
                        require(slot >= 0 && block.text == null && block.children.isEmpty()
                            && block.marker == null && block.label == null) { "INVALID_BLANK_FIELDS" }
                        validateBlankSource(requireNotNull(block.source) { "INVALID_BLANK_SOURCE" }, question, group)
                        slots.getValue(questionId) += slot
                    }
                    "lineBreak" -> {
                        require(block.text == null && block.questionId == null && block.questionNumber == null
                            && block.slotIndex == null && block.children.isEmpty() && block.marker == null && block.label == null) {
                            "INVALID_LINE_BREAK_FIELDS"
                        }
                        require(block.source == null) { "INVALID_LINE_BREAK_SOURCE" }
                    }
                    "paragraph", "listItem", "formRow" -> {
                        require(block.children.isNotEmpty() && block.text == null && block.questionId == null
                            && block.questionNumber == null && block.slotIndex == null && block.source == null) {
                            "INVALID_STRUCTURED_BLOCK_FIELDS"
                        }
                        if (block.type == "listItem") {
                            require(block.label == null && (block.marker == null || block.marker in setOf("•", "●", "▪", "-", "*"))) {
                                "INVALID_LIST_MARKER"
                            }
                        } else if (block.type == "formRow") {
                            require(block.marker == null && (block.label == null || block.label.length <= 2_000)) { "INVALID_FORM_LABEL" }
                        } else require(block.marker == null && block.label == null) { "INVALID_PARAGRAPH_FIELDS" }
                        visit(block.children, depth + 1)
                    }
                    else -> throw IllegalArgumentException("UNSUPPORTED_BLOCK_TYPE")
                }
            }
        }
        visit(group.blocks, 1)
        group.questionIds.forEach { questionId ->
            val question = byId.getValue(questionId)
            val values = slots.getValue(questionId).sorted()
            val expected = if (question.answerSeparator != null) 2 else 1
            require(values.size == expected) { "BLANK_COUNT_MISMATCH" }
            require(values == (0 until expected).toList()) { "SLOT_INDEX_GAP_OR_DUPLICATE" }
        }
        addTotals(blockCount, textCount)
    }

    private fun validateTextSource(source: LayoutBlockSource, text: String, group: QuestionLayoutGroup,
        byId: Map<String, Question>) {
        when (source.kind) {
            "prompt" -> {
                val questionId = requireNotNull(source.questionId) { "INVALID_TEXT_SOURCE" }
                require(questionId in group.questionIds) { "SOURCE_QUESTION_OUTSIDE_GROUP" }
                val start = requireNotNull(source.startUtf16) { "INVALID_SOURCE_OFFSET" }
                val end = requireNotNull(source.endUtf16) { "INVALID_SOURCE_OFFSET" }
                val prompt = byId.getValue(questionId).prompt
                require(start >= 0 && end > start && end <= prompt.length &&
                    isUtf16Boundary(prompt, start) && isUtf16Boundary(prompt, end) &&
                    prompt.substring(start, end) == text) {
                    "SOURCE_TEXT_MISMATCH"
                }
                require(source.resource == null && source.locator == null) { "INVALID_TEXT_SOURCE_FIELDS" }
            }
            "html" -> {
                require(source.questionId == null && source.startUtf16 == null && source.endUtf16 == null
                    && source.resource == "content.html" && source.locator.orEmpty().isNotBlank()
                    && source.locator!!.length <= MAX_LOCATOR) { "INVALID_HTML_TEXT_SOURCE" }
            }
            else -> throw IllegalArgumentException("UNSUPPORTED_TEXT_SOURCE")
        }
    }

    private fun validateBlankSource(source: LayoutBlockSource, question: Question, group: QuestionLayoutGroup) {
        when (source.kind) {
            "prompt-anchor" -> {
                require(source.questionId == question.id && source.resource == null && source.locator == null) {
                    "BLANK_SOURCE_QUESTION_MISMATCH"
                }
                val start = requireNotNull(source.startUtf16) { "INVALID_SOURCE_OFFSET" }
                val end = requireNotNull(source.endUtf16) { "INVALID_SOURCE_OFFSET" }
                require(start >= 0 && end > start && end <= question.prompt.length &&
                    isUtf16Boundary(question.prompt, start) && isUtf16Boundary(question.prompt, end)) {
                    "INVALID_SOURCE_OFFSET"
                }
                val anchor = question.prompt.substring(start, end)
                require(Regex("(?<![\\p{L}\\p{N}_])${question.number}[ \\t\\r\\n]*(?:[.:][ \\t\\r\\n]*)?(?:_{2,}|[＿﹍]{2,})(?![\\p{L}\\p{N}_])")
                    .containsMatchIn(anchor)) {
                    "INVALID_PROMPT_GAP_ANCHOR"
                }
            }
            "html-marker" -> require(source.questionId == null && source.startUtf16 == null && source.endUtf16 == null
                && source.resource == "content.html" && source.locator.orEmpty().isNotBlank()
                && source.locator!!.length <= MAX_LOCATOR) { "INVALID_HTML_GAP_SOURCE" }
            else -> throw IllegalArgumentException("UNSUPPORTED_BLANK_SOURCE")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun isUtf16Boundary(text: String, offset: Int): Boolean =
        offset == 0 || offset == text.length ||
            !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())
}
