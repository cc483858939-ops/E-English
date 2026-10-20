package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class TranscriptTimelineTest {
    private val transcript = Transcript("one\n\ntwo\n\nthree", "一\n\n二\n\n三", listOf(
        TranscriptSegment("one", "一"), TranscriptSegment("two", "二"), TranscriptSegment("three", "三")))
    private val part = ListeningPart(2, "cambridge-5-test-1-part-1", "IELTS", 5, 1, 1, "fixture", "fixture",
        "listening/cambridge-5-test-1-part-1/audio.mp3", "a".repeat(64), transcript, emptyList())
    private val timeline = TranscriptTimeline(part.id, part.audioSha256, transcriptTimingHash(transcript),
        listOf(TranscriptCue(100, 1000), TranscriptCue(900, 1800), TranscriptCue(2000, 3000)))

    @Test fun boundariesGapsOverlapsAndNearestCueUseMediaPosition() {
        assertNull(timeline.activeIndex(99))
        assertEquals(0, timeline.activeIndex(100))
        assertEquals(1, timeline.activeIndex(900))
        assertNull(timeline.activeIndex(1800))
        assertEquals(2, timeline.activeIndex(2000))
        assertNull(timeline.activeIndex(3000))
        assertEquals(0, timeline.nearestIndex(0))
        assertEquals(1, timeline.nearestIndex(1850))
        assertEquals(2, timeline.nearestIndex(4000))
        // A null heading interval never becomes a blue spoken paragraph.
        assertNull(timeline.copy(segments = listOf(null)).activeIndex(0))
    }

    @Test fun mismatchedAudioTextOrderingAndDurationDisableSync() {
        assertTrue(timeline.matches(part))
        assertFalse(timeline.copy(audioSha256 = "b".repeat(64)).matches(part))
        assertFalse(timeline.matches(part.copy(transcript = transcript.copy(segments = transcript.segments.reversed()))))
        assertFalse(timeline.copy(segments = timeline.segments.reversed()).matches(part))
        assertFalse(timeline.copy(segments = listOf(TranscriptCue(-1, 100))).matches(part))
        assertFalse(timeline.fitsDuration(2999))
        assertTrue(timeline.fitsDuration(3000))
        assertEquals(2999L, timeline.forDuration(2999)!!.segments.last()!!.endMs)
        assertEquals(3000L, timeline.segments.last()!!.endMs)
        assertNull(timeline.forDuration(1000))
        assertNull(timeline.forDuration(2000)) // Never discard an entire spoken paragraph.
    }

    @Test fun generatedCatalogMatchesAllCurrentPacksWithoutChangingTimingOrExamContent() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val catalog = Json.decodeFromString<TranscriptTimelineCatalog>(File(root, "app/src/main/assets/transcript-timelines.json").readText())
        assertEquals(1, catalog.schemaVersion)
        assertEquals(272, catalog.parts.size)
        assertEquals(272, catalog.parts.map { it.partId }.distinct().size)
        val byId = catalog.parts.associateBy { it.partId }
        var checked = 0
        File(root, "private-data/layout-v4-android-test-packs").listFiles()!!.filter { it.extension == "eelpack" }.forEach { pack ->
            ZipFile(pack).use { zip -> zip.entries().asSequence().filter { it.name.endsWith("/part.json") }.forEach { entry ->
                val p = PartCodec.decode(zip.getInputStream(entry).reader().readText())
                assertTrue(p.id, byId.getValue(p.id).matches(p))
                checked++
            } }
        }
        assertEquals(272, checked)
        assertEquals(103150L, byId.getValue("cambridge-5-test-1-part-1").segments.filterNotNull().first().startMs)
        assertEquals(85400L, byId.getValue("cambridge-6-test-1-part-1").segments.filterNotNull().first().startMs)
        assertEquals(53200L, byId.getValue("cambridge-9-test-1-part-3").segments.filterNotNull().first().startMs)
        val padded = byId.getValue("cambridge-21-test-1-part-1")
        val playable = requireNotNull(padded.forDuration(351843))
        assertTrue(playable.fitsDuration(351843))
        assertEquals(padded.segments.map { it?.startMs }, playable.segments.map { it?.startMs })
    }
}
