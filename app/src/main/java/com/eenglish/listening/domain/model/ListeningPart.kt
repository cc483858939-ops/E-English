package com.eenglish.listening.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class QuestionType {
    SINGLE_CHOICE, MULTIPLE_CHOICE, TEXT_INPUT, MATCHING, IMAGE_BASED,
    SHORT_ANSWER, TABLE_COMPLETION, NOTE_COMPLETION, FLOW_COMPLETION,
}

@Serializable
data class WordLimit(val maxWords: Int, val maxNumbers: Int? = 0,
    val numberOnly: Boolean = false, val wordsOrNumber: Boolean = false)

@Serializable
data class Option(val id: String, val text: String)

@Serializable
data class Question(
    val id: String,
    val number: Int,
    val type: QuestionType,
    val prompt: String,
    val options: List<Option>,
    val correctAnswer: String,
    val acceptedAnswers: List<String> = emptyList(),
    val instructions: String = "",
    val context: String = "",
    val images: List<String> = emptyList(),
    val wordLimit: WordLimit? = null,
    val groupId: String? = null,
    val groupNumbers: List<Int> = emptyList(),
) {
    val acceptableAnswers get() = acceptedAnswers.ifEmpty { listOf(correctAnswer) }
    val isTextInput get() = options.isEmpty()
}

@Serializable
data class TranscriptSegment(val english: String, val chinese: String)

@Serializable
data class Transcript(val english: String, val chinese: String, val segments: List<TranscriptSegment>)

@Serializable
data class ListeningPart(
    val schemaVersion: Int,
    val id: String,
    val examType: String,
    val book: Int,
    val test: Int,
    val part: Int,
    val title: String,
    val instructions: String,
    val audioPath: String,
    val audioSha256: String,
    val transcript: Transcript,
    val questions: List<Question>,
) {
    /** Validate at the resource boundary; errors identify structure, never answer contents. */
    fun validate(): ListeningPart = apply {
        require(schemaVersion in 1..2) { "Unsupported schema version" }
        require(id.matches(Regex("[a-z0-9-]+"))) { "Invalid part ID" }
        require(audioPath == "listening/$id/audio.mp3") { "Invalid local audio path" }
        require(audioSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid audio checksum" }
        require(title.isNotBlank() && instructions.isNotBlank()) { "Missing title/instructions" }
        require(questions.isNotEmpty()) { "Empty questions" }
        require(questions.map { it.number }.distinct().size == questions.size) { "Duplicate question number" }
        require(questions.map { it.id }.distinct().size == questions.size) { "Duplicate question ID" }
        questions.forEach { question ->
            require(question.id.isNotBlank() && question.prompt.isNotBlank()) { "Empty question" }
            if (!question.isTextInput) require(question.options.size >= 2) { "Missing options" }
            else require(question.type !in listOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE, QuestionType.MATCHING)
                && question.wordLimit != null) { "Missing input requirements" }
            require(question.options.all { it.id.isNotBlank() && it.text.isNotBlank() }) { "Empty option" }
            require(question.options.map { it.id }.distinct().size == question.options.size) { "Duplicate option" }
            require(question.acceptableAnswers.all { it.isNotBlank() }) { "Missing accepted answer" }
            if (!question.isTextInput) require(question.acceptableAnswers.all { answer ->
                question.options.any { it.id.equals(answer, ignoreCase = true) }
            }) { "Invalid answer reference" }
            require(question.images.all { it.matches(Regex("[a-zA-Z0-9_-]+\\.(png|jpg|jpeg|webp)")) }) { "Unsafe image path" }
            if (question.type == QuestionType.IMAGE_BASED) require(question.images.isNotEmpty()) { "Missing question image" }
            question.wordLimit?.let { require(it.maxWords in 0..10 && (it.maxNumbers == null || it.maxNumbers in 0..10)) }
            if (question.type == QuestionType.MULTIPLE_CHOICE) {
                val group = questions.filter { it.groupId == question.groupId }
                require(question.groupId != null && question.groupNumbers.size in 2..6
                    && group.map { it.number }.sorted() == question.groupNumbers.sorted()
                    && group.all { it.type == QuestionType.MULTIPLE_CHOICE && it.options == question.options && it.groupNumbers == question.groupNumbers }
                    && group.map { it.correctAnswer }.distinct().size == group.size) { "Invalid multi-answer group" }
            } else require(question.groupId == null && question.groupNumbers.isEmpty()) { "Unexpected answer group" }
        }
        if (schemaVersion == 1) require(transcript.english.isNotBlank() && transcript.chinese.isNotBlank()
            && transcript.segments.isNotEmpty()) { "Missing transcript" }
        require(transcript.segments.filter { it.english.isNotBlank() }.joinToString("\n\n") { it.english.trim() }
            == transcript.english.trim()) { "English segment mismatch" }
        require(transcript.segments.filter { it.chinese.isNotBlank() }.joinToString("\n\n") { it.chinese.trim() }
            == transcript.chinese.trim()) { "Chinese segment mismatch" }
    }
}
