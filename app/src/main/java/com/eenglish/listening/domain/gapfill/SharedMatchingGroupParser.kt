package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType

/** Display-only grouping for questions that explicitly share one matching prompt. */
data class SharedMatchingQuestionGroup(
    val questions: List<Question>,
    val title: String,
    val prompt: String,
    val instructions: String,
    val context: String,
    val options: List<Option>,
    val images: List<String>,
    val optionsInPrompt: Boolean,
    /** Present only when every numbered row can be extracted without losing text. */
    val questionTexts: Map<String, String>?,
)

object SharedMatchingGroupParser {
    private const val MAX_GROUP_SIZE = 20
    private val rangeLine = Regex(
        "(?i)^\\s*Questions?\\s+(\\d{1,3})\\s*(?:[-–—]\\s*(\\d{1,3})|and\\s+(\\d{1,3}))\\s*$"
    )
    private val rowLine = Regex("^\\s*(\\d{1,3})[.)]?\\s*(.*)$")
    private val placeholder = Regex("(?:_{2,}|[＿﹍]{2,})")
    private val mapLabelPrompt = Regex("(?is)\\blabel\\s+(?:the\\s+)?(?:map|plan|diagram)\\b")

    fun parseAtStart(questions: List<Question>): SharedMatchingQuestionGroup? {
        val first = questions.firstOrNull() ?: return null
        val isImageMatching = first.type == QuestionType.IMAGE_BASED
        if (first.type != QuestionType.MATCHING && !isImageMatching) return null
        if (first.isTextInput || first.options.size < 2 || first.wordLimit != null || first.answerSeparator != null ||
            first.groupId != null || first.groupNumbers.isNotEmpty()) return null

        // IMAGE_BASED is accepted only for explicit map/plan/diagram labelling with letter choices.
        if (isImageMatching && (!mapLabelPrompt.containsMatchIn(first.prompt) ||
                first.options.any { !it.id.matches(Regex("[A-Z]")) || it.text.trim() != it.id })) return null

        val lines = first.prompt.replace("\r\n", "\n").replace('\r', '\n').lines()
        val title = lines.firstOrNull()?.trim().orEmpty()
        val range = rangeLine.matchEntire(title) ?: return null
        val start = range.groupValues[1].toIntOrNull() ?: return null
        val end = (range.groupValues[2].ifBlank { range.groupValues[3] }).toIntOrNull() ?: return null
        if (start != first.number || end < start) return null
        val size = end - start + 1
        if (size !in 2..MAX_GROUP_SIZE || questions.size < size) return null

        val members = questions.take(size)
        if (members.map { it.number } != (start..end).toList() ||
            members.map { it.id }.distinct().size != size) return null

        val promptKey = normalize(first.prompt)
        val instructionsKey = normalize(first.instructions)
        val contextKey = normalize(first.context)
        if (members.any { question ->
                question.type != first.type || question.isTextInput || question.options != first.options ||
                    normalize(question.prompt) != promptKey || normalize(question.instructions) != instructionsKey ||
                    normalize(question.context) != contextKey || question.wordLimit != null ||
                    question.answerSeparator != null || question.groupId != null || question.groupNumbers.isNotEmpty() ||
                    question.options.any { option -> option.id.isBlank() || option.text.isBlank() } ||
                    question.options.map { it.id }.distinct().size != question.options.size ||
                    question.acceptableAnswers.isEmpty() || question.acceptableAnswers.any { answer ->
                        question.options.none { it.id.equals(answer, ignoreCase = true) }
                    }
            }) return null

        val imageLists = members.map { it.images }
        if (imageLists.any { images -> images.any(String::isBlank) }) return null
        val images = when {
            imageLists.distinct().size == 1 -> imageLists.first()
            imageLists.first().isNotEmpty() && imageLists.drop(1).all { it.isEmpty() } -> imageLists.first()
            else -> return null
        }
        if (isImageMatching && images.isEmpty()) return null

        val promptOptionCount = countPromptOptions(first.prompt, first.options)
        if (promptOptionCount in 1 until first.options.size) return null

        return SharedMatchingQuestionGroup(
            questions = members,
            title = title,
            prompt = first.prompt,
            instructions = first.instructions,
            context = first.context,
            options = first.options.toList(),
            images = images.toList(),
            optionsInPrompt = promptOptionCount == first.options.size,
            questionTexts = extractQuestionTexts(lines, start..end),
        )
    }

    private fun countPromptOptions(prompt: String, options: List<Option>): Int {
        val lines = prompt.replace("\r\n", "\n").replace('\r', '\n').lines()
        return options.count { option ->
            val prefix = Regex("^\\s*${Regex.escape(option.id)}(?:[\\t .):–—-]+)(.*)$")
            lines.any { line -> prefix.matchEntire(line)?.groupValues?.get(1)?.let(::normalize) == normalize(option.text) }
        }
    }

    /** Returns null unless every expected number has one non-empty, ordered row. */
    private fun extractQuestionTexts(lines: List<String>, expected: IntRange): Map<String, String>? {
        val starts = lines.mapIndexedNotNull { index, line ->
            val match = rowLine.matchEntire(line) ?: return@mapIndexedNotNull null
            val number = match.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
            if (number in expected) index to number else null
        }
        if (starts.map { it.second } != expected.toList()) return null
        val result = linkedMapOf<String, String>()
        starts.forEachIndexed { index, (lineIndex, number) ->
            val until = starts.getOrNull(index + 1)?.first ?: lines.size
            val firstText = rowLine.matchEntire(lines[lineIndex])?.groupValues?.get(2).orEmpty()
            val content = (listOf(firstText) + lines.subList(lineIndex + 1, until))
                .map { placeholder.replace(it, "").trim() }
                .filter(String::isNotBlank)
                .joinToString(" ")
                .trim()
            if (content.isBlank()) return null
            result["q$number"] = content
        }
        return result.takeIf { it.keys == expected.map { number -> "q$number" }.toSet() }
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
