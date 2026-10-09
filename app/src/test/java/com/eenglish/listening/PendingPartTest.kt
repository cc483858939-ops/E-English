package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@Serializable
private data class LimitVector(val instruction: String, val limit: WordLimit, val value: String,
    val separator: String? = null, val allowed: Boolean)

class PendingPartTest {
    @Test fun sameLimitVectorsAsPythonImporter() {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream("input-limits.json")).bufferedReader().use { it.readText() }
        val vectors = Json.decodeFromString<List<LimitVector>>(text)
        assertEquals(28,vectors.size)
        vectors.forEach { v ->
            val q = Question("synthetic",1,QuestionType.TEXT_INPUT,"Synthetic",emptyList(),"alpha",wordLimit=v.limit,answerSeparator=v.separator)
            assertEquals("Limit vector: ${v.instruction}, separator=${v.separator}",v.allowed,Grader.withinLimit(q,v.value))
        }
    }
    @Test fun incompletePairedResponseIsMissingAndSubmittedSnapshotRoundTrips() {
        val q = Question("synthetic",1,QuestionType.TEXT_INPUT,"Synthetic",emptyList(),"alpha and beta",
            wordLimit=WordLimit(2),answerSeparator="and")
        assertEquals(1,Grader.missingCount(listOf(q),mapOf(q.id to "alpha and ")))
        assertEquals(1,Grader.missingCount(listOf(q),mapOf(q.id to " and beta")))
        assertEquals(0,Grader.missingCount(listOf(q),mapOf(q.id to q.correctAnswer)))
        assertEquals(1,Grader.grade(listOf(q),mapOf(q.id to q.correctAnswer)).correctCount)
        val encoded=Json.encodeToString(Question.serializer(),q)
        assertEquals(q,Json.decodeFromString<Question>(encoded))
    }
    @Test fun sixRecoveredPrivatePartsValidateAndLegalSourceVariantsScore() {
        val root=File(requireNotNull(System.getProperty("listening.pending")))
        assumeTrue("Private targeted normalized fixtures absent",root.isDirectory)
        val parts=root.walkTopDown().filter { it.name == "part.json" }.map { PartCodec.decode(it.readText()) }.toList()
        assertEquals(6,parts.size); assertEquals(60,parts.sumOf { it.questions.size })
        assertEquals(5,parts.flatMap { it.questions }.count { it.answerSeparator != null })
        parts.forEach { part ->
            assertEquals(((part.part-1)*10+1..part.part*10).toList(),part.questions.map { it.number })
            val answers=part.questions.associate { q -> q.id to if(q.type == QuestionType.MULTIPLE_CHOICE)
                part.questions.filter { it.groupId == q.groupId }.map { it.correctAnswer }.sorted().joinToString(",") else q.correctAnswer }
            assertEquals("Recovered Part grade: ${part.id}",10,Grader.grade(part.questions,answers).correctCount)
            part.questions.filter { it.answerSeparator != null }.forEach { q ->
                q.acceptableAnswers.forEach { value ->
                    assertTrue("Source variant violates limit: ${q.id}",Grader.withinLimit(q,value))
                    assertEquals(10,Grader.grade(part.questions,answers+(q.id to value)).correctCount)
                }
            }
        }
        parts.single { it.book == 11 }.questions.filter { it.number in 15..20 }.forEach {
            assertEquals(('A'..'I').map(Char::toString),it.options.map { o -> o.id })
            assertEquals(QuestionType.IMAGE_BASED,it.type); assertEquals(1,it.images.size)
        }
    }
}
