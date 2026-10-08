package com.eenglish.listening

import com.eenglish.listening.domain.annotation.*
import com.eenglish.listening.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class HighlightTest {
    @Test fun duplicateOverlappingAndAdjacentRangesMerge() {
        val original = listOf(HighlightRange(2, 7, 10))
        assertEquals(original, HighlightRanges.add(original, 2, 7, 20))
        assertEquals(listOf(HighlightRange(2, 9, 10)), HighlightRanges.add(original, 4, 9, 20))
        assertEquals(listOf(HighlightRange(2, 9, 10)), HighlightRanges.add(original, 7, 9, 20))
    }
    @Test fun partialCancellationSplitsAndKeepsOtherRanges() {
        val original = listOf(HighlightRange(0, 7, 10), HighlightRange(9, 12, 20))
        assertEquals(listOf(HighlightRange(0, 2, 10), HighlightRange(5, 7, 10), HighlightRange(9, 12, 20)),
            HighlightRanges.remove(original, 2, 5))
        assertEquals(emptyList<HighlightRange>(), HighlightRanges.remove(original, 0, 20))
    }
    @Test fun repeatedWordsUseExactOffsetsRatherThanSearching() {
        val document = AnnotationDocument.single("repeat repeat", "transcript", "segment:0", "en")
        val selected = document.selection("p", 7, 13).single()
        assertEquals(7, selected.start)
        assertEquals(listOf(HighlightRange(7, 13, 1)), document.visibleRanges("p", listOf(Highlight(selected.key, HighlightRange(7, 13, 1)))))
    }
    @Test fun crossParagraphSelectionSplitsAndSeparatorsAreNotMarked() {
        val transcript = Transcript("abc\n\ndef", "甲乙\n\n丙丁", listOf(TranscriptSegment("abc", "甲乙"), TranscriptSegment("def", "丙丁")))
        val selected = AnnotationDocument.transcript(transcript, "en").selection("p", 1, 7)
        assertEquals(listOf(1 to 3, 0 to 2), selected.map { it.start to it.end })
        assertEquals(listOf("segment:0", "segment:1"), selected.map { it.key.textId })
    }
    @Test fun languageAndContentVersionAreIsolatedAndBilingualUsesSameKeys() {
        val transcript = Transcript("hello", "你好", listOf(TranscriptSegment("hello", "你好")))
        val en = AnnotationDocument.transcript(transcript, "en").selection("p", 0, 2).single()
        val saved = listOf(Highlight(en.key, HighlightRange(0, 2, 1)))
        assertEquals(1, AnnotationDocument.single("hello", "transcript", "segment:0", "en").visibleRanges("p", saved).size)
        assertTrue(AnnotationDocument.transcript(transcript, "zh").visibleRanges("p", saved).isEmpty())
        assertTrue(AnnotationDocument.single("changed", "transcript", "segment:0", "en").visibleRanges("p", saved).isEmpty())
        assertTrue(AnnotationDocument.transcript(transcript, "en").visibleRanges("other-part", saved).isEmpty())
    }
    @Test fun offsetsRemainUtf16WithEmojiAndAcrossLines() {
        val document = AnnotationDocument.single("a😀\n中文", "transcript", "s", "en")
        val change = document.selection("p", 1, 6).single()
        assertEquals(1, change.start)
        assertEquals(6, change.end)
        assertEquals("😀\n中文", document.text.substring(change.start, change.end))
    }
    @Test(expected = IllegalArgumentException::class) fun mismatchedParagraphContentIsRejected() {
        AnnotationDocument.transcript(Transcript("changed", "翻译", listOf(TranscriptSegment("original", "翻译"))), "en")
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSelectionIsRejected() {
        AnnotationDocument.single("abc", "question", "q", "und").selection("p", 0, 4)
    }
}
