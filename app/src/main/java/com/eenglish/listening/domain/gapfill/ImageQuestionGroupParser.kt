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
    private val numberedLabel = Regex("""^(\d{1,3})([.:]?)$""")
    private val numberedPlaceholder = Regex("""^(\d{1,3})([.:]?)(_{2,}|[＿﹍]{2,})$""")
    private val standaloneNumber = Regex("""^\d{1,3}$""")
    private val placeholder = Regex("""(?:_{2,}|[＿﹍]{2,})""")
    private val trailingQuestionNumber = Regex("""(?s)^(.*?)(?:\s+)(\d{1,3})\s*$""")

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

        val sharedPrompt = normalizeWhitespace(first.prompt)
        val sharedInstructions = normalizeWhitespace(first.instructions)
        val sharedContext = first.context
        val sharedWordLimit = first.wordLimit ?: return null
        val sharedType = first.type
        val sharedImages = first.images
        if (sharedImages.isEmpty() || sharedImages.any(String::isBlank)) return null

        if (members.any { question ->
                !question.isTextInput || question.options.isNotEmpty() ||
                    question.type != sharedType || question.wordLimit != sharedWordLimit ||
                    question.answerSeparator != null || question.groupId != null ||
                    question.groupNumbers.isNotEmpty() || normalizeWhitespace(question.prompt) != sharedPrompt ||
                    normalizeWhitespace(question.instructions) != sharedInstructions || question.context != sharedContext ||
                    (question.images.isNotEmpty() && question.images != sharedImages)
            }
        ) return null

        val displayParts = explicitSharedPrefix(
            prompt = first.prompt,
            title = title,
            instructions = first.instructions,
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

    /** Extracts the shared instruction prefix from the prompt after validating the complete answer range. */
    private fun explicitSharedPrefix(
        prompt: String,
        title: String,
        instructions: String,
        expectedNumbers: IntRange,
    ): String? {
        if (instructions.isBlank()) return null
        val normalizedPrompt = normalizeWhitespaceWithOffsets(prompt)
        val headingEnd = prompt.indexOfAny(charArrayOf('\r', '\n'))
        if (headingEnd < 0) return null
        val instructionStart = if (prompt[headingEnd] == '\r' && prompt.getOrNull(headingEnd + 1) == '\n') {
            headingEnd + 2
        } else {
            headingEnd + 1
        }

        // Instructions may include the heading, omit it, or use different whitespace from prompt.
        // Try both exact textual prefixes; only a complete, validated answer suffix can be removed.
        val prefixCandidates = listOf(
            normalizeWhitespace(instructions),
            normalizeWhitespace("$title\n$instructions"),
        ).distinct()
        val validDisplays = prefixCandidates.mapNotNull { expectedPrefix ->
            if (expectedPrefix.isBlank() || !normalizedPrompt.value.startsWith(expectedPrefix)) return@mapNotNull null
            if (normalizedPrompt.value.length > expectedPrefix.length &&
                normalizedPrompt.value[expectedPrefix.length] != ' '
            ) return@mapNotNull null

            val prefixEnd = normalizedPrompt.sourceEndOffsets.getOrNull(expectedPrefix.lastIndex)
                ?: return@mapNotNull null
            if (prefixEnd < instructionStart) return@mapNotNull null

            val displayText = prompt.substring(instructionStart, prefixEnd).trim()
            if (displayText.isBlank()) return@mapNotNull null
            val suffixTokens = normalizeWhitespace(prompt.substring(prefixEnd))
                .split(' ')
                .filter(String::isNotEmpty)

            val firstLabel = trailingQuestionNumber.matchEntire(displayText)
            val containsFirstQuestionLabel = firstLabel?.groupValues?.get(2)?.toIntOrNull() == expectedNumbers.first
            when {
                containsFirstQuestionLabel && hasSplitNumberPlaceholderTokens(suffixTokens, expectedNumbers) ->
                    firstLabel!!.groupValues[1].trimEnd().takeIf(String::isNotBlank)
                containsFirstQuestionLabel -> null
                hasNumberedPlaceholderTokens(suffixTokens, expectedNumbers) -> displayText
                else -> null
            }
        }
        return validDisplays.distinct().singleOrNull()
    }

    private fun hasNumberedPlaceholderTokens(tokens: List<String>, expectedNumbers: IntRange): Boolean {
        var index = 0
        for (expectedNumber in expectedNumbers) {
            if (index >= tokens.size) return false
            val combined = numberedPlaceholder.matchEntire(tokens[index])
            if (combined != null) {
                if (combined.groupValues[1].toIntOrNull() != expectedNumber) return false
                index++
                continue
            }

            val label = numberedLabel.matchEntire(tokens[index++])?.groupValues?.get(1)?.toIntOrNull()
                ?: return false
            if (label != expectedNumber) return false
            if (index < tokens.size && tokens[index] in setOf(".", ":")) index++
            if (index >= tokens.size || !placeholder.matches(tokens[index++])) return false
        }
        return index == tokens.size
    }

    private fun hasSplitNumberPlaceholderTokens(tokens: List<String>, expectedNumbers: IntRange): Boolean {
        var index = 0
        if (tokens.isEmpty() || !placeholder.matches(tokens[index++])) return false
        for (number in expectedNumbers.drop(1)) {
            if (index >= tokens.size || !standaloneNumber.matches(tokens[index++]) ||
                tokens[index - 1].toIntOrNull() != number || index >= tokens.size ||
                !placeholder.matches(tokens[index++])
            ) return false
        }
        return index == tokens.size
    }

    private fun normalizeLines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun normalizeWhitespace(value: String): String = normalizeWhitespaceWithOffsets(value).value

    private data class NormalizedWhitespace(val value: String, val sourceEndOffsets: IntArray)

    /** Collapses Unicode whitespace for comparison while retaining offsets into the untouched source. */
    private fun normalizeWhitespaceWithOffsets(value: String): NormalizedWhitespace {
        val normalized = StringBuilder()
        val sourceEndOffsets = ArrayList<Int>()
        var pendingWhitespace = false
        value.forEachIndexed { index, character ->
            if (character.isWhitespace()) {
                pendingWhitespace = true
            } else {
                if (pendingWhitespace && normalized.isNotEmpty()) {
                    normalized.append(' ')
                    sourceEndOffsets += index
                }
                normalized.append(character)
                sourceEndOffsets += index + 1
                pendingWhitespace = false
            }
        }
        return NormalizedWhitespace(normalized.toString(), sourceEndOffsets.toIntArray())
    }
}
