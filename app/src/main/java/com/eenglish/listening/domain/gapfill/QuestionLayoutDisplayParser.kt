package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.annotation.contentHash
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionLayoutGroup

/** Prefer validated sidecars, then preserve the existing parser and QuestionCard fallback. */
object QuestionLayoutDisplayParser {
    fun parse(questions: List<Question>, groups: List<QuestionLayoutGroup>): List<QuestionDisplayItem> {
        val positions = questions.mapIndexed { index, question -> question.id to index }.toMap()
        val eligible = groups.mapNotNull { group ->
            if (group.questionIds.isEmpty() || group.questionIds.distinct().size != group.questionIds.size) return@mapNotNull null
            val indices = group.questionIds.mapNotNull { positions[it] }
            if (indices.size != group.questionIds.size || indices.isEmpty() ||
                indices != (indices.first()..indices.last()).toList()) return@mapNotNull null
            val members = indices.map { questions[it] }
            if (members.map { it.id } != group.questionIds || members.any { question ->
                    !question.isTextInput || question.wordLimit == null || question.images.isNotEmpty() ||
                    group.promptHashes[question.id] != contentHash(question.prompt)
                }) return@mapNotNull null
            val slots = group.questionIds.associateWith { mutableListOf<Int>() }
            var malformedBlank = false
            fun collect(blocks: List<com.eenglish.listening.domain.model.LayoutBlock>) {
                blocks.forEach { block ->
                    if (block.type == "blank") {
                        val questionId = block.questionId
                        val target = questionId?.let(slots::get)
                        if (target == null || block.questionNumber != members.firstOrNull { it.id == questionId }?.number ||
                            block.slotIndex == null) malformedBlank = true
                        else target += block.slotIndex
                    } else collect(block.children)
                }
            }
            collect(group.blocks)
            if (malformedBlank || members.any { question ->
                    val expected = if (question.answerSeparator != null) 2 else 1
                    slots.getValue(question.id).sorted() != (0 until expected).toList()
                }) return@mapNotNull null
            indices.first() to (group to members)
        }.sortedBy { it.first }
        val groupAt = eligible.associateBy({ it.first }, { it.second })
        val result = mutableListOf<QuestionDisplayItem>()
        var index = 0
        while (index < questions.size) {
            val structured = groupAt[index]
            if (structured != null) {
                result += QuestionDisplayItem.StructuredLayout(structured.first, structured.second)
                index += structured.second.size
                continue
            }
            val nextLayoutStart = eligible.firstOrNull { it.first > index }?.first ?: questions.size
            val legacySlice = questions.subList(index, nextLayoutStart.coerceAtLeast(index + 1))
            val first = GapFillGroupParser.parse(legacySlice).firstOrNull()
            when (first) {
                is QuestionDisplayItem.InlineGroup -> {
                    result += first
                    index += first.group.questions.size
                }
                else -> {
                    result += QuestionDisplayItem.Single(questions[index])
                    index++
                }
            }
        }
        return result
    }
}
