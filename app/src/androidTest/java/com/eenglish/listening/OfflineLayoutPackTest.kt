package com.eenglish.listening

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.data.repository.OfflinePackImporter
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.data.repository.ImportFailure
import com.eenglish.listening.data.repository.PackImportException
import com.eenglish.listening.domain.annotation.contentHash
import com.eenglish.listening.domain.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only synthetic files in an app cache subdirectory; no Room data or installed library is touched. */
@RunWith(AndroidJUnit4::class)
class OfflineLayoutPackTest {
    private val json = Json

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun part(title: String = "Synthetic layout fixture", firstPrompt: String = "Question 1 _____ tail"): ListeningPart {
        val audio = byteArrayOf(1, 2, 3, 4)
        val questions = (1..10).map { number ->
            Question("cambridge-5-test-1-part-1-q$number", number, QuestionType.TEXT_INPUT,
                if (number == 1) firstPrompt else "Question $number fixture", emptyList(), "fixture",
                wordLimit = WordLimit(2, 0))
        }
        return ListeningPart(2, "cambridge-5-test-1-part-1", "IELTS", 5, 1, 1, title,
            "Write answers", "listening/cambridge-5-test-1-part-1/audio.mp3", sha(audio),
            Transcript("English fixture", "中文测试", listOf(TranscriptSegment("English fixture", "中文测试"))),
            questions).validate()
    }

    private fun layout(part: ListeningPart, partBytes: ByteArray, layoutLabel: String): ByteArray {
        val question = part.questions.first()
        val prompt = question.prompt
        val prefix = "Question "
        val anchorStart = prompt.indexOf("1 _____")
        val anchorEnd = anchorStart + "1 _____".length
        val html = "fixture $layoutLabel".toByteArray()
        val group = QuestionLayoutGroup(
            "${part.id}-layout-summary-q1-q1", "summaryCompletion", listOf(question.id),
            mapOf(question.id to contentHash(question.prompt)),
            LayoutSourceTrace("content.html", sha(html), "wrapper:0", "synthetic"), listOf(
                LayoutBlock("paragraph", children = listOf(
                    LayoutBlock("text", text = prefix,
                        source = LayoutBlockSource("prompt", question.id, 0, prefix.length)),
                    LayoutBlock("blank", questionId = question.id, questionNumber = 1, slotIndex = 0,
                        source = LayoutBlockSource("prompt-anchor", question.id, anchorStart, anchorEnd)),
                    LayoutBlock("text", text = " tail", source = LayoutBlockSource("prompt", question.id,
                        prompt.indexOf(" tail"), prompt.length)),
                )),
            ))
        val document = QuestionLayoutDocument(1, part.id, sha(partBytes), listOf(group))
        return json.encodeToString(document).toByteArray(Charsets.UTF_8)
    }

