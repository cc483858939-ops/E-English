package com.eenglish.listening

import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.data.assets.QuestionLayoutCodec
import com.eenglish.listening.domain.gapfill.QuestionDisplayItem
import com.eenglish.listening.domain.gapfill.QuestionLayoutDisplayParser
import com.eenglish.listening.domain.gapfill.SharedMatchingQuestionGroup
import com.eenglish.listening.domain.layout.QuestionLayoutValidator
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionLayoutDocument
import com.eenglish.listening.domain.model.QuestionType
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local-only audit of the ignored Cambridge packs; no prompt or answer text is written to the report. */
class SharedQuestionGroupRealDataAuditTest {
    private val auditJson = Json { prettyPrint = true }

    private data class AuditedPart(
        val part: ListeningPart,
        val items: List<QuestionDisplayItem>,
        val zipEntries: Set<String>,
        val questionIdsAndOrderPreserved: Boolean,
    )

    private data class PromptKey(
        val type: QuestionType,
        val prompt: String,
        val instructions: String,
        val options: List<com.eenglish.listening.domain.model.Option>,
        val images: List<String>,
    )

    @Test fun currentParserPreservesAllQuestionsAndAuditsRepresentativeCambridgeSamples() {
        val packDirectory = listOf(
            File("private-data/layout-batch-ab-v2"),
            File("../private-data/layout-batch-ab-v2"),
        ).firstOrNull { it.isDirectory } ?: File("private-data/layout-batch-ab-v2")
        assumeTrue("Private Cambridge pack set is not installed", packDirectory.isDirectory)
        val packs = packDirectory.listFiles { file -> file.extension == "eelpack" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("Expected all 17 local Cambridge packs", packs.size == 17)

        val audited = mutableListOf<AuditedPart>()
        val typeCounts = linkedMapOf<String, Int>()
        QuestionType.values().forEach { typeCounts[it.name] = 0 }
        var layoutSidecarCount = 0
        var questionCount = 0

        packs.forEach { pack ->
            ZipFile(pack).use { zip ->
                val entries = zip.entries().asSequence().associateBy { it.name }
                val partEntries = entries.values.filter { it.name.matches(Regex("listening/cambridge-[0-9]+-test-[0-9]+-part-[0-9]+/part\\.json")) }
                assertEquals("Unexpected part count in ${pack.name}", 16, partEntries.size)
                val zipPaths = entries.keys
                partEntries.sortedBy { it.name }.forEach { partEntry ->
                    val partBytes = zip.getInputStream(partEntry).use { it.readBytes() }
                    val part = PartCodec.decode(partBytes.toString(Charsets.UTF_8))
                    assertEquals(pack.nameWithoutExtension.substringAfter("cambridge-").toInt(), part.book)
                    val partDirectory = partEntry.name.removeSuffix("part.json")
                    val layoutEntry = entries["${partDirectory}layout.json"]
                    val layout = layoutEntry?.let { entry ->
                        layoutSidecarCount++
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        QuestionLayoutCodec.decode(bytes).also {
                            QuestionLayoutValidator.validate(it, part, partBytes)
                        }
                    }
                    val items = QuestionLayoutDisplayParser.parse(part.questions, layout?.groups.orEmpty())
                    val sourceIds = part.questions.map { it.id }
                    val displayedQuestions = items.flatMap(::members)
                    val displayedIds = displayedQuestions.map { it.id }
                    val idsAndOrderPreserved = sourceIds == displayedIds && displayedIds.distinct().size == sourceIds.size
                    assertTrue("Display ID order changed or a question was repeated in ${part.id}", idsAndOrderPreserved)
                    part.questions.forEach { question ->
                        typeCounts[question.type.name] = (typeCounts[question.type.name] ?: 0) + 1
                    }
                    questionCount += part.questions.size
                    audited += AuditedPart(part, items, zipPaths, idsAndOrderPreserved)
                }
            }
        }

        assertEquals(272, audited.size)
        assertEquals(2720, questionCount)

        val allPartQuestionIdsAndOrderPreserved = audited.all { it.questionIdsAndOrderPreserved }
        assertTrue(allPartQuestionIdsAndOrderPreserved)
        val questionModeCounts = linkedMapOf<String, Int>()
        val modeCountsByType = QuestionType.values().associate { it.name to linkedMapOf<String, Int>() }
        audited.forEach { source ->
            source.items.forEach { item ->
                val mode = displayMode(item)
                questionModeCounts[mode] = (questionModeCounts[mode] ?: 0) + members(item).size
                members(item).forEach { question ->
                    val counts = modeCountsByType.getValue(question.type.name)
                    counts[mode] = (counts[mode] ?: 0) + 1
                }
            }
        }
        assertEquals(questionCount, questionModeCounts.values.sum())
        assertEquals(questionCount, modeCountsByType.values.sumOf { modes -> modes.values.sum() })

        val matchingCandidates = audited.flatMap { source ->
            repeatedPromptRuns(source.part.questions) {
                it.type == QuestionType.MATCHING
            }
        }
        val imageMatchingCandidates = audited.flatMap { source ->
            repeatedPromptRuns(source.part.questions) {
                it.type == QuestionType.IMAGE_BASED && it.options.isNotEmpty() &&
                    Regex("(?is)\\blabel\\s+(?:the\\s+)?(?:map|plan|diagram)\\b").containsMatchIn(it.prompt)
            }
        }
        val multipleChoiceCandidates = audited.flatMap { source ->
            source.part.questions.filter { it.type == QuestionType.MULTIPLE_CHOICE && !it.groupId.isNullOrBlank() }
                .groupBy { it.groupId }
                .values.filter { it.size > 1 }
        }
        val rawTextCandidates = audited.flatMap { source ->
            repeatedPromptRuns(source.part.questions) {
                it.isTextInput && it.type in setOf(QuestionType.TEXT_INPUT, QuestionType.NOTE_COMPLETION,
                    QuestionType.TABLE_COMPLETION, QuestionType.FLOW_COMPLETION, QuestionType.SHORT_ANSWER) &&
                    it.images.isEmpty() && it.answerSeparator == null && it.groupId == null
            }
        }

        val successfulMatchingGroups = audited.flatMap { source ->
            source.items.filterIsInstance<QuestionDisplayItem.SharedMatching>().map { it.group }
        }
        val successfulMatching = successfulMatchingGroups.map { it.questions }
        val successfulMultipleChoice = audited.flatMap { source ->
            source.items.filterIsInstance<QuestionDisplayItem.SharedMultipleChoice>().map { it.group.questions }
        }
        val existingTextDisplays = audited.flatMap { source ->
            source.items.filter { it is QuestionDisplayItem.StructuredLayout ||
                it is QuestionDisplayItem.InlineGroup || it is QuestionDisplayItem.ImageGroup }.map(::members)
        }
        val textCandidates = rawTextCandidates.filter { candidate ->
            existingTextDisplays.none { sameIds(candidate, it) }
        }
        val successfulTextFallbacks = audited.flatMap { source ->
            source.items.filterIsInstance<QuestionDisplayItem.SharedTextFallback>().map { it.group.questions }
        }
        val targetCandidateIds = (matchingCandidates + imageMatchingCandidates + multipleChoiceCandidates + rawTextCandidates)
            .flatten().map { it.id }.toSet()
        val remainingPromptRuns = audited.sumOf { source ->
            val singles = source.items.filterIsInstance<QuestionDisplayItem.Single>().map { it.question }
            repeatedPromptRuns(singles) { it.id in targetCandidateIds }.size
        }
        val matchingGroupCount = successfulMatching.count { it.firstOrNull()?.type == QuestionType.MATCHING }
        val imageMatchingGroupCount = successfulMatching.count { it.firstOrNull()?.type == QuestionType.IMAGE_BASED }
        val ordinaryMatchingGroups = successfulMatchingGroups.filter { it.questions.firstOrNull()?.type == QuestionType.MATCHING }
        val imageMatchingGroups = successfulMatchingGroups.filter { it.questions.firstOrNull()?.type == QuestionType.IMAGE_BASED }
        val ordinaryMatchingModes = matchingModeCounts(ordinaryMatchingGroups)
        val imageMatchingModes = matchingModeCounts(imageMatchingGroups)
        val inlineFallbackReasons = (ordinaryMatchingGroups + imageMatchingGroups)
            .filter { it.inlineSlots == null }
            .groupingBy { it.inlineFallbackReason?.name ?: "UNKNOWN" }
            .eachCount()
        val multipleChoiceGroupCount = successfulMultipleChoice.size
        val textFallbackGroupCount = successfulTextFallbacks.count { fallback ->
            textCandidates.any { sameIds(fallback, it) }
        }
        val fallbackDiagnostics = buildList {
            matchingCandidates.filter { candidate -> successfulMatching.none { sameIds(candidate, it) } }
                .forEach { candidate -> add(fallbackEntry("matching", candidate, matchingFallbackReason(candidate))) }
            imageMatchingCandidates.filter { candidate -> successfulMatching.none { sameIds(candidate, it) } }
                .forEach { candidate -> add(fallbackEntry("imageMatching", candidate, matchingFallbackReason(candidate))) }
            multipleChoiceCandidates.filter { candidate -> successfulMultipleChoice.none { sameIds(candidate, it) } }
                .forEach { candidate -> add(fallbackEntry("multipleChoice", candidate, multipleChoiceFallbackReason(candidate))) }
            textCandidates.filter { candidate -> successfulTextFallbacks.none { sameIds(candidate, it) } }
                .forEach { candidate -> add(fallbackEntry("sharedText", candidate, textFallbackReason(candidate))) }
        }

        val samples = listOf(
            sample(audited, "cambridge-7-test-3-part-3", 26..30, "SharedMatching", 0, "INLINE"),
            sample(audited, "cambridge-5-test-4-part-4", 37..40, "SharedMatching", 0, "INLINE"),
            sample(audited, "cambridge-6-test-3-part-2", 14..17, "SharedMatchingImage", 1, "INLINE"),
            sample(audited, "cambridge-5-test-1-part-3", 24..25, "SharedMultipleChoice", 0),
            sample(audited, "cambridge-5-test-1-part-2", 11..19, "ImageGroup", 1),
            sample(audited, "cambridge-5-test-1-part-4", 31..35, "StructuredLayout", 0),
        )
        samples.forEach { result ->
            assertTrue("Representative sample failed: ${result.partId}", result.expectedMatched)
            assertTrue("Sample image reference missing: ${result.partId}", result.imagesExist)
            assertEquals("Unexpected number of shared images: ${result.partId}", result.expectedImageCount, result.imageCount)
        }

        val report = buildJsonObject {
            put("generatedAtUtc", Instant.now().toString())
            put("sourceDirectory", packDirectory.path)
            put("books", packs.size)
            put("parts", audited.size)
            put("questions", questionCount)
            put("layoutSidecars", layoutSidecarCount)
            put("questionTypeCounts", buildJsonObject {
                typeCounts.toSortedMap().forEach { (type, count) -> put(type, count) }
            })
            put("groups", buildJsonObject {
                put("matching", buildJsonObject {
                    put("candidates", matchingCandidates.size)
                    put("successful", matchingGroupCount)
                    put("fallback", matchingCandidates.count { candidate ->
                        successfulMatching.none { sameIds(candidate, it) }
                    })
                })
                put("imageMatching", buildJsonObject {
                    put("candidates", imageMatchingCandidates.size)
                    put("successful", imageMatchingGroupCount)
                    put("fallback", imageMatchingCandidates.count { candidate ->
                        successfulMatching.none { sameIds(candidate, it) }
                    })
                })
                put("multipleChoice", buildJsonObject {
                    put("candidates", multipleChoiceCandidates.size)
                    put("successful", multipleChoiceGroupCount)
                    put("fallback", multipleChoiceCandidates.count { candidate ->
                        successfulMultipleChoice.none { sameIds(candidate, it) }
                    })
                })
                put("sharedTextFallback", buildJsonObject {
                    put("notFullyCoveredByExistingDisplayGroup", textCandidates.size)
                    put("successfulFallbackGroups", textFallbackGroupCount)
                    put("fullyCoveredByStructuredOrLegacyGroups", rawTextCandidates.size - textCandidates.size)
                    put("fallback", textCandidates.count { candidate -> successfulTextFallbacks.none { sameIds(candidate, it) } })
                })
            })
            put("remainingRepeatedFullPromptGroups", remainingPromptRuns)
            putJsonArray("fallbackDiagnostics") {
                fallbackDiagnostics.forEach { add(it) }
            }
            put("allPartQuestionIdsAndOrderPreserved", allPartQuestionIdsAndOrderPreserved)
            putJsonArray("samples") {
                samples.forEach { result ->
                    add(buildJsonObject {
                        put("partId", result.partId)
                        put("questionNumbers", result.questionNumbers.joinToString(","))
                        put("actualDisplayType", result.actualType)
                        put("displayedMemberCount", result.displayedMemberCount)
                        put("questionIds", result.questionIds.joinToString(","))
                        put("imageCount", result.imageCount)
                        put("expectedImageCount", result.expectedImageCount)
                        put("imagesExist", result.imagesExist)
                        put("expectedMatched", result.expectedMatched)
                    })
                }
            }
        }
        val reportFile = File(packDirectory.parentFile, "audits/shared-question-display-audit.json")
        requireNotNull(reportFile.parentFile).mkdirs()
        reportFile.writeText(auditJson.encodeToString(JsonObject.serializer(), report))

        val inlineCoverageReport = buildJsonObject {
            put("generatedAtUtc", Instant.now().toString())
            put("sourceDirectory", packDirectory.path)
            put("books", packs.size)
            put("parts", audited.size)
            put("questions", questionCount)
            put("layoutSidecars", layoutSidecarCount)
            put("allPartQuestionIdsAndOrderPreserved", allPartQuestionIdsAndOrderPreserved)
            put("questionTypeCoverage", buildJsonObject {
                QuestionType.values().forEach { type ->
                    val total = typeCounts.getValue(type.name)
                    val modes = modeCountsByType.getValue(type.name)
                    val inPlace = modes.filterKeys(::isInPlaceMode).values.sum()
                    val waiting = modes.filter { (mode, _) -> isAwaitingMappingMode(mode) ||
                        type == QuestionType.MATCHING && mode == "SINGLE_QUESTION_CARD_CHOICES" }
                        .values.sum()
                    put(type.name, buildJsonObject {
                        put("totalQuestions", total)
                        put("inPlaceQuestions", inPlace)
                        put("nonInlineButUsableQuestions", total - inPlace)
                        put("awaitingSafeInPlaceMappingQuestions", waiting)
                        put("displayModes", buildJsonObject {
                            modes.toSortedMap().forEach { (mode, count) -> put(mode, count) }
                        })
                    })
                }
            })
            put("displayModeTotals", buildJsonObject {
                questionModeCounts.toSortedMap().forEach { (mode, count) -> put(mode, count) }
            })
            put("matchingCoverage", buildJsonObject {
                put("textMatching", matchingModeCounts(ordinaryMatchingGroups))
                put("imageMatching", matchingModeCounts(imageMatchingGroups))
                put("parserRejectedCandidateGroups", buildJsonObject {
                    put("textMatchingGroups", matchingCandidates.count { candidate ->
                        successfulMatching.none { sameIds(candidate, it) }
                    })
                    put("textMatchingQuestions", matchingCandidates.filter { candidate ->
                        successfulMatching.none { sameIds(candidate, it) }
                    }.sumOf { it.size })
                    put("imageMatchingGroups", imageMatchingCandidates.count { candidate ->
                        successfulMatching.none { sameIds(candidate, it) }
                    })
                    put("imageMatchingQuestions", imageMatchingCandidates.filter { candidate ->
                        successfulMatching.none { sameIds(candidate, it) }
                    }.sumOf { it.size })
                })
                put("inlineFallbackReasonCounts", buildJsonObject {
                    inlineFallbackReasons.toSortedMap().forEach { (reason, count) -> put(reason, count) }
                })
            })
            put("otherSharedGroupCoverage", buildJsonObject {
                put("multipleChoiceGroups", multipleChoiceGroupCount)
                put("multipleChoiceQuestions", successfulMultipleChoice.sumOf { it.size })
                put("sharedTextFallbackGroups", textFallbackGroupCount)
                put("sharedTextFallbackQuestions", successfulTextFallbacks.filter { fallback ->
                    textCandidates.any { sameIds(fallback, it) }
                }.sumOf { it.size })
            })
            put("parserFallbackReasonCounts", buildJsonObject {
                val counts = linkedMapOf<String, Int>()
                fallbackDiagnostics.forEach { entry ->
                    val type = entry["type"]?.jsonPrimitive?.content ?: "unknown"
                    val reason = entry["reason"]?.jsonPrimitive?.content ?: "unknown"
                    val key = "$type:$reason"
                    counts[key] = (counts[key] ?: 0) + 1
                }
                counts.toSortedMap().forEach { (reason, count) -> put(reason, count) }
            })
            putJsonArray("representativeSamples") {
                samples.forEach { result ->
                    add(buildJsonObject {
                        put("partId", result.partId)
                        put("questionNumbers", result.questionNumbers.joinToString(","))
                        put("displayType", result.actualType)
                        put("matchingMode", result.matchingMode ?: "not-applicable")
                        put("inlineSlotCount", result.inlineSlotCount)
                        put("displayedMemberCount", result.displayedMemberCount)
                        put("imagesExist", result.imagesExist)
                        put("expectedMatched", result.expectedMatched)
                    })
                }
            }
        }
        val inlineCoverageFile = File(packDirectory.parentFile, "audits/inline-answer-coverage-audit.json")
        inlineCoverageFile.writeText(auditJson.encodeToString(JsonObject.serializer(), inlineCoverageReport))
        println("Shared question audit: books=${packs.size}, parts=${audited.size}, questions=$questionCount, " +
            "matching=$matchingGroupCount, imageMatching=$imageMatchingGroupCount, " +
            "multipleChoice=$multipleChoiceGroupCount, textFallback=$textFallbackGroupCount, " +
            "remainingRepeatedPrompts=$remainingPromptRuns, report=${reportFile.path}, inlineReport=${inlineCoverageFile.path}")
    }

    private data class SampleResult(
        val partId: String,
        val questionNumbers: List<Int>,
        val actualType: String,
        val displayedMemberCount: Int,
        val expectedImageCount: Int,
        val questionIds: List<String>,
        val imageCount: Int,
        val imagesExist: Boolean,
        val expectedMatched: Boolean,
        val matchingMode: String?,
        val inlineSlotCount: Int,
    )

    private fun sample(
        audited: List<AuditedPart>,
        partId: String,
        range: IntRange,
        expectedType: String,
        expectedImageCount: Int,
        expectedMatchingMode: String? = null,
    ): SampleResult {
        val source = requireNotNull(audited.singleOrNull { it.part.id == partId }) { "Missing real sample $partId" }
        val expectedQuestions = source.part.questions.filter { it.number in range }
        assertEquals(range.toList(), expectedQuestions.map { it.number })
        val ids = expectedQuestions.map { it.id }.toSet()
        val item = source.items.singleOrNull { displayItem -> members(displayItem).any { it.id in ids } }
        val matchedQuestions = item?.let(::members).orEmpty()
        val imagePaths = when (item) {
            is QuestionDisplayItem.ImageGroup -> item.group.images
            is QuestionDisplayItem.SharedMatching -> item.group.images
            is QuestionDisplayItem.SharedMultipleChoice -> item.group.images
            else -> emptyList()
        }
        val imagesExist = imagePaths.all { image ->
            source.zipEntries.any { entry -> entry.endsWith("/$image") }
        }
        val actualType = when (item) {
            is QuestionDisplayItem.StructuredLayout -> "StructuredLayout"
            is QuestionDisplayItem.InlineGroup -> "InlineGroup"
            is QuestionDisplayItem.ImageGroup -> "ImageGroup"
            is QuestionDisplayItem.SharedMatching -> if (item.group.images.isEmpty()) "SharedMatching" else "SharedMatchingImage"
            is QuestionDisplayItem.SharedMultipleChoice -> "SharedMultipleChoice"
            is QuestionDisplayItem.SharedTextFallback -> "SharedTextFallback"
            is QuestionDisplayItem.Single, null -> "Single"
        }
        val matchingGroup = (item as? QuestionDisplayItem.SharedMatching)?.group
        val actualMatchingMode = matchingGroup?.let(::matchingMode)
        val inlineSlotCount = matchingGroup?.inlineSlots?.size ?: 0
        val slotsAligned = expectedMatchingMode != "INLINE" || (matchingGroup != null && matchingGroup.inlineSlots != null &&
            matchingGroup.inlineSlots.map { it.questionId } == expectedQuestions.map { it.id } &&
            matchingGroup.inlineSlots.map { it.number } == range.toList() &&
            matchingGroup.inlineSlots.all { it.start >= 0 && it.end <= matchingGroup.prompt.length && it.start < it.end })
        val matchedRange = matchedQuestions.map { it.number }.containsAll(range.toList())
        return SampleResult(partId, range.toList(), actualType, matchedQuestions.size, expectedImageCount,
            matchedQuestions.map { it.id }, imagePaths.size, imagesExist,
            actualType == expectedType && matchedRange && matchedQuestions.map { it.number } == range.toList() &&
                matchedQuestions.map { it.id } == expectedQuestions.map { it.id } &&
                (expectedMatchingMode == null || actualMatchingMode == expectedMatchingMode) && slotsAligned,
            actualMatchingMode, inlineSlotCount)
    }

    private fun displayMode(item: QuestionDisplayItem): String = when (item) {
        is QuestionDisplayItem.Single -> if (item.question.isTextInput) "SINGLE_QUESTION_CARD_TEXT_INPUT"
            else if (item.question.type == QuestionType.IMAGE_BASED) "SINGLE_IMAGE_QUESTION_CHOICES"
            else "SINGLE_QUESTION_CARD_CHOICES"
        is QuestionDisplayItem.InlineGroup -> "LEGACY_INLINE_TEXT"
        is QuestionDisplayItem.StructuredLayout -> "STRUCTURED_LAYOUT_INLINE"
        is QuestionDisplayItem.ImageGroup -> "IMAGE_GROUP_CENTRAL_INPUT"
        is QuestionDisplayItem.SharedMatching -> when (matchingMode(item.group)) {
            "INLINE" -> "MATCHING_INLINE_SELECTION"
            "QUESTION_ROWS" -> "MATCHING_QUESTION_ROW_SELECTION"
            else -> "MATCHING_SHARED_PROMPT_FALLBACK"
        }
        is QuestionDisplayItem.SharedMultipleChoice -> "SHARED_MULTIPLE_CHOICE_OPTIONS"
        is QuestionDisplayItem.SharedTextFallback -> "SHARED_TEXT_FALLBACK_QUESTION_CARDS"
    }

    private fun matchingMode(group: SharedMatchingQuestionGroup): String = when {
        group.inlineSlots != null -> "INLINE"
        group.questionRows != null -> "QUESTION_ROWS"
        else -> "SHARED_PROMPT_FALLBACK"
    }

    private fun matchingModeCounts(groups: List<SharedMatchingQuestionGroup>) = buildJsonObject {
        listOf("INLINE", "QUESTION_ROWS", "SHARED_PROMPT_FALLBACK").forEach { mode ->
            val members = groups.filter { matchingMode(it) == mode }
            put(mode, buildJsonObject {
                put("groups", members.size)
                put("questions", members.sumOf { it.questions.size })
            })
        }
    }

    private fun isInPlaceMode(mode: String): Boolean = mode in setOf(
        "STRUCTURED_LAYOUT_INLINE", "LEGACY_INLINE_TEXT", "MATCHING_INLINE_SELECTION",
    )

    private fun isAwaitingMappingMode(mode: String): Boolean = mode in setOf(
        "SINGLE_QUESTION_CARD_TEXT_INPUT", "IMAGE_GROUP_CENTRAL_INPUT",
        "SHARED_TEXT_FALLBACK_QUESTION_CARDS", "MATCHING_SHARED_PROMPT_FALLBACK",
    )

    private fun fallbackEntry(kind: String, questions: List<Question>, reason: String): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            val sourcePart = requireNotNull(questions.firstOrNull())
            val part = sourcePart.id.substringBeforeLast("-q")
            put("type", kind)
            put("partId", part)
            put("questionNumbers", questions.joinToString(",") { it.number.toString() })
            put("reason", reason)
        }

