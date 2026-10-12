package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType

data class SharedMultipleChoiceQuestionGroup(
    val groupId: String,
    val questions: List<Question>,
    val title: String,
    val prompt: String,
    val instructions: String,
    val context: String,
    val options: List<Option>,
    val optionsInPrompt: Boolean,
    val images: List<String>,
    val selectionCount: Int,
)

object SharedMultipleChoiceGroupParser {
    private const val MAX_GROUP_SIZE = 6
    private val titleLine = Regex(
        "(?i)^\\s*Questions?\\s+(\\d{1,3})\\s*(?:[-–—]\\s*(\\d{1,3})|and\\s+(\\d{1,3}))\\s*$"
    )
    private val chooseCount = Regex(
        "(?i)\\bchoose\\s+(one|two|three|four|five|six|[1-6])\\s+(?:answers?|letters?)\\b"
    )
    private val counts = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)

    fun parseAtStart(questions: List<Question>): SharedMultipleChoiceQuestionGroup? {
        val first = questions.firstOrNull() ?: return null
        if (first.type != QuestionType.MULTIPLE_CHOICE) return null
        val groupId = first.groupId?.takeIf(String::isNotBlank) ?: return null
        val numbers = first.groupNumbers
        if (numbers.size !in 2..MAX_GROUP_SIZE || numbers.distinct().size != numbers.size ||
            numbers != numbers.sorted() || numbers.zipWithNext().any { (left, right) -> right != left + 1 }) return null
        val size = numbers.size
        if (questions.size < size || first.number != numbers.first()) return null

        val members = questions.take(size)
        if (members.map { it.number } != numbers || members.map { it.id }.distinct().size != size ||
            questions.count { it.groupId == groupId } != size ||
            members.any { question -> question.groupId != groupId || question.type != QuestionType.MULTIPLE_CHOICE ||
                question.groupNumbers != numbers }) return null

        val lines = first.prompt.replace("\r\n", "\n").replace('\r', '\n').lines()
        val title = lines.firstOrNull()?.trim().orEmpty()
        val heading = titleLine.matchEntire(title) ?: return null
        val rangeStart = heading.groupValues[1].toIntOrNull() ?: return null
        val rangeEnd = (heading.groupValues[2].ifBlank { heading.groupValues[3] }).toIntOrNull() ?: return null
        if (rangeStart != numbers.first() || rangeEnd != numbers.last()) return null

        val selectionMatches = chooseCount.findAll(first.prompt).toList()
        if (selectionMatches.size != 1) return null
        val countToken = selectionMatches.single().groupValues[1].lowercase()
        val selectionCount = counts[countToken] ?: countToken.toIntOrNull() ?: return null
        if (selectionCount != size) return null

        val promptKey = normalize(first.prompt)
        val instructionsKey = normalize(first.instructions)
        val contextKey = normalize(first.context)
        if (members.any { question ->
                normalize(question.prompt) != promptKey || normalize(question.instructions) != instructionsKey ||
                    normalize(question.context) != contextKey || question.options != first.options ||
                    question.options.size < 2 || question.options.any { it.id.isBlank() || it.text.isBlank() } ||
                    question.options.map { it.id }.distinct().size != question.options.size ||
                    question.acceptedAnswers.isNotEmpty() && question.acceptedAnswers != listOf(question.correctAnswer) ||
                    question.options.none { it.id.equals(question.correctAnswer, ignoreCase = true) } ||
                    question.acceptableAnswers.size != 1 ||
                    question.acceptableAnswers.any { answer -> question.options.none { it.id.equals(answer, true) } } ||
                    question.answerSeparator != null || question.wordLimit != null
            }) return null
        if (members.map { it.correctAnswer.uppercase() }.distinct().size != size) return null

        if (members.any { it.images != first.images || it.images.any(String::isBlank) }) return null
        val promptOptionCount = countPromptOptions(first.prompt, first.options)
        if (promptOptionCount in 1 until first.options.size) return null

        return SharedMultipleChoiceQuestionGroup(
            groupId = groupId,
            questions = members,
            title = title,
            prompt = first.prompt,
            instructions = first.instructions,
            context = first.context,
            options = first.options.toList(),
            optionsInPrompt = promptOptionCount == first.options.size,
            images = first.images.toList(),
            selectionCount = selectionCount,
        )
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun countPromptOptions(prompt: String, options: List<Option>): Int {
        val lines = prompt.replace("\r\n", "\n").replace('\r', '\n').lines()
        return options.count { option ->
            val prefix = Regex("^\\s*${Regex.escape(option.id)}(?:[\\t .):–—-]+)(.*)$")
            lines.any { line -> prefix.matchEntire(line)?.groupValues?.get(1)?.let(::normalize) == normalize(option.text) }
        }
    }
}
