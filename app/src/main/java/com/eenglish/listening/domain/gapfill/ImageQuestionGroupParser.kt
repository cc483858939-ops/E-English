package com.eenglish.listening.domain.gapfill

import com.eenglish.listening.domain.model.Question

/** A display-only view of questions that explicitly share one image and numbered answer range. */
data class SharedImageQuestionGroup(
    val questions: List<Question>,
    val title: String,
    val instructions: String,
    val context: String,
    val images: List<String>,
)

/**
 * Conservative parser for legacy image questions. A group is formed only when the first prompt
 * names its exact question range and ends in one explicit numbered blank for every member.
 * Stored prompts, IDs and question data are never changed.
 */
object ImageQuestionGroupParser {
    private const val MAX_GROUP_SIZE = 20

    private val rangeHeading = Regex("""(?i)^Questions?\s+(\d{1,3})\s*[-–—]\s*(\d{1,3})$""")
    private val numberedPlaceholder = Regex("""^\s*(\d{1,3})\s*(?:[.:]\s*)?(_{2,}|[＿﹍]{2,})\s*$""")
    private val barePlaceholder = Regex("""^\s*(_{2,}|[＿﹍]{2,})\s*$""")
    private val standaloneNumber = Regex("""^\s*(\d{1,3})\s*$""")
    private val trailingQuestionNumber = Regex("""^(.*?)[ \t]+(\d{1,3})[ \t]*$""")

    fun parseAtStart(questions: List<Question>): SharedImageQuestionGroup? {
        val first = questions.firstOrNull() ?: return null
        val prompt = normalizeLines(first.prompt)
        val promptLines = prompt.split('\n')
        val title = promptLines.firstOrNull()?.trim().orEmpty()
        val range = rangeHeading.matchEntire(title) ?: return null
        val firstNumber = range.groupValues[1].toIntOrNull() ?: return null
        val lastNumber = range.groupValues[2].toIntOrNull() ?: return null
        if (first.number != firstNumber || lastNumber < firstNumber) return null

        val groupSize = lastNumber - firstNumber + 1
        if (groupSize !in 2..MAX_GROUP_SIZE || questions.size < groupSize) return null
        val members = questions.take(groupSize)
        if (members.map { it.number } != (firstNumber..lastNumber).toList() ||
            members.map { it.id }.distinct().size != members.size
        ) return null

        val sharedPrompt = normalizeLines(first.prompt)
        val sharedInstructions = normalizeLines(first.instructions)
        val sharedContext = first.context
        val sharedWordLimit = first.wordLimit ?: return null
        val sharedType = first.type
        val sharedImages = first.images
        if (sharedImages.isEmpty() || sharedImages.any(String::isBlank)) return null

        if (members.any { question ->
                !question.isTextInput || question.options.isNotEmpty() ||
                    question.type != sharedType || question.wordLimit != sharedWordLimit ||
                    question.answerSeparator != null || question.groupId != null ||
                    question.groupNumbers.isNotEmpty() || normalizeLines(question.prompt) != sharedPrompt ||
                    normalizeLines(question.instructions) != sharedInstructions || question.context != sharedContext ||
                    (question.images.isNotEmpty() && question.images != sharedImages)
            }
        ) return null

        val displayParts = explicitSharedPrefix(
            promptLines = promptLines,
            title = title,
            instructions = sharedInstructions,
            expectedNumbers = firstNumber..lastNumber,
        ) ?: return null

        return SharedImageQuestionGroup(
            questions = members,
            title = title,
            instructions = displayParts,
            context = sharedContext,
            images = sharedImages.distinct(),
        )
    }

    /** Returns only the instruction lines after the heading, after validating every suffix row. */
    private fun explicitSharedPrefix(
        promptLines: List<String>,
        title: String,
        instructions: String,
        expectedNumbers: IntRange,
    ): String? {
        if (instructions.isBlank()) return null
        val instructionLines = instructions.split('\n')
        val instructionsIncludeTitle = instructionLines.firstOrNull()?.trim() == title
        val prefixLineCount = if (instructionsIncludeTitle) {
            instructionLines.size
        } else {
            1 + instructionLines.size
        }
        if (promptLines.size < prefixLineCount) return null

        val actualPrefix = promptLines.take(prefixLineCount)
        val expectedPrefix = if (instructionsIncludeTitle) {
            instructionLines
        } else {
            listOf(promptLines.first()) + instructionLines
        }
        if (actualPrefix != expectedPrefix || actualPrefix.firstOrNull()?.trim() != title) return null

        val displayLines = if (instructionsIncludeTitle) {
            instructionLines.drop(1).joinToString("\n").trim()
        } else {
            instructionLines.joinToString("\n").trim()
        }
        if (displayLines.isBlank()) return null

        val suffix = promptLines.drop(prefixLineCount)
        if (hasNumberedPlaceholderRows(suffix, expectedNumbers)) return displayLines

        // Some packs place Q11 at the end of the final instruction line, then serialize each
        // remaining question number and its blank on separate lines. Accept only that exact shape.
        val instructionDisplayLines = displayLines.split('\n').toMutableList()
        val firstLabel = trailingQuestionNumber.matchEntire(instructionDisplayLines.last()) ?: return null
        if (firstLabel.groupValues[2].toIntOrNull() != expectedNumbers.first ||
            firstLabel.groupValues[1].isBlank() ||
            !hasSplitNumberPlaceholderRows(suffix, expectedNumbers)
        ) return null
        instructionDisplayLines[instructionDisplayLines.lastIndex] = firstLabel.groupValues[1].trimEnd()
        val withoutFirstLabel = instructionDisplayLines.joinToString("\n").trim()
        return withoutFirstLabel.takeIf(String::isNotBlank)
    }

    private fun hasNumberedPlaceholderRows(lines: List<String>, expectedNumbers: IntRange): Boolean {
        var nextNumber = expectedNumbers.first
        var placeholderCount = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val match = numberedPlaceholder.matchEntire(line) ?: return false
            val number = match.groupValues[1].toIntOrNull() ?: return false
            if (number != nextNumber || number > expectedNumbers.last) return false
            nextNumber++
            placeholderCount++
        }
        return placeholderCount == expectedNumbers.count() && nextNumber == expectedNumbers.last + 1
    }

    private fun hasSplitNumberPlaceholderRows(lines: List<String>, expectedNumbers: IntRange): Boolean {
        val content = lines.filterNot(String::isBlank)
        var index = 0
        if (content.isEmpty() || barePlaceholder.matchEntire(content[index++]) == null) return false
        for (number in expectedNumbers.drop(1)) {
            if (index >= content.size) return false
            val label = standaloneNumber.matchEntire(content[index++])?.groupValues?.get(1)?.toIntOrNull()
            if (label != number || index >= content.size || barePlaceholder.matchEntire(content[index++]) == null) {
                return false
            }
        }
        return index == content.size
    }

    private fun normalizeLines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
}
