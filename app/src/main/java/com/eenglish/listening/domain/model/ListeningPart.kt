package com.eenglish.listening.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class QuestionType { SINGLE_CHOICE, MULTIPLE_CHOICE, TEXT_INPUT, MATCHING, IMAGE_BASED }

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
)

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
        require(schemaVersion == 1) { "Unsupported schema version" }
        require(id.matches(Regex("[a-z0-9-]+"))) { "Invalid part ID" }
        require(audioPath == "listening/$id/audio.mp3") { "Invalid local audio path" }
        require(audioSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid audio checksum" }
        require(title.isNotBlank() && instructions.isNotBlank()) { "Missing title/instructions" }
        require(questions.isNotEmpty()) { "Empty questions" }
        require(questions.map { it.number }.distinct().size == questions.size) { "Duplicate question number" }
        require(questions.map { it.id }.distinct().size == questions.size) { "Duplicate question ID" }
        questions.forEach { question ->
            require(question.type == QuestionType.SINGLE_CHOICE) { "Question type has no renderer yet" }
            require(question.id.isNotBlank() && question.prompt.isNotBlank()) { "Empty question" }
            require(question.options.size >= 2) { "Missing options" }
            require(question.options.all { it.id.isNotBlank() && it.text.isNotBlank() }) { "Empty option" }
            require(question.options.map { it.id }.distinct().size == question.options.size) { "Duplicate option" }
            require(question.options.any { it.id == question.correctAnswer }) { "Invalid answer reference" }
        }
        require(transcript.english.isNotBlank() && transcript.chinese.isNotBlank()
            && transcript.segments.isNotEmpty()) { "Missing transcript" }
        require(transcript.segments.filter { it.english.isNotBlank() }.joinToString("\n\n") { it.english.trim() }
            == transcript.english.trim()) { "English segment mismatch" }
        require(transcript.segments.filter { it.chinese.isNotBlank() }.joinToString("\n\n") { it.chinese.trim() }
            == transcript.chinese.trim()) { "Chinese segment mismatch" }
    }
}
