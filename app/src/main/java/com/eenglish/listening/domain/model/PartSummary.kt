package com.eenglish.listening.domain.model

import kotlinx.serialization.Serializable

/** Small index entry: no question bodies, transcripts or audio reads at startup. */
@Serializable
data class PartSummary(val id: String, val book: Int, val test: Int, val part: Int,
    val title: String, val questionCount: Int, val firstNumber: Int, val lastNumber: Int,
    val audioBytes: Long = 0, val partSha256: String = "", val audioSha256: String = "",
    val imageHashes: Map<String, String> = emptyMap()) {
    fun validate() = apply {
        require(book in 5..21 && test in 1..4 && part in 1..4)
        require(id == "cambridge-$book-test-$test-part-$part")
        require(title.isNotBlank() && questionCount == 10 && firstNumber == (part - 1) * 10 + 1 && lastNumber == part * 10)
        require(audioBytes in 1..128_000_000 && listOf(partSha256, audioSha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(imageHashes.all { (path, hash) -> path.matches(Regex("[a-zA-Z0-9_-]+\\.(png|jpg|jpeg|webp)")) && hash.matches(Regex("[a-f0-9]{64}")) })
    }
}

@Serializable
data class BookIndex(val schemaVersion: Int, val book: Int, val parts: List<PartSummary>) {
    fun validate() = apply {
        require(schemaVersion == 1 && book in 5..21 && parts.size in 1..16)
        require(parts.map { it.id }.distinct().size == parts.size)
        parts.forEach { require(it.book == book); it.validate() }
    }
}
