package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType

/** All offsets refer to the untouched Question.prompt in UTF-16 code units. */
data class NumberedGap(val number: Int, val questionId: String, val start: Int, val end: Int)
data class GapFillGroup(val questions: List<Question>, val prompt: String, val gaps: List<NumberedGap>) {
    init {
        require(questions.size >= 2 && gaps.size == questions.size)
        require(gaps.map { it.number } == questions.map { it.number })
    }
}

sealed interface QuestionDisplayItem {
    data class Single(val question: Question) : QuestionDisplayItem
    data class InlineGroup(val group: GapFillGroup) : QuestionDisplayItem
}

/**
 * Display-only compatibility parser for legacy packs. Never touches IDs, stored prompts or JSON.
 * Deliberately limited to explicitly numbered SUMMARY completion; no ordering guesses.
 */
object GapFillGroupParser {
    private val heading = Regex("""(?im)^[ \t]*Questions?[ \t]+(\d{1,3})[ \t]*[-–—][ \t]*(\d{1,3})[ \t]*$""")
    private val summary = Regex("""(?i)\bcomplete\s+the\s+summary\b""")
    // One match must contain BOTH a standalone number and its underscore run.
    // A range title such as "Questions 26–30" cannot match this expression.
    private val numberedBlank = Regex(
        """(?<![\p{L}\p{N}_])([1-9]\d{0,2})[ \t\r\n]*(?:[.:][ \t\r\n]*)?(_{2,}(?:[ \t]*_{2,})*|[＿﹍]{2,})(?![\p{L}\p{N}_])"""
    )

    fun parse(questions: List<Question>): List<QuestionDisplayItem> {
        val result = mutableListOf<QuestionDisplayItem>()
        var position = 0
        while (position < questions.size) {
            val first = questions[position]
            if (!eligible(first)) {
                result += QuestionDisplayItem.Single(first)
                position++
                continue
            }
            var end = position + 1
            while (end < questions.size && compatible(first, questions[end]) &&
                questions[end].number == questions[end - 1].number + 1) end++

            val run = questions.subList(position, end)
            val group = parseRun(run)
            if (group != null) result += QuestionDisplayItem.InlineGroup(group)
            else run.forEach { result += QuestionDisplayItem.Single(it) }
            position = end
        }
        return result
    }

    private fun eligible(q: Question): Boolean =
        q.type == QuestionType.TEXT_INPUT && q.isTextInput && q.options.isEmpty() &&
            q.wordLimit != null && q.answerSeparator == null && q.groupId == null &&
            q.groupNumbers.isEmpty() && q.images.isEmpty()

    private fun compatible(a: Question, b: Question): Boolean =
        eligible(b) && a.prompt == b.prompt && a.instructions.trim() == b.instructions.trim() &&
            a.wordLimit == b.wordLimit && a.context == b.context

    private fun parseRun(run: List<Question>): GapFillGroup? {
        if (run.size < 2) return null
        val prompt = run.first().prompt
        val header = heading.findAll(prompt).toList()
        if (header.size != 1 || !summary.containsMatchIn(prompt)) return null
        val first = header.single().groupValues[1].toIntOrNull() ?: return null
        val last = header.single().groupValues[2].toIntOrNull() ?: return null
        if (first != run.first().number || last != run.last().number ||
            last - first + 1 != run.size || run.map { it.id }.distinct().size != run.size) return null

        val matches = numberedBlank.findAll(prompt).toList()
        if (matches.size != run.size) return null
        val positions = matches.map { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return null
            val question = run.singleOrNull { it.number == number } ?: return null
            NumberedGap(number, question.id, match.range.first, match.range.last + 1)
        }
        // Presentation order must be explicit, continuous and unique. No inferred numbering.
        if (positions.map { it.number } != run.map { it.number } ||
            positions.map { it.start }.distinct().size != positions.size) return null
        return GapFillGroup(run.toList(), prompt, positions)
    }
}
