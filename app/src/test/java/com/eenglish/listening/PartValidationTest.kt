package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.*
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic question text is confined to tests; real exam assets are local and ignored. */
fun fixturePart(): ListeningPart = ListeningPart(
    1, "test-part", "IELTS", 9, 1, 3, "Test fixture", "Choose one", "listening/test-part/audio.mp3", "0".repeat(64),
    Transcript("English fixture", "中文测试", listOf(TranscriptSegment("English fixture", "中文测试"))),
    (21..30).map { Question("q$it", it, QuestionType.SINGLE_CHOICE, "Test prompt $it",
        listOf(Option("A", "First"), Option("B", "Second"), Option("C", "Third")), "A") },
)

class PartValidationTest {
    @Test fun validFixture() { assertEquals(10, fixturePart().validate().questions.size) }
    @Test fun duplicateNumbersRejected() {
        val p = fixturePart()
        assertThrows(IllegalArgumentException::class.java) { p.copy(questions = p.questions + p.questions.first()).validate() }
    }
    @Test fun invalidAnswersAndOptionsRejected() {
        val p = fixturePart()
        for (q in listOf(p.questions.first().copy(correctAnswer = "Z"),
            p.questions.first().copy(options = listOf(Option("A", ""))),
            p.questions.first().copy(options = listOf(Option("A", "x"), Option("A", "y"))))) {
            assertThrows(IllegalArgumentException::class.java) { p.copy(questions = listOf(q)).validate() }
        }
    }
    @Test fun actualImportedPrivateAssetsAreConsistent() {
        val assets = File(requireNotNull(System.getProperty("listening.assets")))
        val file = File(assets, "listening/cambridge-9-test-1-part-3/part.json")
        assumeTrue("Private sample absent; run tools/import_sample.py", file.isFile)
        val part = PartCodec.decode(file.readText())
        assertEquals((21..30).toList(), part.questions.map { it.number })
        assertEquals(10, part.questions.size)
        assertTrue(part.questions.all { it.options.map { option -> option.id } == listOf("A", "B", "C") })
        val audio = File(assets, part.audioPath).readBytes()
        assertEquals(part.audioSha256, MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) })
    }
}