    private fun matchingFallbackReason(questions: List<Question>): String {
        val first = questions.first()
        val line = first.prompt.lineSequence().firstOrNull()?.trim().orEmpty()
        val range = Regex("(?i)^Questions?\\s+(\\d{1,3})\\s*(?:[-–—]\\s*(\\d{1,3})|and\\s+(\\d{1,3}))$").matchEntire(line)
            ?: return "prompt-range-not-explicit"
        val start = range.groupValues[1].toIntOrNull() ?: return "prompt-range-invalid"
        val end = (range.groupValues[2].ifBlank { range.groupValues[3] }).toIntOrNull() ?: return "prompt-range-invalid"
        if (start != first.number || questions.map { it.number } != (start..end).toList()) return "range-member-count-or-order-mismatch"
        if (first.type == QuestionType.IMAGE_BASED &&
            (!Regex("(?is)\\blabel\\s+(?:the\\s+)?(?:map|plan|diagram)\\b").containsMatchIn(first.prompt) ||
                first.options.any { !it.id.matches(Regex("[A-Z]")) || it.text.trim() != it.id })) {
            return "image-matching-label-or-letter-options-not-explicit"
        }
        if (questions.any { it.wordLimit != null || it.answerSeparator != null || it.groupId != null || it.groupNumbers.isNotEmpty() }) {
            return "unsupported-question-metadata"
        }
        if (questions.any { question -> question.acceptableAnswers.isEmpty() ||
                question.acceptableAnswers.any { answer -> question.options.none { it.id.equals(answer, true) } } }) {
            return "answer-not-in-shared-options"
        }
        if (questions.drop(1).any { it.images.isNotEmpty() && it.images != first.images }) return "image-resources-conflict"
        val optionCount = questions.first().options.count { option ->
            val optionLine = Regex("(?m)^\\s*${Regex.escape(option.id)}(?:[\\t .):–—-]+)(.*)$")
            first.prompt.lineSequence().any { line -> optionLine.matchEntire(line)?.groupValues?.get(1)?.trim() == option.text.trim() }
        }
        if (optionCount in 1 until first.options.size) return "prompt-has-partial-option-list"
        return "other-conservative-validation"
    }

