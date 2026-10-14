package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionType
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Dimensions and digest derived from the packaged image bytes (not from layout claims). */
data class LayoutV4ImageMetadata(val sha256: String, val widthPx: Int, val heightPx: Int)

/** Runtime checks for packaged v4 display data. Original HTML/TXT provenance remains an offline check. */
object LayoutV4Validator {
    private val shaPattern = Regex("[a-f0-9]{64}")
    private val idPattern = Regex("[a-z][a-z0-9-]{0,79}")
    private const val MAX_GROUPS = 64
    private const val MAX_BLOCKS = 2_048
    private const val MAX_BLOCKS_PER_GROUP = 512
    private const val MAX_TEXT = 1_000_000

    fun validate(
        layout: JsonObject,
        part: ListeningPart,
        partJsonBytes: ByteArray,
        imageMetadata: Map<String, LayoutV4ImageMetadata>,
    ): JsonObject {
        require(layout.int("schemaVersion") == 4) { "UNSUPPORTED_LAYOUT_SCHEMA" }
        require(layout.string("partId") == part.id) { "PART_ID_MISMATCH" }
        require(layout.string("basePartSha256").matches(shaPattern)) { "INVALID_BASE_PART_HASH" }
        require(sha256(partJsonBytes) == layout.string("basePartSha256")) { "BASE_PART_HASH_MISMATCH" }
        val groups = layout.array("groups")
        require(groups.isNotEmpty() && groups.size <= MAX_GROUPS) { "INVALID_GROUPS" }
        val byId = part.questions.associateBy { it.id }
        require(byId.size == part.questions.size) { "INVALID_PART_QUESTION_MAP" }
        val occupiedQuestions = mutableSetOf<String>()
        val groupIds = mutableSetOf<String>()
        var totalBlocks = 0
        var totalText = 0

        groups.forEach { groupElement ->
            val group = groupElement.obj("INVALID_GROUP")
            require(group.string("groupId").matches(idPattern) && groupIds.add(group.string("groupId"))) {
                "INVALID_GROUP_ID"
            }
            val questionIds = group.stringArray("questionIds")
            require(questionIds.isNotEmpty() && questionIds.size <= 10 && questionIds.distinct().size == questionIds.size) {
                "INVALID_GROUP_QUESTIONS"
            }
            require(questionIds.all { it in byId } && questionIds.none { it in occupiedQuestions }) {
                "QUESTION_COVERAGE_INVALID"
            }
            occupiedQuestions += questionIds
            val prompts = group.objectField("promptHashes")
            require(prompts.keys == questionIds.toSet()) { "PROMPT_HASH_SET_MISMATCH" }
            questionIds.forEach { id ->
                val expected = sha256(byId.getValue(id).prompt.toByteArray(Charsets.UTF_8))
                require(prompts[id]?.primitiveContent() == expected) { "PROMPT_HASH_MISMATCH" }
            }
            val instructions = group.array("instructions")
            val blocks = group.array("blocks")
            require(blocks.isNotEmpty() && blocks.size <= MAX_BLOCKS_PER_GROUP) { "INVALID_BLOCKS" }
            val slots = validateSlots(group, questionIds, byId)
            val pools = validatePools(group, questionIds, byId)
            val responses = validateResponses(group, questionIds, slots, pools, byId)
            val displayedSlots = mutableMapOf<String, Int>()
            var localBlocks = 0

            fun visit(block: JsonObject, depth: Int) {
                require(depth <= 8) { "LAYOUT_NESTING_TOO_DEEP" }
                localBlocks++
                totalBlocks++
                require(localBlocks <= MAX_BLOCKS_PER_GROUP && totalBlocks <= MAX_BLOCKS) { "TOO_MANY_BLOCKS" }
                when (block.string("type")) {
                    "text" -> {
                        val text = block.string("text")
                        totalText += text.length
                        require(text.isNotEmpty() && totalText <= MAX_TEXT) { "LAYOUT_TEXT_TOO_LARGE" }
                        validateText(block, part, byId, questionIds)
                    }
                    "slot" -> {
                        val slotId = block.string("slotId")
                        require(slotId in slots) { "UNKNOWN_SLOT_REFERENCE" }
                        displayedSlots[slotId] = (displayedSlots[slotId] ?: 0) + 1
                    }
                    "choiceList" -> {
                        val poolId = block.string("optionPoolId")
                        require(poolId in pools) { "UNKNOWN_OPTION_POOL" }
                        val slotIds = block.stringArray("slotIds")
                        require(slotIds.isNotEmpty() && slotIds.distinct().size == slotIds.size && slotIds.all { it in slots }) {
                            "INVALID_CHOICE_LIST"
                        }
                        require(slotIds.all { slots.getValue(it).kind != "fill" }) { "INVALID_CHOICE_LIST" }
                        require(slotIds.all { slots.getValue(it).questionId in pools.getValue(poolId) }) {
                            "OPTION_POOL_REFERENCE_MISMATCH"
                        }
                        slotIds.forEach { slotId -> displayedSlots[slotId] = (displayedSlots[slotId] ?: 0) + 1 }
                    }
                    "image" -> {
                        val asset = block.string("asset")
                        require(asset.matches(Regex("[A-Za-z0-9_-]+\\.(?:png|jpg|jpeg|webp)"))) { "INVALID_IMAGE_ASSET" }
                        require(part.questions.filter { it.id in questionIds }.any { asset in it.images }) { "IMAGE_NOT_IN_PART" }
                        val actual = imageMetadata[asset] ?: throw IllegalArgumentException("IMAGE_METADATA_MISSING")
                        require(block.int("widthPx") == actual.widthPx && block.int("heightPx") == actual.heightPx) {
                            "IMAGE_DIMENSIONS_MISMATCH"
                        }
                        val overlays = block.array("overlays")
                        overlays.forEach { element ->
                            val overlay = element.obj("INVALID_OVERLAY")
                            val slotId = overlay.string("slotId")
                            val slot = slots[slotId] ?: throw IllegalArgumentException("UNKNOWN_SLOT_REFERENCE")
                            require(slot.kind in setOf("fill", "singleChoice", "matching")) { "UNSUPPORTED_OVERLAY_SLOT" }
                            val rect = overlay.objectField("rectPx")
                            val x = rect.int("x"); val y = rect.int("y")
                            val width = rect.int("width"); val height = rect.int("height")
                            require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
                                x.toLong() + width <= actual.widthPx && y.toLong() + height <= actual.heightPx) {
                                "OVERLAY_OUT_OF_BOUNDS"
                            }
                            val evidence = overlay.objectField("evidence")
                            require(evidence.string("kind") in setOf("reviewedMeasurement", "modelMeasurement")) {
                                "INVALID_IMAGE_EVIDENCE"
                            }
                            require(evidence.string("imageSha256") == actual.sha256) { "IMAGE_EVIDENCE_HASH_MISMATCH" }
                            displayedSlots[slotId] = (displayedSlots[slotId] ?: 0) + 1
                        }
                        block.array("printedConnectors").forEach { element ->
                            val connector = element.obj("INVALID_PRINTED_CONNECTOR")
                            require(connector.string("questionId") in questionIds && connector.string("text").isNotBlank()) {
                                "INVALID_PRINTED_CONNECTOR"
                            }
                            val rect = connector.objectField("rectPx")
                            val x = rect.int("x"); val y = rect.int("y")
                            val width = rect.int("width"); val height = rect.int("height")
                            require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
                                x.toLong() + width <= actual.widthPx && y.toLong() + height <= actual.heightPx) {
                                "OVERLAY_OUT_OF_BOUNDS"
                            }
                            val evidence = connector.objectField("evidence")
                            require(evidence.string("kind") == "reviewedMeasurement" &&
                                evidence.string("imageSha256") == actual.sha256) { "INVALID_IMAGE_EVIDENCE" }
                        }
                    }
                    "paragraph", "formRow" -> block.array("children").forEach { visit(it.obj("INVALID_BLOCK"), depth + 1) }
                    "listItem" -> {
                        require(block.string("marker") in setOf("bullet", "numbered", "none")) { "INVALID_LIST_MARKER" }
                        block.array("children").forEach { visit(it.obj("INVALID_BLOCK"), depth + 1) }
                    }
                    "table" -> block.array("rows").forEach { rowElement ->
                        rowElement.obj("INVALID_TABLE_ROW").array("cells").forEach { cellElement ->
                            cellElement.obj("INVALID_TABLE_CELL").array("children")
                                .forEach { visit(it.obj("INVALID_BLOCK"), depth + 1) }
                        }
                    }
                    "flow" -> {
                        val flow = block.array("nodes")
                        val nodeIds = flow.map { it.obj("INVALID_FLOW_NODE").string("id") }
                        require(nodeIds.distinct().size == nodeIds.size) { "INVALID_FLOW_NODES" }
                        flow.forEach { node -> node.obj("INVALID_FLOW_NODE").array("children")
                            .forEach { visit(it.obj("INVALID_BLOCK"), depth + 1) } }
                        block.array("edges").forEach { edgeElement ->
                            val edge = edgeElement.obj("INVALID_FLOW_EDGE")
                            require(edge.string("from") in nodeIds && edge.string("to") in nodeIds) { "INVALID_FLOW_EDGE" }
                        }
                    }
                    else -> throw IllegalArgumentException("UNSUPPORTED_BLOCK_TYPE")
                }
            }
            instructions.forEach { visit(it.obj("INVALID_INSTRUCTION"), 0) }
            blocks.forEach { visit(it.obj("INVALID_BLOCK"), 0) }
            slots.keys.forEach { slotId -> require(displayedSlots[slotId] == 1) { "SLOT_DISPLAY_COUNT_INVALID" } }
            require(displayedSlots.keys == slots.keys) { "SLOT_DISPLAY_COUNT_INVALID" }
            require(responses.keys == slots.keys) { "SLOT_RESPONSE_COUNT_INVALID" }
        }
        require(occupiedQuestions == byId.keys) { "QUESTION_COVERAGE_INVALID" }
        return layout
    }

    private data class Slot(val id: String, val questionId: String, val index: Int, val kind: String)

    private fun validateSlots(group: JsonObject, questionIds: List<String>, byId: Map<String, Question>): Map<String, Slot> {
        val result = linkedMapOf<String, Slot>()
        group.array("slots").forEach { element ->
            val slot = element.obj("INVALID_SLOT")
            val id = slot.string("slotId")
            val questionId = slot.string("questionId")
            val index = slot.int("slotIndex")
            val kind = slot.string("kind")
            require(id.matches(idPattern) && questionId in questionIds && index in 0..1 &&
                kind in setOf("fill", "singleChoice", "matching", "multipleChoice") &&
                result.put(id, Slot(id, questionId, index, kind)) == null) {
                "INVALID_SLOT"
            }
            val question = byId.getValue(questionId)
            when (kind) {
                "fill" -> require(question.isTextInput) { "SLOT_QUESTION_TYPE_MISMATCH" }
                "singleChoice" -> require(question.type == QuestionType.SINGLE_CHOICE ||
                    question.type == QuestionType.IMAGE_BASED && question.options.isNotEmpty()) { "SLOT_QUESTION_TYPE_MISMATCH" }
                "matching" -> require(question.type == QuestionType.MATCHING ||
                    question.type == QuestionType.IMAGE_BASED && question.options.isNotEmpty()) { "SLOT_QUESTION_TYPE_MISMATCH" }
                "multipleChoice" -> require(question.type == QuestionType.MULTIPLE_CHOICE) { "SLOT_QUESTION_TYPE_MISMATCH" }
            }
        }
        require(result.isNotEmpty()) { "INVALID_SLOT" }
        questionIds.forEach { questionId ->
            val qSlots = result.values.filter { it.questionId == questionId }.sortedBy { it.index }
            require(qSlots.map { it.index } == qSlots.indices.toList() && qSlots.size in 1..2) { "INVALID_SLOT_SHAPE" }
            if (qSlots.size == 2) require(qSlots.all { it.kind == "fill" }) { "INVALID_SLOT_SHAPE" }
        }
        return result
    }

    private fun validatePools(group: JsonObject, questionIds: List<String>, byId: Map<String, Question>): Map<String, Set<String>> {
        val result = linkedMapOf<String, Set<String>>()
        group.array("optionPools").forEach { element ->
            val pool = element.obj("INVALID_OPTION_POOL")
            val poolId = pool.string("poolId")
            val questions = pool.stringArray("questionIds")
            val optionIds = pool.stringArray("optionIds")
            require(poolId.matches(idPattern) && poolId !in result && questions.isNotEmpty() &&
                questions.distinct().size == questions.size && questions.all { it in questionIds } &&
                optionIds.isNotEmpty() && optionIds.distinct().size == optionIds.size) { "INVALID_OPTION_POOL" }
            questions.forEach { questionId ->
                val actual = byId.getValue(questionId).options.map { it.id }.toSet()
                require(optionIds.toSet() == actual) { "OPTION_POOL_REFERENCE_MISMATCH" }
            }
            result[poolId] = questions.toSet()
        }
        return result
    }

    private fun validateResponses(
        group: JsonObject,
        questionIds: List<String>,
        slots: Map<String, Slot>,
        pools: Map<String, Set<String>>,
        byId: Map<String, Question>,
    ): Map<String, String> {
        val covered = mutableSetOf<String>()
        val slotResponses = mutableMapOf<String, String>()
        group.array("responses").forEach { element ->
            val response = element.obj("INVALID_RESPONSE")
            val kind = response.string("kind")
            val qIds = response.stringArray("questionIds")
            val slotIds = response.stringArray("slotIds")
            require(kind in setOf("fill", "singleChoice", "matching", "multipleChoice") &&
                qIds.isNotEmpty() && qIds.distinct().size == qIds.size && qIds.all { it in questionIds } &&
                slotIds.isNotEmpty() && slotIds.distinct().size == slotIds.size && slotIds.all { it in slots }) {
                "INVALID_RESPONSE"
            }
            require(qIds.none { !covered.add(it) }) { "QUESTION_RESPONSE_DUPLICATE" }
            require(response.string("scoring") in setOf("legacyPerQuestion", "onePointPerCorrectOption")) {
                "INVALID_SCORING_RULE"
            }
            val qSlots = slotIds.map { slots.getValue(it) }
            require(qSlots.map { it.questionId }.toSet() == qIds.toSet()) { "RESPONSE_SLOT_MISMATCH" }
            require(qSlots.all { it.kind == kind }) {
                "RESPONSE_SLOT_MISMATCH"
            }
            if (kind == "fill") {
                require(qIds.size == 1 && qSlots.size in 1..2) { "INVALID_FILL_RESPONSE" }
                val question = byId.getValue(qIds.single())
                if (qSlots.size == 2) {
                    val joiner = response.string("joiner")
                    require(joiner in setOf("and", "or", "to")) { "INVALID_FILL_RESPONSE" }
                    if (question.answerSeparator != null) require(question.answerSeparator.equals(joiner, true)) {
                        "ANSWER_SEPARATOR_MISMATCH"
                    } else {
                        require(question.acceptableAnswers.any { answer ->
                            val parts = answer.split(Regex("\\s+${Regex.escape(joiner)}\\s+", RegexOption.IGNORE_CASE))
                            parts.size == 2 && Grader.normalize(parts.joinToString(" $joiner ")) == Grader.normalize(answer)
                        }) { "UNBOUND_JOINER" }
                    }
                } else require(question.answerSeparator == null && response["joiner"] == null) { "INVALID_FILL_RESPONSE" }
            } else if (kind == "multipleChoice") {
                require(qIds.size >= 2 && response.string("scoring") == "onePointPerCorrectOption" &&
                    response.string("storage") == "sameSortedSetOnEveryQuestion" && response.string("order") == "unordered") {
                    "INVALID_MULTIPLE_CHOICE_RESPONSE"
                }
                require(qIds.all { byId.getValue(it).type == QuestionType.MULTIPLE_CHOICE } &&
                    qIds.map { byId.getValue(it).groupId }.distinct().size == 1 &&
                    response.int("selectCount") == qIds.size) { "INVALID_MULTIPLE_CHOICE_RESPONSE" }
                validatePoolReference(response, qIds, pools)
            } else {
                require(qIds.all { questionId ->
                    val question = byId.getValue(questionId)
                    when (kind) {
                        "singleChoice" -> question.type == QuestionType.SINGLE_CHOICE ||
                            question.type == QuestionType.IMAGE_BASED && question.options.isNotEmpty()
                        else -> question.type == QuestionType.MATCHING ||
                            question.type == QuestionType.IMAGE_BASED && question.options.isNotEmpty()
                    }
                }) { "RESPONSE_QUESTION_TYPE_MISMATCH" }
                validatePoolReference(response, qIds, pools)
                if (kind == "matching") require(response.string("reuse") in setOf("allowed", "forbidden")) {
                    "INVALID_MATCHING_REUSE"
                }
            }
            slotIds.forEach { slotId -> require(slotResponses.put(slotId, kind) == null) { "SLOT_RESPONSE_DUPLICATE" } }
        }
        require(covered == questionIds.toSet()) { "QUESTION_RESPONSE_COVERAGE_INVALID" }
        require(slotResponses.keys == slots.keys) { "SLOT_RESPONSE_COUNT_INVALID" }
        return slotResponses
    }

    private fun validatePoolReference(response: JsonObject, qIds: List<String>, pools: Map<String, Set<String>>) {
        val poolId = response.string("optionPoolId")
        require(pools[poolId]?.containsAll(qIds) == true) { "OPTION_POOL_REFERENCE_MISMATCH" }
    }

    private fun validateText(block: JsonObject, part: ListeningPart, byId: Map<String, Question>, questionIds: List<String>) {
        val source = block.objectField("source")
        val start = source.int("startUtf16"); val end = source.int("endUtf16")
        require(start >= 0 && end >= start && end - start == block.string("text").length) { "INVALID_TEXT_SOURCE" }
        when (source.string("kind")) {
            "partField" -> {
                val field = source.string("field")
                val content = when (field) {
                    "part.instructions" -> part.instructions
                    "question.prompt", "question.instructions", "question.context" -> {
                        val questionId = source.string("questionId")
                        require(questionId in questionIds) { "INVALID_TEXT_SOURCE" }
                        val question = byId.getValue(questionId)
                        when (field) {
                            "question.prompt" -> question.prompt
                            "question.instructions" -> question.instructions
                            else -> question.context
                        }
                    }
                    else -> throw IllegalArgumentException("INVALID_TEXT_SOURCE")
                }
                require(end <= content.length && content.substring(start, end) == block.string("text")) {
                    "INVALID_TEXT_SOURCE"
                }
            }
            "resourceRange" -> {
                require(source.string("resource") in setOf("content.html", "content.txt") &&
                    source.string("normalization") in setOf("htmlTextV1", "rawTextV1") &&
                    source.string("sha256").matches(shaPattern)) { "INVALID_TEXT_SOURCE" }
            }
            else -> throw IllegalArgumentException("INVALID_TEXT_SOURCE")
        }
        if (block["role"] != null) require(block.string("role") == "answerConnector" &&
            block.string("questionId") in questionIds) { "INVALID_TEXT_ROLE" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonElement.obj(code: String): JsonObject = this as? JsonObject
        ?: throw IllegalArgumentException(code)
    private fun JsonObject.array(name: String): List<JsonElement> =
        (this[name] as? JsonArray)?.toList() ?: throw IllegalArgumentException("INVALID_${name.uppercase()}")
    private fun JsonObject.objectField(name: String): JsonObject =
        this[name] as? JsonObject ?: throw IllegalArgumentException("INVALID_${name.uppercase()}")
    private fun JsonObject.string(name: String): String = this[name]?.primitiveContent()
        ?: throw IllegalArgumentException("INVALID_${name.uppercase()}")
    private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("INVALID_${name.uppercase()}")
    private fun JsonObject.stringArray(name: String): List<String> = array(name).map {
        it.jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("INVALID_${name.uppercase()}")
    }
    private fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