    private fun pack(part: ListeningPart, withLayout: Boolean, extraEntry: Boolean = false): ByteArray {
        val partBytes = json.encodeToString(part).toByteArray(Charsets.UTF_8)
        val audio = byteArrayOf(1, 2, 3, 4)
        val layoutBytes = if (withLayout) layout(part, partBytes, "layout-v2") else null
        val summary = PartSummary(part.id, part.book, part.test, part.part, part.title,
            part.questions.size, part.questions.first().number, part.questions.last().number,
            audio.size.toLong(), sha(partBytes), sha(audio), emptyMap(), layoutBytes?.let(::sha))
        val index = BookIndex(if (withLayout) 2 else 1, part.book, listOf(summary))
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            entry("index.json", json.encodeToString(index).toByteArray(Charsets.UTF_8))
            val prefix = "listening/${part.id}/"
            entry(prefix + "part.json", partBytes)
            entry(prefix + "audio.mp3", audio)
            layoutBytes?.let { entry(prefix + "layout.json", it) }
            if (extraEntry) entry("unlisted.txt", byteArrayOf(9))
        }
        return output.toByteArray()
    }

    @Test fun legacyImportThenLayoutOnlyUpdatePublishesAtomicallyAndRetainsPriorDirectory() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "offline-layout-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val importer = OfflinePackImporter(root)
            val original = part()
            importer.install(ByteArrayInputStream(pack(original, withLayout = false)))
            val oldFolder = importer.installed().getValue(5)
            val oldDirectory = File(root, oldFolder)
            assertTrue(oldDirectory.isDirectory)
            assertFalse(File(oldDirectory, "listening/${original.id}/layout.json").exists())

            val upgraded = importer.install(ByteArrayInputStream(pack(original, withLayout = true)))
            assertEquals(2, upgraded.schemaVersion)
            val currentFolder = importer.installed().getValue(5)
            assertNotEquals(oldFolder, currentFolder)
            assertTrue("the active/old immutable directory remains available", oldDirectory.isDirectory)
            val installedLayout = File(root, "$currentFolder/listening/${original.id}/layout.json")
            assertTrue(installedLayout.isFile)
            assertEquals(upgraded.parts.single().layoutSha256, sha(installedLayout.readBytes()))

            val beforeConflict = importer.installed()
            try {
                importer.install(ByteArrayInputStream(pack(part(title = "Changed source Part"), withLayout = false)))
                fail("Changed part.json must conflict")
            } catch (error: PackImportException) {
                assertEquals(ImportFailure.CONFLICT, error.reason)
            }
            assertEquals("A rejected update cannot move the catalogue pointer", beforeConflict, importer.installed())

            try {
                importer.install(ByteArrayInputStream(pack(original, withLayout = false, extraEntry = true)))
                fail("Undeclared ZIP entry must be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(beforeConflict, importer.installed())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun bundledCambridge9PartRemainsPrimaryAndCanReadItsVerifiedLayoutSidecar() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundledPartPath = "listening/cambridge-9-test-1-part-3/part.json"
        val partBytes = context.assets.open(bundledPartPath).use { it.readBytes() }
        val part = PartCodec.decode(partBytes.toString(Charsets.UTF_8))
        val audio = context.assets.open(part.audioPath).use { it.readBytes() }
        assertTrue("the built-in sample fixture should not need separate image entries",
            part.questions.flatMap { it.images }.isEmpty())
        val layoutBytes = json.encodeToString(QuestionLayoutDocument(1, part.id, sha(partBytes), emptyList()))
            .toByteArray(Charsets.UTF_8)
        val summary = PartSummary(part.id, part.book, part.test, part.part, part.title,
            part.questions.size, part.questions.first().number, part.questions.last().number,
            audio.size.toLong(), sha(partBytes), sha(audio), emptyMap(), sha(layoutBytes))
        val index = BookIndex(2, part.book, listOf(summary))
        val archiveBytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                entry("index.json", json.encodeToString(index).toByteArray(Charsets.UTF_8))
                val prefix = "listening/${part.id}/"
                entry(prefix + "part.json", partBytes)
                entry(prefix + "audio.mp3", audio)
                entry(prefix + "layout.json", layoutBytes)
            }
        }.toByteArray()

        val root = File(context.cacheDir, "bundled-layout-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val repository = PartRepository(context.assets, root)
            repository.importPack(ByteArrayInputStream(archiveBytes))
            assertTrue(repository.loadParts().any { it.id == part.id })
            val loadedPart = repository.loadPart(part.id)
            assertEquals("the protected bundled Part remains the selected semantic source", part, loadedPart)
            val loadedLayout = repository.loadLayout(loadedPart)
            assertNotNull("an exact Part-bound sidecar remains available for the bundled sample", loadedLayout)
            assertEquals(part.id, loadedLayout?.partId)
            assertTrue(loadedLayout?.groups?.isEmpty() == true)
        } finally {
            root.deleteRecursively()
        }
    }
}