    private fun multipleChoiceFallbackReason(questions: List<Question>): String {
        val first = questions.first()
        val numbers = first.groupNumbers
        if (numbers.size < 2 || numbers != numbers.sorted() || numbers.zipWithNext().any { (a, b) -> b != a + 1 }) {
            return "groupNumbers-not-contiguous"
        }
        if (questions.map { it.number } != numbers || questions.map { it.id }.distinct().size != questions.size) {
            return "members-do-not-match-groupNumbers"
        }
        val line = first.prompt.lineSequence().firstOrNull()?.trim().orEmpty()
        val heading = Regex("(?i)^Questions?\\s+(\\d{1,3})\\s*(?:[-–—]\\s*(\\d{1,3})|and\\s+(\\d{1,3}))$").matchEntire(line)
            ?: return "prompt-range-not-explicit"
        val start = heading.groupValues[1].toIntOrNull()
        val end = (heading.groupValues[2].ifBlank { heading.groupValues[3] }).toIntOrNull()
        if (start != numbers.firstOrNull() || end != numbers.lastOrNull()) return "prompt-range-does-not-match-metadata"
        if (questions.any { it.groupId != first.groupId || it.groupNumbers != numbers }) return "group-metadata-conflict"
        if (questions.any { it.options != first.options }) return "options-differ"
        if (questions.any { it.images != first.images }) return "image-resources-conflict"
        if (questions.any { it.acceptedAnswers.isNotEmpty() && it.acceptedAnswers != listOf(it.correctAnswer) }) {
            return "answer-group-semantics-conflict"
        }
        if (questions.map { it.correctAnswer.uppercase() }.distinct().size != questions.size) return "answers-not-distinct"
        return "prompt-instructions-or-selection-count-conflict"
    }

