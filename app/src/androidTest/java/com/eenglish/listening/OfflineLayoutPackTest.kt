package com.eenglish.listening

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.data.repository.OfflinePackImporter
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.data.repository.ImportFailure
import com.eenglish.listening.data.repository.BatchPackImporter
import com.eenglish.listening.data.repository.ImportOutcome
import com.eenglish.listening.data.repository.PackImportException
import com.eenglish.listening.data.repository.PackImportSource
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
import kotlinx.serialization.json.*
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

    private fun imageBytes(): ByteArray = ByteArrayOutputStream().also { output ->
        // The synthetic asset keeps a .png filename while the raw bytes are JPEG, like some source packs.
        Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 100, output)
    }.toByteArray()

    private fun partWithImage(): ListeningPart {
        val base = part()
        return base.copy(questions = base.questions.mapIndexed { index, question ->
            if (index == 0) question.copy(images = listOf("diagram.png")) else question
        }).validate()
    }

    private fun v4Layout(part: ListeningPart, partBytes: ByteArray, image: ByteArray, corruption: String?): ByteArray {
        if (corruption == "deepJson") {
            val depth = 2_000
            return ("{\"schemaVersion\":4,\"nested\":" + "[".repeat(depth) + "0" + "]".repeat(depth) + "}")
                .toByteArray(Charsets.UTF_8)
        }
        val imageHash = sha(image)
        val slots = part.questions.map { question -> buildJsonObject {
            put("slotId", "slot-q${question.number}-0"); put("questionId", question.id)
            put("slotIndex", 0); put("kind", "fill")
        } }
        val responses = part.questions.map { question -> buildJsonObject {
            put("kind", "fill"); put("questionIds", buildJsonArray { add(question.id) })
            put("slotIds", buildJsonArray { add("slot-q${question.number}-0") })
            put("scoring", "legacyPerQuestion")
        } }
        fun text(question: Question) = buildJsonObject {
            put("id", "b-text-q${question.number}"); put("type", "text"); put("textId", "t-q${question.number}")
            put("text", question.prompt); put("questionId", question.id)
            put("source", buildJsonObject {
                put("kind", "partField"); put("field", "question.prompt"); put("questionId", question.id)
                put("startUtf16", 0); put("endUtf16", question.prompt.length)
            })
        }
        fun slot(question: Question) = buildJsonObject {
            put("id", "b-slot-q${question.number}"); put("type", "slot"); put("slotId", "slot-q${question.number}-0")
        }
        val blocks = buildList {
            val first = part.questions.first()
            add(text(first))
            add(buildJsonObject {
                put("id", "b-image"); put("type", "image"); put("asset", "diagram.png")
                put("widthPx", 100); put("heightPx", 40)
                put("overlays", buildJsonArray {
                    add(buildJsonObject {
                        put("slotId", "slot-q${first.number}-0")
                        put("rectPx", buildJsonObject { put("x", 10); put("y", 8); put("width", 30); put("height", 12) })
                        put("evidence", buildJsonObject {
                            put("kind", "modelMeasurement"); put("measurementId", "model-q${first.number}-0")
                            put("imageSha256", imageHash)
                        })
                    })
                })
                put("printedConnectors", buildJsonArray { })
            })
            part.questions.drop(1).forEach { question ->
                add(buildJsonObject {
                    put("id", "b-row-q${question.number}"); put("type", "formRow")
                    put("children", buildJsonArray { add(text(question)); add(slot(question)) })
                })
            }
        }
        var layout = buildJsonObject {
            put("schemaVersion", 4); put("partId", part.id); put("basePartSha256", sha(partBytes))
            put("groups", buildJsonArray { add(buildJsonObject {
                put("groupId", "${part.id}-layout-test-q1-q10")
                put("questionIds", buildJsonArray { part.questions.forEach { add(it.id) } })
                put("promptHashes", buildJsonObject {
                    part.questions.forEach { put(it.id, sha(it.prompt.toByteArray(Charsets.UTF_8))) }
                })
                put("instructions", buildJsonArray { }); put("optionPools", buildJsonArray { })
                put("slots", buildJsonArray { slots.forEach(::add) })
                put("responses", buildJsonArray { responses.forEach(::add) })
                put("blocks", buildJsonArray { blocks.forEach(::add) })
            }) })
        }
        if (corruption != null && corruption != "layoutHash") {
            val group = layout.getValue("groups").jsonArray.single().jsonObject
            val originalBlocks = group.getValue("blocks").jsonArray.toMutableList()
            val imageBlock = originalBlocks[1].jsonObject
            when (corruption) {
                "partBinding" -> layout = JsonObject(layout.toMutableMap() + ("basePartSha256" to JsonPrimitive("c".repeat(64))))
                "imageDimensions" -> originalBlocks[1] = JsonObject(imageBlock.toMutableMap() + ("widthPx" to JsonPrimitive(99)))
                "duplicateSlot" -> {
                    val overlays = imageBlock.getValue("overlays").jsonArray
                    originalBlocks[1] = JsonObject(imageBlock.toMutableMap() + ("overlays" to JsonArray(overlays + overlays.single())))
                }
                "outsideHotspot" -> {
                    val overlay = imageBlock.getValue("overlays").jsonArray.single().jsonObject
                    val rect = buildJsonObject { put("x", 95); put("y", 8); put("width", 30); put("height", 12) }
                    originalBlocks[1] = JsonObject(imageBlock.toMutableMap() + ("overlays" to JsonArray(
                        listOf(JsonObject(overlay.toMutableMap() + ("rectPx" to rect))))))
                }
                else -> error("Unknown v4 corruption: $corruption")
            }
            if (corruption != "partBinding") {
                val changedGroup = JsonObject(group.toMutableMap() + ("blocks" to JsonArray(originalBlocks)))
                layout = JsonObject(layout.toMutableMap() + ("groups" to JsonArray(listOf(changedGroup))))
            }
        }
        return json.encodeToString(layout).toByteArray(Charsets.UTF_8)
    }

    private fun v4Pack(part: ListeningPart, corruption: String? = null): ByteArray {
        val partBytes = json.encodeToString(part).toByteArray(Charsets.UTF_8)
        val audio = byteArrayOf(1, 2, 3, 4)
        val image = imageBytes()
        val layout = v4Layout(part, partBytes, image, corruption)
        val layoutHash = if (corruption == "layoutHash") "f".repeat(64) else sha(layout)
        val summary = PartSummary(part.id, part.book, part.test, part.part, part.title,
            part.questions.size, part.questions.first().number, part.questions.last().number,
            audio.size.toLong(), sha(partBytes), sha(audio), mapOf("diagram.png" to sha(image)), layoutHash)
        val index = BookIndex(2, part.book, listOf(summary))
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                entry("index.json", json.encodeToString(index).toByteArray(Charsets.UTF_8))
                val prefix = "listening/${part.id}/"
                entry(prefix + "part.json", partBytes); entry(prefix + "audio.mp3", audio)
                entry(prefix + "diagram.png", image); entry(prefix + "layout.json", layout)
            }
        }.toByteArray()
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

    @Test fun v4ImportValidatesRawPartImageAndSlotPlacementBeforeAtomicCataloguePublish() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "offline-layout-v4-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val importer = OfflinePackImporter(root)
            val fixture = partWithImage()
            val installed = importer.install(ByteArrayInputStream(v4Pack(fixture)))
            assertEquals("The pack-index schema remains v2", 2, installed.schemaVersion)
            assertNotNull(installed.parts.single().layoutSha256)
            val activeCatalog = importer.installed()
            val activeFolder = activeCatalog.getValue(fixture.book)
            val activeDirectory = File(root, activeFolder)
            assertTrue(activeDirectory.isDirectory)

            val repository = PartRepository(InstrumentationRegistry.getInstrumentation().targetContext.assets, root)
            repository.loadParts()
            val installedPart = repository.loadPart(fixture.id)
            assertEquals(fixture, installedPart)
            assertNotNull("V4 remains bound to the installed raw Part bytes", repository.loadLayoutV4(installedPart))
            val decodedImage = repository.readImage(fixture.id, "diagram.png")
            assertNotNull("Image decoding follows bytes, not the .png filename", decodedImage)
            assertEquals(100, decodedImage!!.width)
            assertEquals(40, decodedImage.height)
            decodedImage.recycle()

            val expectedErrors = mapOf(
                "layoutHash" to "Layout checksum mismatch",
                "partBinding" to "BASE_PART_HASH_MISMATCH",
                "imageDimensions" to "IMAGE_DIMENSIONS_MISMATCH",
                "duplicateSlot" to "SLOT_DISPLAY_COUNT_INVALID",
                "outsideHotspot" to "OVERLAY_OUT_OF_BOUNDS",
                "deepJson" to "LAYOUT_NESTING_TOO_DEEP",
            )
            expectedErrors.forEach { (corruption, expected) ->
                try {
                    importer.install(ByteArrayInputStream(v4Pack(fixture, corruption)))
                    fail("Corrupt V4 pack should be rejected: $corruption")
                } catch (error: IllegalArgumentException) {
                    assertTrue("$corruption should report $expected, got ${error.message}",
                        error.message.orEmpty().contains(expected, ignoreCase = true))
                }
                assertEquals("Rejected $corruption pack cannot move catalogue pointer", activeCatalog, importer.installed())
                assertTrue("Rejected $corruption pack keeps prior installed directory", activeDirectory.isDirectory)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun batchReimportReportsLayoutUpdateAndIdenticalRepeatAsAlreadyInstalled() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "offline-layout-batch-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val importer = OfflinePackImporter(root)
            val original = part()
            val batch = BatchPackImporter(
                listParts = {
                    importer.installed().values.flatMap { folder ->
                        json.decodeFromString<BookIndex>(File(root, "$folder/index.json").readText()).parts
                    }
                },
                install = { input, progress -> importer.install(input, onProgress = progress) },
            )
            fun source(name: String, withLayout: Boolean) = PackImportSource(
                name = { name },
                open = { ByteArrayInputStream(pack(original, withLayout)) },
            )

            val initial = batch.run(listOf(source("legacy.eelpack", false)), {}, {})
            assertEquals(ImportOutcome.INSTALLED, initial.results.single().outcome)
            val update = batch.run(listOf(source("with-layout.eelpack", true)), {}, {})
            assertEquals(ImportOutcome.UPDATED, update.results.single().outcome)
            assertEquals(1, update.updatedLayoutParts)
            assertEquals(1, update.succeeded)

            val repeated = batch.run(listOf(source("same-layout.eelpack", true)), {}, {})
            assertEquals(ImportOutcome.ALREADY_INSTALLED, repeated.results.single().outcome)
            assertEquals(0, repeated.updatedLayoutParts)
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
