package com.eenglish.listening

import com.eenglish.listening.data.assets.LayoutV4Codec
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.data.repository.validateV4ForDisplayedPart
import com.eenglish.listening.domain.layout.LayoutV4ImageMetadata
import com.eenglish.listening.domain.layout.LayoutV4AnswerStatus
import com.eenglish.listening.domain.layout.LayoutV4Validator
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class LayoutV4ValidatorTest {
    private data class Fixture(
        val part: ListeningPart,
        val partBytes: ByteArray,
        val layout: JsonObject,
        val images: Map<String, LayoutV4ImageMetadata>,
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun fixture(answerSeparator: String? = "and", partId: String = "cambridge-5-test-1-part-1",
        book: Int = 5, test: Int = 1, partNumber: Int = 1): Fixture {
        val firstNumber = (partNumber - 1) * 10 + 1
        val questions = (firstNumber until firstNumber + 10).mapIndexed { index, number ->
            val paired = index == 0
            Question(
                id = "$partId-q$number",
                number = number,
                type = QuestionType.TEXT_INPUT,
                prompt = if (paired) "Question 1 _____ and _____" else "Question $number fixture",
                options = emptyList(),
                correctAnswer = if (paired) "left and right" else "fixture",
                acceptedAnswers = listOf(if (paired) "left and right" else "fixture"),
                instructions = "Write no more than two words",
                images = if (number == firstNumber) listOf("diagram.png") else emptyList(),
                wordLimit = WordLimit(2, 0),
                answerSeparator = if (paired) answerSeparator else null,
            )
        }
        val part = ListeningPart(2, partId, "IELTS", book, test, partNumber,
            "Synthetic", "Write answers", "listening/$partId/audio.mp3",
            "a".repeat(64), Transcript("English", "中文", listOf(TranscriptSegment("English", "中文"))), questions).validate()
        val partBytes = Json.encodeToString(part).toByteArray(Charsets.UTF_8)
        val slots = questions.flatMap { question ->
            if (question.number == firstNumber) listOf(slot(partId, question.number, 0), slot(partId, question.number, 1))
            else listOf(slot(partId, question.number, 0))
        }
        val responses = buildList {
            add(buildJsonObject {
                put("kind", "fill"); put("questionIds", buildJsonArray { add(questions.first().id) })
                put("slotIds", buildJsonArray { add("slot-q$firstNumber-0"); add("slot-q$firstNumber-1") })
                put("scoring", "legacyPerQuestion"); put("joiner", "and")
            })
            questions.drop(1).forEach { question -> add(buildJsonObject {
                put("kind", "fill"); put("questionIds", buildJsonArray { add(question.id) })
                put("slotIds", buildJsonArray { add("slot-q${question.number}-0") })
                put("scoring", "legacyPerQuestion")
            }) }
        }
        val groupBlocks = buildList {
            add(textBlock(questions.first()))
            add(buildJsonObject {
                put("id", "b-image"); put("type", "image"); put("asset", "diagram.png")
                put("widthPx", 100); put("heightPx", 40)
                put("overlays", buildJsonArray {
                    add(overlay("slot-q$firstNumber-0", 1)); add(overlay("slot-q$firstNumber-1", 55))
                })
                put("printedConnectors", buildJsonArray { })
            })
            questions.drop(1).forEach { question ->
                add(buildJsonObject {
                    put("id", "row-q${question.number}"); put("type", "formRow")
                    put("children", buildJsonArray { add(textBlock(question)); add(slotBlock(partId, question.number, 0)) })
                })
            }
        }
        val group = buildJsonObject {
            put("groupId", "${part.id}-layout-synthetic-q${firstNumber}-q${firstNumber + 9}")
            put("questionIds", buildJsonArray { questions.forEach { add(it.id) } })
            put("promptHashes", buildJsonObject { questions.forEach { put(it.id, sha(it.prompt.toByteArray())) } })
            put("instructions", buildJsonArray { })
            put("optionPools", buildJsonArray { })
            put("slots", buildJsonArray { slots.forEach(::add) })
            put("responses", buildJsonArray { responses.forEach(::add) })
            put("blocks", buildJsonArray { groupBlocks.forEach(::add) })
        }
        val layout = buildJsonObject {
            put("schemaVersion", 4); put("partId", part.id); put("basePartSha256", sha(partBytes))
            put("groups", buildJsonArray { add(group) })
        }
        val imageHash = "b".repeat(64)
        return Fixture(part, partBytes, layout, mapOf("diagram.png" to LayoutV4ImageMetadata(imageHash, 100, 40)))
    }

    private fun slot(partId: String, number: Int, index: Int) = buildJsonObject {
        put("slotId", "slot-q$number-$index"); put("questionId", "$partId-q$number")
        put("slotIndex", index); put("kind", "fill")
    }

    private fun slotBlock(partId: String, number: Int, index: Int) = buildJsonObject {
        put("id", "b-slot-q$number-$index"); put("type", "slot"); put("slotId", "slot-q$number-$index")
    }

    private fun textBlock(question: Question) = buildJsonObject {
        put("id", "b-text-q${question.number}"); put("type", "text"); put("textId", "t-q${question.number}")
        put("text", question.prompt)
        put("source", buildJsonObject {
            put("kind", "partField"); put("field", "question.prompt"); put("questionId", question.id)
            put("startUtf16", 0); put("endUtf16", question.prompt.length)
        })
        put("questionId", question.id)
    }

    private fun overlay(slotId: String, x: Int) = buildJsonObject {
        put("slotId", slotId); put("rectPx", rect(x, 4, 20, 12))
        put("evidence", buildJsonObject {
            put("kind", "modelMeasurement"); put("measurementId", "measurement-$slotId")
            put("imageSha256", "b".repeat(64))
        })
    }

    private fun rect(x: Int, y: Int, width: Int, height: Int) = buildJsonObject {
        put("x", x); put("y", y); put("width", width); put("height", height)
    }

    private fun withGroup(fixture: Fixture, transform: (JsonObject) -> JsonObject): JsonObject {
        val groups = fixture.layout.getValue("groups").jsonArray
        val group = transform(groups.single().jsonObject)
        return JsonObject(fixture.layout.toMutableMap() + ("groups" to JsonArray(listOf(group))))
    }

    @Test fun acceptsOrdinaryFillAndReviewedJoinerDoubleFillOnImage() {
        val f = fixture()
        assertSame(f.layout, LayoutV4Validator.validate(f.layout, f.part, f.partBytes, f.images))
        val noLegacySeparator = fixture(answerSeparator = null)
        assertSame(noLegacySeparator.layout, LayoutV4Validator.validate(noLegacySeparator.layout,
            noLegacySeparator.part, noLegacySeparator.partBytes, noLegacySeparator.images))
    }

    @Test fun bundledC9PartUsesPackRawBytesForV4BindingWhenDecodedPartIsEqual() {
        val f = fixture(partId = "cambridge-9-test-1-part-3", book = 9, test = 1, partNumber = 3)
        val packPartBytes = ("\n" + f.partBytes.toString(Charsets.UTF_8) + "\n").toByteArray(Charsets.UTF_8)
        assertNotEquals(sha(f.partBytes), sha(packPartBytes))
        assertEquals(f.part, PartCodec.decode(packPartBytes.toString(Charsets.UTF_8)))
        val packBoundLayout = JsonObject(f.layout.toMutableMap() + ("basePartSha256" to JsonPrimitive(sha(packPartBytes))))
        val layoutBytes = Json.encodeToString(packBoundLayout).toByteArray(Charsets.UTF_8)
        assertEquals(packBoundLayout, validateV4ForDisplayedPart(f.part, packPartBytes, layoutBytes, f.images))

        val sampleBoundLayoutBytes = Json.encodeToString(f.layout).toByteArray(Charsets.UTF_8)
        assertEquals("BASE_PART_HASH_MISMATCH", assertThrows(IllegalArgumentException::class.java) {
            validateV4ForDisplayedPart(f.part, packPartBytes, sampleBoundLayoutBytes, f.images)
        }.message)
    }

    @Test fun pairedFillCountsAsIncompleteUntilBothSlotsHaveValues() {
        val f = fixture(answerSeparator = null)
        val paired = f.part.questions.first()
        assertEquals(1, LayoutV4AnswerStatus.incompletePairedFillCount(
            f.part.questions, f.layout, mapOf(paired.id to "left and ")))
        assertEquals(1, LayoutV4AnswerStatus.missingCount(
            listOf(paired), f.layout, mapOf(paired.id to "left and ")))
        assertEquals(1, LayoutV4AnswerStatus.incompletePairedFillCount(
            f.part.questions, f.layout, mapOf(paired.id to " and right")))
        assertEquals(0, LayoutV4AnswerStatus.incompletePairedFillCount(
            f.part.questions, f.layout, mapOf(paired.id to "left and right")))
        assertEquals(0, LayoutV4AnswerStatus.missingCount(
            listOf(paired), f.layout, mapOf(paired.id to "left and right")))
        assertEquals(0, LayoutV4AnswerStatus.incompletePairedFillCount(
            f.part.questions, f.layout, mapOf(f.part.questions[1].id to "fixture")))
    }

    @Test fun realLegacySeparatorPairsAreCountedOnceInTheCombinedMissingTotal() {
        val root = findRepositoryRoot()
        val candidateRoot = File(root, "private-data/layout-v4-unified-candidates/listening")
        val packRoot = File(root, "private-data/library/pending-six/updated-library")
        assertTrue("Real V4 candidates must be present", candidateRoot.isDirectory)
        assertTrue("Baseline source packs must be present", packRoot.isDirectory)
        val packs = mutableMapOf<Int, ZipFile>()
        try {
            var checked = 0
            candidateRoot.listFiles().orEmpty().sortedBy { it.name }.forEach { partDir ->
                val rawLayout = File(partDir, "layout.json").takeIf(File::isFile)?.readBytes() ?: return@forEach
                val partId = partDir.name
                val book = Regex("^cambridge-(\\d+)-").find(partId)!!.groupValues[1].toInt()
                val pack = packs.getOrPut(book) { ZipFile(File(packRoot, "cambridge-$book.eelpack")) }
                val partBytes = pack.getInputStream(pack.getEntry("listening/$partId/part.json")).use { it.readBytes() }
                val part = PartCodec.decode(partBytes.toString(Charsets.UTF_8))
                val layout = LayoutV4Codec.decode(rawLayout)
                layout.getValue("groups").jsonArray.forEach { groupElement ->
                    val group = groupElement.jsonObject
                    group.getValue("responses").jsonArray.map { it.jsonObject }
                        .filter { response -> response["kind"]?.jsonPrimitive?.content == "fill" &&
                            response["slotIds"]?.jsonArray?.size == 2 && response["questionIds"]?.jsonArray?.size == 1 }
                        .forEach responseLoop@ { response ->
                            val id = response.getValue("questionIds").jsonArray.single().jsonPrimitive.content
                            val question = part.questions.single { it.id == id }
                            val separator = question.answerSeparator ?: return@responseLoop
                            val partial = "first $separator "
                            val answers = part.questions.associate { q ->
                                q.id to (q.acceptableAnswers.firstOrNull() ?: q.correctAnswer)
                            }.toMutableMap()
                            part.questions.filter { it.type == QuestionType.MULTIPLE_CHOICE }
                                .groupBy { it.groupId }.values.forEach { multi ->
                                    val stored = multi.map { it.correctAnswer.uppercase() }.distinct().sorted().joinToString(",")
                                    multi.forEach { answers[it.id] = stored }
                                }
                            assertEquals("$partId starts with all questions answered", 0,
                                LayoutV4AnswerStatus.missingCount(part.questions, layout, answers))
                            answers[id] = partial
                            assertEquals("$partId Q${question.number} is already counted by Grader", 1,
                                Grader.missingCount(part.questions, answers))
                            val combined = LayoutV4AnswerStatus.missingCount(part.questions, layout, answers)
                            assertEquals("$partId Q${question.number} must count once", 1, combined)
                            checked++
                        }
                }
            }
            assertEquals("The five real legacy-separated paired fills must be covered", 5, checked)
        } finally {
            packs.values.forEach(ZipFile::close)
        }
    }

    @Test fun codecRejectsDeepJsonBeforeBuildingAJsonTree() {
        val depth = 2_000
        val bytes = ("{\"schemaVersion\":4,\"nested\":" + "[".repeat(depth) + "0" + "]".repeat(depth) + "}")
            .toByteArray(Charsets.UTF_8)
        assertEquals("LAYOUT_NESTING_TOO_DEEP", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Codec.decode(bytes)
        }.message)
    }

    @Test fun rejectsWrongSchemaBaseBindingAndSeparatorMismatch() {
        val f = fixture()
        assertEquals("UNSUPPORTED_LAYOUT_SCHEMA", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Validator.validate(JsonObject(f.layout.toMutableMap() + ("schemaVersion" to JsonPrimitive(3))),
                f.part, f.partBytes, f.images)
        }.message)
        assertEquals("BASE_PART_HASH_MISMATCH", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Validator.validate(JsonObject(f.layout.toMutableMap() + ("basePartSha256" to JsonPrimitive("c".repeat(64)))),
                f.part, f.partBytes, f.images)
        }.message)

        val mismatch = fixture(answerSeparator = "or")
        assertEquals("ANSWER_SEPARATOR_MISMATCH", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Validator.validate(mismatch.layout, mismatch.part, mismatch.partBytes, mismatch.images)
        }.message)
    }

    @Test fun rejectsMissingImageEvidenceMetadataDuplicateSlotAndOutOfBoundsOverlay() {
        val f = fixture()
        assertEquals("IMAGE_METADATA_MISSING", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Validator.validate(f.layout, f.part, f.partBytes, emptyMap())
        }.message)
        val group = f.layout.getValue("groups").jsonArray.single().jsonObject
        val image = group.getValue("blocks").jsonArray[1].jsonObject
        val overlays = image.getValue("overlays").jsonArray

        val duplicateImage = JsonObject(image.toMutableMap() + ("overlays" to JsonArray(overlays + overlays[0])))
        val duplicate = withGroup(f) { g ->
            val blocks = g.getValue("blocks").jsonArray.toMutableList()
            blocks[1] = duplicateImage
            JsonObject(g.toMutableMap() + ("blocks" to JsonArray(blocks)))
        }
        assertEquals("SLOT_DISPLAY_COUNT_INVALID", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Validator.validate(duplicate, f.part, f.partBytes, f.images)
        }.message)

        val outside = withGroup(f) { g ->
            val blocks = g.getValue("blocks").jsonArray.toMutableList()
            val badOverlay = JsonObject(overlays[0].jsonObject.toMutableMap() +
                ("rectPx" to rect(95, 4, 20, 12)))
            blocks[1] = JsonObject(image.toMutableMap() + ("overlays" to JsonArray(listOf(badOverlay, overlays[1]))))
            JsonObject(g.toMutableMap() + ("blocks" to JsonArray(blocks)))
        }
        assertEquals("OVERLAY_OUT_OF_BOUNDS", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Validator.validate(outside, f.part, f.partBytes, f.images)
        }.message)

        assertEquals("IMAGE_DIMENSIONS_MISMATCH", assertThrows(IllegalArgumentException::class.java) {
            LayoutV4Validator.validate(f.layout, f.part, f.partBytes,
                mapOf("diagram.png" to LayoutV4ImageMetadata("b".repeat(64), 99, 40)))
        }.message)
    }

    @Test fun androidRuntimeSubsetAcceptsAll272PrivateV4LayoutsAndPackImages() {
        val root = findRepositoryRoot()
        val candidateRoot = File(root, "private-data/layout-v4-unified-candidates/listening")
        val packRoot = File(root, "private-data/library/pending-six/updated-library")
        if (!candidateRoot.isDirectory || !packRoot.isDirectory) return
        val packs = mutableMapOf<Int, ZipFile>()
        try {
            var count = 0
            candidateRoot.listFiles().orEmpty().sortedBy { it.name }.forEach { partDir ->
                val layoutFile = File(partDir, "layout.json")
                if (!layoutFile.isFile) return@forEach
                val partId = partDir.name
                val book = Regex("^cambridge-(\\d+)-").find(partId)!!.groupValues[1].toInt()
                val pack = packs.getOrPut(book) { ZipFile(File(packRoot, "cambridge-$book.eelpack")) }
                val prefix = "listening/$partId/"
                val partBytes = pack.getInputStream(pack.getEntry(prefix + "part.json")).use { it.readBytes() }
                val part = PartCodec.decode(partBytes.toString(Charsets.UTF_8))
                val rawLayout = layoutFile.readBytes()
                val layout = LayoutV4Codec.decode(rawLayout)
                val assets = layout.getValue("groups").jsonArray.flatMap { group ->
                    group.jsonObject.getValue("blocks").jsonArray.flatMap { block -> listOf(block.jsonObject) + nestedBlocks(block.jsonObject) }
                }.mapNotNull { obj -> if (obj["type"]?.jsonPrimitive?.content == "image") obj["asset"]?.jsonPrimitive?.content else null }.toSet()
                val images = assets.associateWith { asset ->
                    val raw = pack.getInputStream(pack.getEntry(prefix + asset)).use { it.readBytes() }
                    val dimensions = imageDimensions(raw) ?: error("Undecodable image: $partId/$asset")
                    LayoutV4ImageMetadata(sha(raw), dimensions.first, dimensions.second)
                }
                LayoutV4Validator.validate(layout, part, partBytes, images)
                count++
            }
            assertEquals("Every package-bound candidate should pass Android's available runtime checks", 272, count)
        } finally {
            packs.values.forEach(ZipFile::close)
        }
    }

    private fun findRepositoryRoot(): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            if (File(current, "tools/layout_v4_validator.py").isFile) return current
            current = current.parentFile
        }
        error("Cannot locate repository root from ${System.getProperty("user.dir")}")
    }

    private fun imageDimensions(raw: ByteArray): Pair<Int, Int>? {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (raw.size >= 24 && raw.copyOfRange(0, 8).contentEquals(png)) {
            fun uint32(offset: Int) = ((raw[offset].toLong() and 0xff) shl 24 or
                ((raw[offset + 1].toLong() and 0xff) shl 16) or
                ((raw[offset + 2].toLong() and 0xff) shl 8) or
                (raw[offset + 3].toLong() and 0xff)).toInt()
            return uint32(16) to uint32(20)
        }
        if (raw.size < 4 || raw[0] != 0xff.toByte() || raw[1] != 0xd8.toByte()) return null
        var position = 2
        while (position + 8 < raw.size) {
            while (position < raw.size && raw[position] != 0xff.toByte()) position++
            while (position < raw.size && raw[position] == 0xff.toByte()) position++
            if (position >= raw.size) return null
            val marker = raw[position++].toInt() and 0xff
            if (marker == 0xd9 || marker == 0xda) return null
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            if (position + 1 >= raw.size) return null
            val length = ((raw[position].toInt() and 0xff) shl 8) or (raw[position + 1].toInt() and 0xff)
            if (length < 2 || position + length > raw.size) return null
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                val height = ((raw[position + 3].toInt() and 0xff) shl 8) or (raw[position + 4].toInt() and 0xff)
                val width = ((raw[position + 5].toInt() and 0xff) shl 8) or (raw[position + 6].toInt() and 0xff)
                return width to height
            }
            position += length
        }
        return null
    }

    private fun nestedBlocks(block: JsonObject): List<JsonObject> = buildList {
        block["children"]?.jsonArray?.forEach { child ->
            child.jsonObject.let { add(it); addAll(nestedBlocks(it)) }
        }
        block["rows"]?.jsonArray?.forEach { row -> row.jsonObject["cells"]?.jsonArray?.forEach { cell ->
            cell.jsonObject["children"]?.jsonArray?.forEach { child -> child.jsonObject.let { add(it); addAll(nestedBlocks(it)) } }
        } }
        block["nodes"]?.jsonArray?.forEach { node -> node.jsonObject["children"]?.jsonArray?.forEach { child ->
            child.jsonObject.let { add(it); addAll(nestedBlocks(it)) }
        } }
    }
}