    private fun textFallbackReason(questions: List<Question>): String {
        val first = questions.first()
        if (questions.any { !it.isTextInput || it.wordLimit == null || it.images.isNotEmpty() ||
                it.answerSeparator != null || it.groupId != null || it.groupNumbers.isNotEmpty() }) {
            return "unsupported-text-question-metadata"
        }
        val title = first.prompt.lineSequence().firstOrNull()?.trim().orEmpty()
        val range = Regex("(?i)^Questions?\\s+(\\d{1,3})\\s*[-–—]\\s*(\\d{1,3})$").matchEntire(title)
            ?: return "prompt-range-not-explicit"
        val start = range.groupValues[1].toIntOrNull()
        val end = range.groupValues[2].toIntOrNull()
        if (start == null || end == null || questions.map { it.number } != (start..end).toList()) {
            return "range-member-count-or-order-mismatch"
        }
        if (Regex("(?:_{2,}|[＿﹍]{2,})").findAll(first.prompt).count() != questions.size) {
            return "blank-count-does-not-match-range"
        }
        return "numbered-blank-boundaries-not-explicit"
    }

    private fun members(item: QuestionDisplayItem): List<Question> = when (item) {
        is QuestionDisplayItem.Single -> listOf(item.question)
        is QuestionDisplayItem.InlineGroup -> item.group.questions
        is QuestionDisplayItem.StructuredLayout -> item.questions
        is QuestionDisplayItem.ImageGroup -> item.group.questions
        is QuestionDisplayItem.SharedMatching -> item.group.questions
        is QuestionDisplayItem.SharedMultipleChoice -> item.group.questions
        is QuestionDisplayItem.SharedTextFallback -> item.group.questions
    }

    private fun sameIds(left: List<Question>, right: List<Question>): Boolean =
        left.map { it.id }.toSet() == right.map { it.id }.toSet()

    private fun repeatedPromptRuns(
        questions: List<Question>,
        include: (Question) -> Boolean,
    ): List<List<Question>> {
        val positions = questions.mapIndexed { index, question -> question.id to index }.toMap()
        val keyed = questions.filter(include).groupBy { question ->
            PromptKey(question.type, normalize(question.prompt), normalize(question.instructions),
                question.options, question.images)
        }
        return keyed.values.flatMap { samePrompt ->
            val sorted = samePrompt.sortedBy { positions[it.id] }
            val runs = mutableListOf<MutableList<Question>>()
            sorted.forEach { question ->
                val previous = runs.lastOrNull()?.lastOrNull()
                if (previous == null || positions[question.id] != positions[previous.id]?.plus(1) ||
                    question.number != previous.number + 1) {
                    runs += mutableListOf(question)
                } else runs.last() += question
            }
            runs.filter { it.size > 1 }
        }
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
