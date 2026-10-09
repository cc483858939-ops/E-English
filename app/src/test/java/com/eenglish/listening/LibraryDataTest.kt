package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LibraryDataTest {
    @Test fun allSuccessfulBatchPartsValidateAndCanonicalAnswersScore() {
        val root = File(requireNotNull(System.getProperty("listening.batch")))
        assumeTrue("Private batch library absent", root.isDirectory)
        val models = root.walkTopDown().filter { it.name == "part.json" }.map { PartCodec.decode(it.readText()) }.toList()
        assertEquals((5..21).toSet(),models.map { it.book }.toSet())
        assertEquals(266,models.size)
        assertEquals(2660,models.sumOf { it.questions.size })
        assertEquals(266,models.map { it.id }.distinct().size)
        models.forEach { part ->
            val selected = part.questions.associate { q -> q.id to if (q.type == QuestionType.MULTIPLE_CHOICE)
                part.questions.filter { it.groupId == q.groupId }.map { it.correctAnswer }.sorted().joinToString(",") else q.correctAnswer }
            assertEquals("Batch grading mismatch for ${part.id}",10,Grader.grade(part.questions,selected).correctCount)
        }
    }
    @Test fun representativeRealPartsValidateAndCanonicalAnswersScore() {
        val root = File(requireNotNull(System.getProperty("listening.library")))
        assumeTrue("Private representative library absent", root.isDirectory)
        val models = root.walkTopDown().filter { it.name == "part.json" }.map { PartCodec.decode(it.readText()) }.toList()
        assertEquals(setOf(5,9,13,17,18,21), models.map { it.book }.toSet())
        assertEquals(24, models.size)
        models.forEach { part ->
            assertEquals(((part.part - 1) * 10 + 1..part.part * 10).toList(), part.questions.map { it.number })
            val selected = part.questions.associate { q -> q.id to
                if (q.type == QuestionType.MULTIPLE_CHOICE) part.questions.filter { it.groupId == q.groupId }.map { it.correctAnswer }.sorted().joinToString(",")
                else q.correctAnswer }
            assertEquals("Canonical grading mismatch for ${part.id}", 10, Grader.grade(part.questions, selected).correctCount)
        }
        val bundled = File(requireNotNull(System.getProperty("listening.assets")), "listening/cambridge-9-test-1-part-3/part.json")
        assertEquals(PartCodec.decode(bundled.readText()), models.single { it.id == "cambridge-9-test-1-part-3" })
    }
    @Test fun indexHasNoQuestionOrTranscriptBodies() {
        val root = File(requireNotNull(System.getProperty("listening.library")))
        assumeTrue("Private representative library absent", root.isDirectory)
        root.walkTopDown().filter { it.name == "index.json" }.forEach { file ->
            val index = Json.decodeFromString<BookIndex>(file.readText()).validate()
            assertTrue(index.parts.all { it.questionCount == 10 })
            assertFalse(file.readText().contains("correctAnswer"))
            assertFalse(file.readText().contains("transcript"))
        }
    }
}
