package com.eenglish.listening.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.eenglish.listening.domain.annotation.AnnotationProjection
import com.eenglish.listening.data.assets.FillNumberCutAssets
import com.eenglish.listening.domain.layout.V4AnnotationSources
import com.eenglish.listening.domain.layout.V4FillNumbering
import com.eenglish.listening.domain.layout.fillNumberLabel
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.math.hypot
import kotlin.math.min

/** Data-driven renderer selection; image groups with mixed response kinds keep their existing UI. */
internal fun usesImageFillAnswerList(group: JsonObject): Boolean {
    val responses = group["responses"] as? JsonArray ?: return false
    if (responses.isEmpty() || responses.any { element ->
            (element as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull != "fill"
        }) return false
    return containsV4BlockType(group["instructions"], "image") || containsV4BlockType(group["blocks"], "image")
}

internal fun hasV4QuestionImage(group: JsonObject): Boolean =
    containsV4BlockType(group["instructions"], "image") || containsV4BlockType(group["blocks"], "image")

private data class SharedTextMatchingRow(val questionId: String, val prompt: JsonObject)
private data class SharedTextMatchingLayout(
    val response: JsonObject,
    val pool: JsonObject,
    val leadingBlocks: List<JsonObject>,
    val rows: List<SharedTextMatchingRow>,
)

/** Accept only the V4 shapes whose single shared pool can replace repeated per-question choice lists. */
private fun sharedTextMatchingLayout(group: JsonObject): SharedTextMatchingLayout? {
    if (hasV4QuestionImage(group)) return null
    val response = group.v4Array("responses").map { it.jsonObject }.singleOrNull() ?: return null
    if (response.v4String("kind") != "matching") return null
    val questionIds = group.v4Array("questionIds").map { it.jsonPrimitive.content }
    if (questionIds.isEmpty() || response.v4Array("questionIds").map { it.jsonPrimitive.content } != questionIds) return null
    val pool = group.v4Array("optionPools").map { it.jsonObject }
        .singleOrNull { it.v4String("poolId") == response.v4String("optionPoolId") } ?: return null
    if (pool.v4Array("optionIds").size < 2) return null
    val slots = group.v4Array("slots").map { it.jsonObject }
    if (slots.size != questionIds.size || slots.any { it.v4String("kind") != "matching" } ||
        slots.map { it.v4String("questionId") } != questionIds ||
        slots.map { it.v4String("slotId") } != response.v4Array("slotIds").map { it.jsonPrimitive.content }) return null
    val leading = mutableListOf<JsonObject>()
    val rows = mutableListOf<SharedTextMatchingRow>()
    for (block in group.v4Array("blocks").map { it.jsonObject }) {
        val children = (block["children"] as? JsonArray)?.map { it.jsonObject } ?: return null
        if (block.v4String("type") == "paragraph" && rows.isEmpty() &&
            children.isNotEmpty() && children.all { it.v4String("type") == "text" }) {
            leading += block
            continue
        }
        if (rows.size >= slots.size || block.v4String("type") !in setOf("formRow", "paragraph") || children.size != 2 ||
            children[0].v4String("type") != "text" || children[1].v4String("type") != "choiceList") return null
        val choice = children[1]
        if (choice.v4String("optionPoolId") != pool.v4String("poolId") ||
            choice.v4Array("slotIds").map { it.jsonPrimitive.content } != listOf(slots[rows.size].v4String("slotId"))) return null
        rows += SharedTextMatchingRow(questionIds[rows.size], children[0])
    }
    return if (rows.size == questionIds.size) SharedTextMatchingLayout(response, pool, leading, rows) else null
}

internal fun usesSharedTextMatchingLayout(group: JsonObject): Boolean = sharedTextMatchingLayout(group) != null

private fun sharedMatchingOptionsAgree(
    layout: SharedTextMatchingLayout,
    questionById: Map<String, Question>,
): Boolean {
    val optionIds = layout.pool.v4Array("optionIds").map { it.jsonPrimitive.content }
    val first = questionById[layout.rows.first().questionId] ?: return false
    val sharedLabels = optionIds.map { id -> first.options.firstOrNull { it.id == id }?.text ?: return false }
    return layout.rows.all { row ->
        val question = questionById[row.questionId] ?: return@all false
        optionIds.withIndex().all { (index, id) ->
            question.options.firstOrNull { it.id == id }?.text == sharedLabels[index]
        }
    }
}

private fun containsV4BlockType(element: JsonElement?, type: String): Boolean = when (element) {
    is JsonObject -> element["type"]?.jsonPrimitive?.contentOrNull == type ||
        element.values.any { containsV4BlockType(it, type) }
    is JsonArray -> element.any { containsV4BlockType(it, type) }
    else -> false
}

/** Renders one validated v4 group. The Part remains the only source of option labels and answers. */
@Composable
fun LayoutV4GroupView(
    group: JsonObject,
    questions: List<Question>,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    saving: Boolean,
    onSelect: (String, String) -> Unit,
    onEdit: (String, String) -> Unit,
    onCommit: (String) -> Unit,
    onActivate: (Int) -> Unit = {},
    imageAnswerCardOverview: Boolean = false,
    imageAnswerCardActive: Boolean = false,
    onImageTap: () -> Unit = {},
    imageAnswerCardCompact: Boolean = false,
) {
    val groupId = group.v4String("groupId")
    val imageFillAnswerList = remember(group) { usesImageFillAnswerList(group) }
    val inlineTextFill = remember(group) {
        !hasV4QuestionImage(group) && group.v4Array("responses").isNotEmpty() &&
            group.v4Array("responses").all { it.jsonObject.v4String("kind") == "fill" }
    }
    val sharedMatching = remember(group) { sharedTextMatchingLayout(group) }
    val questionById = remember(questions) { questions.associateBy { it.id } }
    val assets = LocalContext.current.applicationContext.assets
    val numberCuts = remember(assets) { FillNumberCutAssets.load(assets) }
    val hiddenNumbers = remember(group, questionById, numberCuts) {
        V4FillNumbering.hiddenRanges(group, questionById, numberCuts)
    }
    val sharedMatchingReady = remember(sharedMatching, questionById) {
        sharedMatching != null && sharedMatchingOptionsAgree(sharedMatching, questionById)
    }
    val slots = remember(group) { group.v4Array("slots").map { it.jsonObject }.associateBy { it.v4String("slotId") } }
    val responses = remember(group) {
        buildMap {
            group.v4Array("responses").forEach { responseElement ->
                val response = responseElement.jsonObject
                response.v4Array("slotIds").forEach { slot -> put(slot.jsonPrimitive.content, response) }
            }
        }
    }
    val pools = remember(group) { group.v4Array("optionPools").map { it.jsonObject }.associateBy { it.v4String("poolId") } }
    val matchingSheetHeight = rememberMatchingSheetHeight(groupId)
    var activeSlotId by remember(groupId) { mutableStateOf<String?>(null) }
    var activeImageFillQuestionId by remember(groupId) { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = remember(group, questions, answers, editable, submitted, saving, slots, responses, pools,
        imageFillAnswerList, inlineTextFill, activeImageFillQuestionId, imageAnswerCardOverview, imageAnswerCardActive, onImageTap, imageAnswerCardCompact, hiddenNumbers) {
        V4RenderContext(group, hiddenNumbers, questionById, slots, responses, pools, answers, editable, submitted,
            saving, onSelect, onEdit, onCommit, onActivate, { slotId -> activeSlotId = slotId },
            imageFillAnswerList, inlineTextFill, imageAnswerCardOverview, imageAnswerCardActive, imageAnswerCardCompact, onImageTap, {
                val focusedQuestionId = activeImageFillQuestionId
                focusManager.clearFocus(force = true)
                if (focusedQuestionId != null) onCommit(focusedQuestionId)
                activeImageFillQuestionId = null
            })
    }
    val hasMatchingChoiceList = remember(group) {
        val poolIds = group.v4Array("optionPools").map { it.jsonObject.v4String("poolId") }.toSet()
        group.v4Array("blocks").any { containsChoiceList(it.jsonObject, poolIds) }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("layout-v4-group-$groupId"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.v4Array("instructions").forEachIndexed { index, item ->
            V4BlockView(item.jsonObject, context, 0, numberedOrdinalAt(group.v4Array("instructions"), index))
        }
        if (sharedMatchingReady && sharedMatching != null) {
            V4SharedTextMatchingGroup(group, sharedMatching, context)
        } else {
            if (!imageAnswerCardOverview && !hasMatchingChoiceList) {
                group.v4Array("responses").map { it.jsonObject }.filter { it.v4String("kind") == "matching" }
                    .distinctBy { it.v4String("optionPoolId") }.forEach { response ->
                        val pool = pools[response.v4String("optionPoolId")] ?: return@forEach
                        val optionIds = pool.v4Array("optionIds").map { it.jsonPrimitive.content }
                        val options = optionsFor(response.v4Array("questionIds").map { it.jsonPrimitive.content },
                            optionIds, questionById)
                        Text("可选项", style = MaterialTheme.typography.labelLarge)
                        options.forEach { option ->
                            V4SelectableText(V4AnnotationSources.option(option,
                                response.v4Array("questionIds").mapNotNull { questionById[it.jsonPrimitive.content] }, optionLabel(option)),
                                "v4-matching-option-${groupId}-${option.id}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
            }
            group.v4Array("blocks").forEachIndexed { index, item ->
                V4BlockView(item.jsonObject, context, 0, numberedOrdinalAt(group.v4Array("blocks"), index))
            }
            if (imageFillAnswerList && !imageAnswerCardOverview) {
                V4ImageAnswerList(group, context) { questionId -> activeImageFillQuestionId = questionId }
            }
        }
        if (submitted) ReviewQuestionStrip(questions)
        if (submitted && LocalQuestionReview.current == null) {
            questions.forEach { question ->
                val selected = answers[question.id].orEmpty()
                val isCorrect = when (question.type) {
                    com.eenglish.listening.domain.model.QuestionType.MULTIPLE_CHOICE ->
                        question.correctAnswer.uppercase() in Grader.selection(selected)
                    else -> selected.isNotBlank() && Grader.withinLimit(question, selected) &&
                        question.acceptableAnswers.any { Grader.normalize(it) == Grader.normalize(selected) }
                }
                Text("Q${question.number} · 你的答案：${selected.ifBlank { "未作答" }} · ${if (isCorrect) "正确" else "错误"}",
                    color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("v4-result-${question.number}"))
            }
        }
    }

    val activeSlot = activeSlotId?.let(slots::get)
    val activeResponse = activeSlotId?.let(responses::get)
    if (activeSlot != null && activeResponse != null && editable && !submitted) {
        val question = questionById[activeSlot.v4String("questionId")]
        if (question != null) {
            when (activeResponse.v4String("kind")) {
                "fill" -> V4FillDialog(question, activeSlot, activeResponse, answers[question.id].orEmpty(),
                    onEdit = onEdit, onCommit = onCommit, onDismiss = { activeSlotId = null })
                "singleChoice", "matching" -> {
                    val pool = pools[activeResponse.v4String("optionPoolId")]
                    val allowed = pool?.v4Array("optionIds")?.map { it.jsonPrimitive.content }.orEmpty()
                    val options = question.options.filter { it.id in allowed }
                    MatchingOptionSheet(question, options, answers[question.id].orEmpty(), !saving,
                        onSelect = { option -> onSelect(question.id, option) },
                        onDismiss = { activeSlotId = null }, heightState = matchingSheetHeight)
                }
            }
        }
    }
}

private data class V4RenderContext(
    val group: JsonObject,
    val hiddenNumbers: Map<String, List<IntRange>>,
    val questionById: Map<String, Question>,
    val slots: Map<String, JsonObject>,
    val responses: Map<String, JsonObject>,
    val pools: Map<String, JsonObject>,
    val answers: Map<String, String>,
    val editable: Boolean,
    val submitted: Boolean,
    val saving: Boolean,
    val onSelect: (String, String) -> Unit,
    val onEdit: (String, String) -> Unit,
    val onCommit: (String) -> Unit,
    val onActivate: (Int) -> Unit,
    val onOpenSlot: (String) -> Unit,
    val imageFillAnswerList: Boolean,
    val inlineTextFill: Boolean,
    val imageAnswerCardOverview: Boolean,
    val imageAnswerCardActive: Boolean,
    val imageAnswerCardCompact: Boolean,
    val onImageTap: () -> Unit,
    val beforeImageZoom: () -> Unit,
)

@Composable
private fun V4BlockView(block: JsonObject, context: V4RenderContext, depth: Int, numberedOrdinal: Int? = null) {
    if (depth > 8) return
    when (block.v4String("type")) {
        "text" -> V4SelectableText(blockProjection(block, context), "v4-text-${block.v4String("id")}",
            style = if (context.inlineTextFill) MaterialTheme.typography.bodyLarge.copy(
                fontSize = 18.sp, lineHeight = 28.sp) else MaterialTheme.typography.bodyLarge)
        "slot" -> if (!context.imageFillAnswerList && !context.imageAnswerCardOverview) {
            context.slots[block.v4String("slotId")]?.let { V4SlotControl(it, context) }
        }
        "choiceList" -> if (!context.imageAnswerCardOverview) V4ChoiceList(block, context)
        "image" -> V4ImageBlock(block, context)
        "paragraph" -> FlowRowLayout(block.v4Array("children"), context, depth + 1)
        "formRow" -> if (context.inlineTextFill && block.v4Array("children").all {
            it.jsonObject.v4String("type") in setOf("text", "slot")
        }) FlowRowLayout(block.v4Array("children"), context, depth + 1,
            Modifier.fillMaxWidth().testTag("v4-form-row-${block.v4String("id")}")) else FlowRow(Modifier.fillMaxWidth().testTag("v4-form-row-${block.v4String("id")}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            block.v4Array("children").forEachIndexed { index, child ->
                V4BlockView(child.jsonObject, context, depth + 1,
                    numberedOrdinalAt(block.v4Array("children"), index))
            }
        }
        "listItem" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val hiddenNumber = block.v4Array("children").any { it.jsonObject.v4String("id") in context.hiddenNumbers }
            val marker = if (hiddenNumber && block.v4String("marker") == "numbered") ""
                else v4ListMarkerLabel(block.v4String("marker"), hasExplicitNumberPrefix(block), numberedOrdinal)
            if (marker.isNotEmpty()) {
                Text(marker, modifier = Modifier.alignByBaseline().testTag("v4-list-marker-${block.v4String("id")}"))
            }
            FlowRowLayout(block.v4Array("children"), context, depth + 1, Modifier.weight(1f).alignByBaseline())
        }
        "table" -> V4Table(block, context, depth + 1)
        "flow" -> V4Flow(block, context, depth + 1)
    }
}

internal fun v4ListMarkerLabel(marker: String, explicitNumberPrefix: Boolean, ordinal: Int?): String = when (marker) {
    "bullet" -> "•"
    "numbered" -> if (explicitNumberPrefix) "" else "${ordinal ?: 1}."
    else -> ""
}

internal fun numberedOrdinalAt(siblings: List<JsonElement>, index: Int): Int? {
    fun isNumberedItem(element: JsonElement?): Boolean {
        val block = element as? JsonObject ?: return false
        return block["type"]?.jsonPrimitive?.contentOrNull == "listItem" &&
            block["marker"]?.jsonPrimitive?.contentOrNull == "numbered"
    }
    if (!isNumberedItem(siblings.getOrNull(index))) return null
    var first = index
    while (first > 0 && isNumberedItem(siblings[first - 1])) first--
    return index - first + 1
}

private fun hasExplicitNumberPrefix(block: JsonObject): Boolean {
    fun firstText(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
            return obj["text"]?.jsonPrimitive?.contentOrNull
        }
        val children = obj["children"] as? JsonArray ?: return null
        return children.firstNotNullOfOrNull(::firstText)
    }
    val prefix = (block["children"] as? JsonArray)?.firstNotNullOfOrNull(::firstText)?.trim().orEmpty()
    return prefix.matches(Regex("\\d{1,3}[.)]?"))
}

@Composable
private fun FlowRowLayout(
    children: List<JsonElement>,
    context: V4RenderContext,
    depth: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val blocks = children.map { it.jsonObject }
    val slotCount = blocks.count { it.v4String("type") == "slot" }
    if (context.inlineTextFill && slotCount > 0 &&
        blocks.all { it.v4String("type") in setOf("text", "slot") }) {
        InlineFillTextLayout(blocks, context, depth, modifier)
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(if (context.inlineTextFill) 8.dp else 4.dp)) {
        children.forEachIndexed { index, child ->
            V4BlockView(child.jsonObject, context, depth, numberedOrdinalAt(children, index))
        }
    }
}

@Composable
private fun InlineFillTextLayout(
    blocks: List<JsonObject>, context: V4RenderContext, depth: Int, modifier: Modifier,
) {
    val parts = blocks.map { block ->
        if (block.v4String("type") == "text") blockProjection(block, context)
        else AnnotationProjection("\uFFFC", emptyList())
    }
    // Keep the last word beside its editor, as in the original V4 inline layout.
    val projection = AnnotationProjection.join(parts.flatMapIndexed { index, part ->
        if (index == 0) listOf(part) else listOf(
            AnnotationProjection(if (blocks[index].v4String("type") == "slot") "\u00a0" else " ", emptyList()), part)
    }, "")
    var offset = 0
    val slots = mutableListOf<V4InlineSlot>()
    blocks.forEachIndexed { index, block ->
        if (block.v4String("type") == "slot") {
            val slotId = block.v4String("slotId")
            slots += V4InlineSlot(offset, slotId) { V4BlockView(block, context, depth) }
        }
        offset += parts[index].text.length + 1
    }
    V4SelectableRun(projection, slots, "v4-inline-text-${blocks.first().v4String("id")}", modifier,
        MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp))
}

private fun blockProjection(block: JsonObject, context: V4RenderContext): AnnotationProjection {
    val original = V4AnnotationSources.block(context.group.v4String("groupId"), block, context.questionById)
    return V4FillNumbering.project(original, context.hiddenNumbers[block.v4String("id")].orEmpty())
}

@Composable
private fun V4Table(block: JsonObject, context: V4RenderContext, depth: Int) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))) {
        block.v4Array("rows").forEachIndexed { rowIndex, rowElement ->
            val cells = rowElement.jsonObject.v4Array("cells")
            Row(Modifier.fillMaxWidth()) {
                cells.forEach { cellElement ->
                    val cell = cellElement.jsonObject
                    Column(Modifier.weight(cell.v4Int("colSpan").coerceAtLeast(1).toFloat())
                        .border(0.5.dp, MaterialTheme.colorScheme.outline).padding(6.dp)) {
                        cell.v4Array("children").forEachIndexed { index, child ->
                            V4BlockView(child.jsonObject, context, depth,
                                numberedOrdinalAt(cell.v4Array("children"), index))
                        }
                    }
                }
            }
            if (rowIndex < block.v4Array("rows").lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun V4Flow(block: JsonObject, context: V4RenderContext, depth: Int) {
    val nodes = block.v4Array("nodes").map { it.jsonObject }
    val edges = block.v4Array("edges").map { it.jsonObject }
    Column(Modifier.fillMaxWidth().testTag("v4-flow-${block.v4String("id")}"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        nodes.forEachIndexed { index, node ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp), shape = RoundedCornerShape(10.dp),
                tonalElevation = 1.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.v4Array("children").forEachIndexed { index, child ->
                        V4BlockView(child.jsonObject, context, depth,
                            numberedOrdinalAt(node.v4Array("children"), index))
                    }
                }
            }
            val next = nodes.getOrNull(index + 1)
            if (next != null && edges.any { it.v4String("from") == node.v4String("id") && it.v4String("to") == next.v4String("id") }) {
                Text("↓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun V4SlotControl(slot: JsonObject, context: V4RenderContext) {
    val question = context.questionById[slot.v4String("questionId")] ?: return
    Box(Modifier.questionJumpTarget(question.number)) { V4SlotControlContent(slot, context) }
}

@Composable
private fun V4SlotControlContent(slot: JsonObject, context: V4RenderContext) {
    val questionId = slot.v4String("questionId")
    val question = context.questionById[questionId] ?: return
    val slotId = slot.v4String("slotId")
    val response = context.responses[slotId] ?: return
    val index = slot.v4Int("slotIndex")
    val answer = context.answers[questionId].orEmpty()
    val parts = splitForLayout(question, response, answer)
    if (context.submitted && LocalQuestionReview.current != null) {
        ReviewAnswerToken(question, if (slot.v4String("kind") == "fill") parts.getOrElse(index) { "" } else answer,
            Modifier.widthIn(max = 220.dp), numberLabel = fillNumberLabel(question.number, index, slotCount(questionId, context)),
            singleLine = context.inlineTextFill)
        return
    }
    when (slot.v4String("kind")) {
        "fill" -> {
            if (!context.inlineTextFill) {
                var hadFocus by remember(questionId, slotId) { mutableStateOf(false) }
                OutlinedTextField(
                    value = parts.getOrElse(index) { "" },
                    onValueChange = { value ->
                        if (value.length <= 512) {
                            val next = parts.toMutableList().also { while (it.size < 2) it += "" }
                            next[index] = value
                            context.onEdit(questionId, encodeForLayout(question, response, next))
                        }
                    },
                    enabled = context.editable && !context.submitted,
                    prefix = { Text(fillNumberLabel(question.number, index, slotCount(questionId, context))) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.widthIn(min = 132.dp, max = 220.dp).heightIn(min = 54.dp)
                        .onFocusChanged { state ->
                            if (state.isFocused) hadFocus = true
                            else if (hadFocus) {
                                hadFocus = false
                                context.onCommit(questionId)
                            }
                        }
                        .testTag("v4-input-${question.number}-${index + 1}")
                        .semantics { contentDescription = "Q${question.number} 答案${if (slotCount(questionId, context) > 1) "第${index + 1}空" else ""}" },
                )
                return
            }
            var focused by remember(questionId, slotId) { mutableStateOf(false) }
            val colors = MaterialTheme.colorScheme
            val shape = RoundedCornerShape(6.dp)
            BasicTextField(
                value = parts.getOrElse(index) { "" },
                onValueChange = { value ->
                    if (value.length <= 512) {
                        val next = parts.toMutableList().also { while (it.size < 2) it += "" }
                        next[index] = value
                        context.onEdit(questionId, encodeForLayout(question, response, next))
                    }
                },
                enabled = context.editable && !context.submitted,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { context.onCommit(questionId) }),
                modifier = Modifier.width(V4InlineAnswerWidth).height(48.dp)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            focused = true
                            context.onActivate(question.number)
                        } else if (focused) {
                            focused = false
                            context.onCommit(questionId)
                        }
                    }
                    .testTag("v4-input-${question.number}-${index + 1}")
                    .semantics { contentDescription = "Q${question.number} 答案${if (slotCount(questionId, context) > 1) "第${index + 1}空" else ""}" },
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(Modifier.fillMaxWidth().height(40.dp)
                            .background(if (parts.getOrElse(index) { "" }.isEmpty()) colors.primaryContainer.copy(alpha = 0.35f) else colors.surface, shape)
                            .border(if (focused) 2.dp else 1.dp,
                                if (focused) colors.primary else colors.outline, shape)
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fillNumberLabel(question.number, index, slotCount(questionId, context)),
                                style = MaterialTheme.typography.bodyLarge, color = colors.primary, maxLines = 1,
                                modifier = Modifier.testTag("v4-input-number-${question.number}-${index + 1}"))
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.weight(1f)) { innerTextField() }
                        }
                    }
                },
            )
        }
        "singleChoice", "matching" -> {
            val optionId = answer.takeIf(String::isNotBlank)
            val option = question.options.firstOrNull { it.id.equals(optionId, true) }
            OutlinedButton(
                onClick = { context.onActivate(question.number); context.onOpenSlot(slotId) },
                enabled = context.editable && !context.submitted,
                modifier = Modifier.heightIn(min = 48.dp).testTag("v4-slot-${question.number}")
                    .semantics { contentDescription = "Q${question.number} ${option?.let(::optionLabel) ?: "未选择"}" },
            ) { Text(option?.let(::optionLabel) ?: "Q${question.number} 选择") }
        }
    }
}

@Composable
private fun V4ImageAnswerList(
    group: JsonObject,
    context: V4RenderContext,
    onFocusedQuestionChanged: (String?) -> Unit,
) {
    val rows = remember(group, context.slots, context.responses, context.questionById) {
        group.v4Array("questionIds").mapNotNull { questionElement ->
            val questionId = questionElement.jsonPrimitive.content
            val question = context.questionById[questionId] ?: return@mapNotNull null
            val questionSlots = group.v4Array("slots").map { it.jsonObject }
                .filter { it.v4String("questionId") == questionId }
                .sortedBy { it.v4Int("slotIndex") }
            if (questionSlots.isEmpty() || questionSlots.any { it.v4String("kind") != "fill" }) {
                return@mapNotNull null
            }
            val response = context.responses[questionSlots.first().v4String("slotId")]
                ?: return@mapNotNull null
            if (response.v4String("kind") != "fill") return@mapNotNull null
            val responseSlots = response.v4Array("slotIds").mapNotNull { element ->
                context.slots[element.jsonPrimitive.content]
            }.sortedBy { it.v4Int("slotIndex") }
            if (responseSlots.size != questionSlots.size || responseSlots.any {
                    it.v4String("questionId") != questionId || it.v4String("kind") != "fill"
                }) return@mapNotNull null
            ImageFillQuestionRow(question, response, responseSlots)
        }
    }
    val focusTargets = remember(rows) {
        rows.flatMap { row -> row.slots.map { row.question.id to it.v4String("slotId") } }
    }
    val focusRequesters = remember(focusTargets.map { it.second }) { focusTargets.map { FocusRequester() } }
    val focusIndexBySlotId = remember(focusTargets) {
        focusTargets.mapIndexed { index, target -> target.second to index }.toMap()
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { index, row ->
            val question = row.question
            val answerParts = splitForLayout(question, row.response, context.answers[question.id].orEmpty())
            val bringIntoView = remember(question.id) { BringIntoViewRequester() }
            Row(
                Modifier.fillMaxWidth().questionJumpTarget(question.number).bringIntoViewRequester(bringIntoView)
                    .testTag("v4-image-list-row-${question.number}"),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.slots.forEachIndexed { slotOrdinal, slot ->
                        val slotId = slot.v4String("slotId")
                        val slotIndex = slot.v4Int("slotIndex")
                        val targetIndex = focusIndexBySlotId[slotId] ?: return@forEachIndexed
                        val value = answerParts.getOrElse(slotIndex) { "" }
                        if (context.submitted && LocalQuestionReview.current != null) {
                            ReviewAnswerToken(question, value, Modifier.fillMaxWidth(),
                                numberLabel = fillNumberLabel(question.number, slotIndex, row.slots.size))
                        } else {
                        val singleSlot = row.slots.size == 1
                        var hadFocus by remember(question.id, slotId) { mutableStateOf(false) }
                        val tag = if (singleSlot) "v4-image-list-input-${question.number}"
                            else "v4-image-list-input-${question.number}-slot${slotIndex + 1}"
                        OutlinedTextField(
                            value = value,
                            onValueChange = { next ->
                                if (next.length <= 512) {
                                    val parts = splitForLayout(question, row.response,
                                        context.answers[question.id].orEmpty()).toMutableList()
                                    while (parts.size < row.slots.size) parts += ""
                                    parts[slotIndex] = next
                                    context.onEdit(question.id, encodeForLayout(question, row.response, parts))
                                }
                            },
                            enabled = context.editable && !context.submitted,
                            prefix = { Text(fillNumberLabel(question.number, slotIndex, row.slots.size)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequesters[targetIndex])
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        hadFocus = true
                                        onFocusedQuestionChanged(question.id)
                                        context.onActivate(question.number)
                                        scope.launch { bringIntoView.bringIntoView() }
                                    } else if (hadFocus) {
                                        hadFocus = false
                                        onFocusedQuestionChanged(null)
                                        context.onCommit(question.id)
                                    }
                                }
                                .testTag(tag)
                                .semantics {
                                    contentDescription = if (singleSlot) {
                                        "Q${question.number} 答案，${if (value.isBlank()) "未作答" else "已作答"}"
                                    } else {
                                        "Q${question.number} 第${slotIndex + 1}空答案，${if (value.isBlank()) "未作答" else "已作答"}"
                                    }
                                },
                            placeholder = { Text(if (singleSlot) "填写答案" else "第 ${slotIndex + 1} 空") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = if (targetIndex == focusTargets.lastIndex) ImeAction.Done else ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = {
                                    context.onCommit(question.id)
                                    focusRequesters.getOrNull(targetIndex + 1)?.requestFocus()
                                },
                                onDone = {
                                    context.onCommit(question.id)
                                    focusManager.clearFocus(force = true)
                                    keyboard?.hide()
                                },
                            ),
                        )
                        if (slotOrdinal < row.slots.lastIndex) Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
            if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private data class ImageFillQuestionRow(val question: Question, val response: JsonObject, val slots: List<JsonObject>)

/** One shared option table and one answer control per question for text matching groups. */
@Composable
private fun V4SharedTextMatchingGroup(
    group: JsonObject,
    layout: SharedTextMatchingLayout,
    context: V4RenderContext,
) {
    val groupId = group.v4String("groupId")
    val questionIds = layout.rows.map { it.questionId }
    val optionIds = layout.pool.v4Array("optionIds").map { it.jsonPrimitive.content }
    val options = remember(questionIds, optionIds, context.questionById) {
        optionsFor(questionIds, optionIds, context.questionById)
    }
    val matchingSheetHeight = rememberMatchingSheetHeight(groupId)
    var activeQuestionId by remember(groupId) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        layout.leadingBlocks.forEach { V4BlockView(it, context, depth = 0) }
        Text("可选项（全组只显示一次）", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("v4-letter-match-option-$groupId-${option.id}")
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(option.id, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp)
                            .testTag("v4-letter-match-option-label-$groupId-${option.id}"))
                    V4SelectableText(V4AnnotationSources.option(option,
                        questionIds.mapNotNull(context.questionById::get), option.text.ifBlank { option.id }),
                        "v4-letter-match-option-text-$groupId-${option.id}", Modifier.weight(1f))
                }
            }
        }
        layout.rows.forEachIndexed { rowIndex, row ->
            val question = context.questionById[row.questionId] ?: return@forEachIndexed
            Column(Modifier.fillMaxWidth().questionJumpTarget(question.number).testTag("v4-letter-match-row-${question.number}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                V4BlockView(row.prompt, context, depth = 1)
                if (context.submitted && LocalQuestionReview.current != null) {
                    ReviewAnswerToken(question, context.answers[question.id].orEmpty(), Modifier.fillMaxWidth())
                } else if (options.size <= 4) {
                    Row(Modifier.fillMaxWidth().testTag("v4-letter-match-buttons-${question.number}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { option ->
                            val isSelected = context.answers[question.id].equals(option.id, ignoreCase = true)
                            val enabled = context.editable && !context.submitted && !context.saving
                            val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics {
                                    selected = isSelected
                                    contentDescription = "Q${question.number} 选项 ${option.id}${if (isSelected) "，已选中" else ""}"
                                }
                                .testTag("v4-letter-match-button-${question.number}-${option.id}")
                            val onClick = {
                                context.onActivate(question.number)
                                context.onSelect(question.id, if (isSelected) "" else option.id)
                            }
                            if (isSelected) {
                                Button(onClick = onClick, enabled = enabled, modifier = modifier,
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                                        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                                    )) { Text(option.id) }
                            } else {
                                OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
                                    Text(option.id)
                                }
                            }
                        }
                    }
                } else {
                    val selectedId = context.answers[question.id].orEmpty()
                    OutlinedButton(onClick = {
                        context.onActivate(question.number)
                        activeQuestionId = question.id
                    }, enabled = context.editable && !context.submitted && !context.saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("v4-letter-match-picker-${question.number}")) {
                        Text("Q${question.number} · ${selectedId.ifBlank { "选择字母" }}")
                    }
                }
            }
            if (rowIndex < layout.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    val activeQuestion = activeQuestionId?.let(context.questionById::get)
    if (activeQuestion != null && context.editable && !context.submitted) {
        MatchingOptionSheet(activeQuestion, options, context.answers[activeQuestion.id].orEmpty(), !context.saving,
            onSelect = { context.onSelect(activeQuestion.id, it) }, onDismiss = { activeQuestionId = null },
            heightState = matchingSheetHeight)
    }
}

@Composable
private fun V4ChoiceList(block: JsonObject, context: V4RenderContext) {
    val poolId = block.v4String("optionPoolId")
    val pool = context.pools[poolId] ?: return
    val slotIds = block.v4Array("slotIds").map { it.jsonPrimitive.content }
    val response = slotIds.firstNotNullOfOrNull(context.responses::get) ?: return
    val ids = pool.v4Array("optionIds").map { it.jsonPrimitive.content }
    val qIds = response.v4Array("questionIds").map { it.jsonPrimitive.content }
    val options = optionsFor(qIds, ids, context.questionById)
    if (response.v4String("kind") == "multipleChoice") {
        val groupQuestions = qIds.mapNotNull(context.questionById::get)
        val first = groupQuestions.firstOrNull() ?: return
        val stored = groupQuestions.firstNotNullOfOrNull { context.answers[it.id] } ?: ""
        val selected = Grader.selection(stored)
        Text("请选择 ${response.v4Int("selectCount")} 项，顺序不限 · 每项计 1 分",
            style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth().questionJumpTarget(first.number)) {
            options.forEach { option ->
                val checked = option.id in selected
                val canChoose = context.editable && !context.submitted && (checked || selected.size < response.v4Int("selectCount"))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("v4-choice-${context.group.v4String("groupId")}-${option.id}")
                    .clickable(enabled = canChoose) {
                        val next = if (checked) selected - option.id else selected + option.id
                        context.onSelect(first.id, next.sorted().joinToString(","))
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, onCheckedChange = null, enabled = canChoose)
                    V4SelectableText(V4AnnotationSources.option(option, groupQuestions, optionLabel(option)),
                        "v4-choice-text-${context.group.v4String("groupId")}-${option.id}", Modifier.weight(1f),
                        onTap = if (canChoose) ({
                            val next = if (checked) selected - option.id else selected + option.id
                            context.onSelect(first.id, next.sorted().joinToString(","))
                        }) else null)
                }
            }
        }
        Text("已选 ${selected.size}/${response.v4Int("selectCount")} 项",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("v4-multiple-choice-count-${context.group.v4String("groupId")}"))
    } else {
        // A choiceList may itself be a child of formRow. Keep its option rows
        // together vertically instead of emitting each as a horizontal Row sibling.
        Column(Modifier.fillMaxWidth()) {
            slotIds.forEach { slotId ->
                val slot = context.slots[slotId] ?: return@forEach
                val question = context.questionById[slot.v4String("questionId")] ?: return@forEach
                val heading = if (response.v4String("kind") == "singleChoice") {
                    singleChoiceHeading(context.group, block, question.number)
                } else "Q${question.number} · 选择一项"
                if (heading != null) Text(heading, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.questionJumpTarget(question.number))
                options.forEach { option ->
                    val selected = context.answers[question.id].equals(option.id, ignoreCase = true)
                    val enabled = context.editable && !context.submitted && !context.saving
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = enabled) {
                            context.onActivate(question.number); context.onSelect(question.id, option.id)
                        }
                        .semantics { contentDescription = optionLabel(option) }
                        .testTag("v4-matching-choice-${context.group.v4String("groupId")}-${question.number}-${option.id}")
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null, enabled = enabled)
                        V4SelectableText(V4AnnotationSources.option(option,
                            if (response.v4String("kind") == "matching") qIds.mapNotNull(context.questionById::get)
                            else listOf(question), optionLabel(option)),
                            "v4-choice-text-${context.group.v4String("groupId")}-${question.number}-${option.id}",
                            Modifier.weight(1f), onTap = if (enabled) ({
                                context.onActivate(question.number); context.onSelect(question.id, option.id)
                            }) else null)
                    }
                }
            }
        }
    }
}

/** Keep the question number only when the adjacent printed prompt has none. */
internal fun singleChoiceHeading(group: JsonObject, choiceList: JsonObject, number: Int): String? {
    val blocks = group.v4Array("blocks").map { it.jsonObject }
    val index = blocks.indexOfFirst { it.v4String("id") == choiceList.v4String("id") }
    val prompt = blocks.getOrNull(index - 1)
    if (prompt?.v4String("type") != "paragraph") return "Q$number"
    val text = prompt.v4Array("children").map { it.jsonObject }
        .filter { it.v4String("type") == "text" }
        .joinToString(" ") { it.v4String("text") }
    return if (Regex("^\\s*(?:Q\\s*)?$number\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) null else "Q$number"
}

@Composable
private fun V4ImageBlock(block: JsonObject, context: V4RenderContext) {
    val asset = block.v4String("asset")
    val imageId = block.v4String("id")
    val loader = LocalQuestionImages.current
    var loaded by remember(asset, loader) { mutableStateOf(false) }
    val image by produceState<Bitmap?>(null, asset, loader) {
        value = withContext(Dispatchers.IO) { loader(asset) }
        loaded = true
    }
    val bringIntoView = remember(asset) { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(context.imageAnswerCardCompact, imeBottom, image) {
        if (context.imageAnswerCardCompact && imeBottom > 0 && image != null) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    val reviewId = LocalQuestionReview.current?.activeId
    LaunchedEffect(context.imageAnswerCardActive, image, reviewId) {
        if (context.imageAnswerCardActive && image != null) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
        }
    }
    var zoom by remember(asset) { mutableStateOf(false) }
    val interactive = context.editable && !context.submitted && !context.imageAnswerCardOverview
    val overlays = block.v4Array("overlays").map { it.jsonObject }
    val actualWidth = block.v4Int("widthPx")
    val actualHeight = block.v4Int("heightPx")
    val drawOverlays = !context.imageFillAnswerList && !context.imageAnswerCardOverview
    val hitTestOverlays = drawOverlays
    val onMainImageTap: () -> Unit = {
        if (context.imageAnswerCardOverview) context.onImageTap()
        else {
            if (context.imageFillAnswerList) context.beforeImageZoom()
            zoom = true
        }
    }
    if (image != null) {
        V4HotspotCanvas(image!!, actualWidth, actualHeight, overlays, context, interactive,
            Modifier.fillMaxWidth().aspectRatio(actualWidth.toFloat() / actualHeight)
                .bringIntoViewRequester(bringIntoView),
            zoomable = false, canvasTag = "v4-image-canvas-$imageId",
            targetTagPrefix = "v4-hotspot-target", onBlankTap = onMainImageTap,
            drawOverlays = drawOverlays, hitTestOverlays = hitTestOverlays)
        Text(if (context.imageAnswerCardOverview) "点击图片打开答题卡" else if (context.submitted) "点击图片空白处可放大" else if (context.imageFillAnswerList) "点击图片放大" else "点击图片中的空位作答；点图片空白处可放大",
            style = MaterialTheme.typography.labelSmall)
        if (context.imageAnswerCardOverview) {
            TextButton(onClick = { zoom = true }, modifier = Modifier.testTag("v4-image-zoom-$imageId")) {
                Text("放大图片")
            }
        }
        if (zoom) Dialog(onDismissRequest = { zoom = false }) {
            Surface(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(8.dp)) {
                    TextButton(onClick = { zoom = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("关闭 · 双指缩放与平移")
                    }
                    V4HotspotCanvas(image!!, actualWidth, actualHeight, overlays, context, interactive,
                        Modifier.fillMaxWidth().height(540.dp).clipToBounds(), zoomable = true,
                        canvasTag = "v4-image-canvas-zoom-$imageId", targetTagPrefix = "v4-hotspot-target-zoom",
                        drawOverlays = drawOverlays, hitTestOverlays = hitTestOverlays)
                }
            }
        }
    } else if (loaded) Text("题目图片加载失败，请返回题库重试", color = MaterialTheme.colorScheme.error)
    else CircularProgressIndicator()
}

@Composable
private fun V4HotspotCanvas(
    image: Bitmap,
    widthPx: Int,
    heightPx: Int,
    overlays: List<JsonObject>,
    context: V4RenderContext,
    interactive: Boolean,
    modifier: Modifier,
    zoomable: Boolean,
    canvasTag: String,
    targetTagPrefix: String,
    onBlankTap: (() -> Unit)? = null,
    drawOverlays: Boolean = true,
    hitTestOverlays: Boolean = true,
) {
    var scale by remember(image, zoomable) { mutableFloatStateOf(1f) }
    var pan by remember(image, zoomable) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val maxW = maxWidth
        val maxH = maxHeight
        val aspect = widthPx.toFloat() / heightPx
        val fittedWidth = if (maxH != Dp.Infinity && maxH * aspect < maxW) maxH * aspect else maxW
        val fittedHeight = fittedWidth / aspect
        val viewportWidthPx = with(density) { maxW.toPx() }
        val viewportHeightPx = with(density) { maxH.toPx() }
        val fittedWidthPx = with(density) { fittedWidth.toPx() }
        val fittedHeightPx = with(density) { fittedHeight.toPx() }
        val imageLeftPx = (viewportWidthPx - fittedWidthPx) / 2f
        val imageTopPx = (viewportHeightPx - fittedHeightPx) / 2f
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            if (zoomable) {
                val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
                val limitX = (viewportWidthPx * (nextScale - 1f) / 2f).coerceAtLeast(0f)
                val limitY = (viewportHeightPx * (nextScale - 1f) / 2f).coerceAtLeast(0f)
                scale = nextScale
                pan = Offset((pan.x + panChange.x).coerceIn(-limitX, limitX),
                    (pan.y + panChange.y).coerceIn(-limitY, limitY))
                if (scale == 1f) pan = Offset.Zero
            }
        }
        Box(Modifier.fillMaxSize().testTag(canvasTag)
            .then(if (context.imageAnswerCardOverview && !zoomable) Modifier.semantics {
                contentDescription = "题目图片，双击打开答题卡"
                onClick(label = "打开答题卡") { onBlankTap?.invoke(); true }
            } else Modifier)
            .then(if (zoomable) Modifier.transformable(transformState) else Modifier)
            .pointerInput(overlays, scale, pan, interactive, fittedWidthPx, fittedHeightPx,
                drawOverlays, hitTestOverlays) {
                detectTapGestures { point ->
                    if (fittedWidthPx <= 0f || fittedHeightPx <= 0f) return@detectTapGestures
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val baseX = (point.x - centerX - pan.x) / scale + centerX
                    val baseY = (point.y - centerY - pan.y) / scale + centerY
                    val imageX = baseX - imageLeftPx
                    val imageY = baseY - imageTopPx
                    if (imageX !in 0f..fittedWidthPx || imageY !in 0f..fittedHeightPx) return@detectTapGestures
                    val sourceX = imageX / fittedWidthPx * widthPx
                    val sourceY = imageY / fittedHeightPx * heightPx
                    val minTouchPx = with(density) { 48.dp.toPx() } / scale
                    val target = if (hitTestOverlays) overlays.mapNotNull { overlay ->
                        val rect = overlay.v4Object("rectPx")
                        val x = rect.v4Int("x").toFloat(); val y = rect.v4Int("y").toFloat()
                        val w = rect.v4Int("width").toFloat(); val h = rect.v4Int("height").toFloat()
                        val sx = fittedWidthPx / widthPx; val sy = fittedHeightPx / heightPx
                        val left = x * sx; val top = y * sy; val displayW = w * sx; val displayH = h * sy
                        val padX = ((minTouchPx - displayW) / 2f).coerceAtLeast(0f) / sx
                        val padY = ((minTouchPx - displayH) / 2f).coerceAtLeast(0f) / sy
                        val inside = sourceX >= x - padX && sourceX <= x + w + padX &&
                            sourceY >= y - padY && sourceY <= y + h + padY
                        if (!inside) null else {
                            val distance = hypot((sourceX - x - w / 2f) * sx, (sourceY - y - h / 2f) * sy)
                            overlay to distance
                        }
                    }.minByOrNull { it.second }?.first else null
                    val slotId = target?.get("slotId")?.jsonPrimitive?.content
                    if (hitTestOverlays && interactive && slotId != null) {
                        context.onOpenSlot(slotId)
                    } else {
                        onBlankTap?.invoke()
                    }
                }
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(fittedWidth).height(fittedHeight)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y)) {
                Image(image.asImageBitmap(), "题目图片", Modifier.fillMaxSize())
                if (drawOverlays) overlays.forEach { overlay ->
                    val slotId = overlay.v4String("slotId")
                    val slot = context.slots[slotId] ?: return@forEach
                    val question = context.questionById[slot.v4String("questionId")] ?: return@forEach
                    val rect = overlay.v4Object("rectPx")
                    val response = context.responses[slotId]
                    val saved = context.answers[question.id].orEmpty()
                    val answer = if (response?.v4String("kind") == "fill")
                        splitForLayout(question, response, saved).getOrElse(slot.v4Int("slotIndex")) { "" }
                    else saved
                    val x = fittedWidth * (rect.v4Int("x").toFloat() / widthPx)
                    val y = fittedHeight * (rect.v4Int("y").toFloat() / heightPx)
                    val w = fittedWidth * (rect.v4Int("width").toFloat() / widthPx)
                    val h = fittedHeight * (rect.v4Int("height").toFloat() / heightPx)
                    val text = answer
                    val stateText = if (answer.isBlank()) "未作答" else "已作答 $answer"
                    Box(Modifier.offset(x, y).size(w.coerceAtLeast(8.dp), h.coerceAtLeast(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                        .border(1.dp, if (context.submitted && answer.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary), contentAlignment = Alignment.Center) {
                        Text(text.take(18), maxLines = 1, overflow = TextOverflow.Clip,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(Modifier.offset(x = x + w / 2 - 24.dp, y = y + h / 2 - 24.dp).size(48.dp)
                        .testTag("$targetTagPrefix-${question.number}-${slot.v4Int("slotIndex") + 1}")
                        .semantics {
                            role = Role.Button
                            contentDescription = "Q${question.number} ${question.prompt.take(48)} · $stateText"
                            onClick(label = "作答 Q${question.number}") {
                                context.onActivate(question.number)
                                context.onOpenSlot(slotId)
                                true
                            }
                        })
                }
            }
        }
    }
}

@Composable
private fun V4FillDialog(
    question: Question,
    slot: JsonObject,
    response: JsonObject,
    saved: String,
    onEdit: (String, String) -> Unit,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(question, response, saved) { splitForLayout(question, response, saved) }
    var draft by remember(question.id, slot.v4String("slotId"), saved) {
        mutableStateOf(values.getOrElse(slot.v4Int("slotIndex")) { "" })
    }
    val slotIndex = slot.v4Int("slotIndex")
    val questionId = question.id
    fun update(value: String) {
        draft = value
        val next = values.toMutableList().also { while (it.size < 2) it += "" }
        next[slotIndex] = value
        onEdit(questionId, encodeForLayout(question, response, next))
    }
    AlertDialog(
        onDismissRequest = { onCommit(questionId); onDismiss() },
        title = { Text("Q${question.number}${if (response.v4Array("slotIds").size > 1) " · 第 ${slotIndex + 1} 空" else ""}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, ::update, modifier = Modifier.fillMaxWidth().testTag("v4-hotspot-input-${question.number}-${slotIndex + 1}"),
                    label = { Text("输入答案") }, singleLine = true, keyboardOptions = KeyboardOptions.Default)
                if (question.instructions.isNotBlank()) AnnotatableText(
                    com.eenglish.listening.domain.annotation.AnnotationDocument.single(question.instructions,
                        "question", "${question.id}/instructions", "und"),
                    style = MaterialTheme.typography.bodySmall, viewTag = "v4-fill-instructions-${question.number}")
                else Text("按题目要求填写", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onCommit(questionId); onDismiss() }) { Text("完成") } },
        dismissButton = { TextButton(onClick = { onCommit(questionId); onDismiss() }) { Text("关闭") } },
    )
}

private fun splitForLayout(question: Question, response: JsonObject, answer: String): List<String> {
    if (response.v4Array("slotIds").size != 2) return listOf(answer)
    val joiner = question.answerSeparator ?: response["joiner"]?.jsonPrimitive?.content ?: "and"
    return answer.split(Regex("(?:^|\\s+)${Regex.escape(joiner)}(?:\\s+|$)", RegexOption.IGNORE_CASE), limit = 2)
}

private fun encodeForLayout(question: Question, response: JsonObject, slots: List<String>): String {
    if (response.v4Array("slotIds").size != 2) return slots.firstOrNull().orEmpty()
    val joiner = question.answerSeparator ?: response["joiner"]?.jsonPrimitive?.content ?: "and"
    val first = slots.getOrElse(0) { "" }.trim()
    val second = slots.getOrElse(1) { "" }.trim()
    return if (first.isBlank() && second.isBlank()) "" else "$first $joiner $second"
}

private fun slotCount(questionId: String, context: V4RenderContext): Int =
    context.slots.values.count { it.v4String("questionId") == questionId }

private fun optionsFor(questionIds: List<String>, optionIds: List<String>, byId: Map<String, Question>): List<Option> {
    val source = questionIds.firstNotNullOfOrNull(byId::get)?.options.orEmpty()
    return optionIds.mapNotNull { id -> source.firstOrNull { it.id == id } }
}

private fun optionLabel(option: Option): String = if (option.text.isBlank() || option.text.equals(option.id, true))
    option.id else "${option.id}. ${option.text}"

private fun containsChoiceList(block: JsonObject, poolIds: Set<String>): Boolean =
    block["type"]?.jsonPrimitive?.content == "choiceList" && block["optionPoolId"]?.jsonPrimitive?.content in poolIds ||
        block["children"]?.jsonArray?.any { it is JsonObject && containsChoiceList(it, poolIds) } == true ||
        block["nodes"]?.jsonArray?.any { node -> node.jsonObject["children"]?.jsonArray?.any {
            it is JsonObject && containsChoiceList(it, poolIds)
        } == true } == true ||
        block["rows"]?.jsonArray?.any { row -> row.jsonObject["cells"]?.jsonArray?.any { cell ->
            cell.jsonObject["children"]?.jsonArray?.any { it is JsonObject && containsChoiceList(it, poolIds) } == true
        } == true } == true

/** The image stays in the practice page; this panel edits one question from that image's group. */
@Composable
fun LayoutV4ImageAnswerCard(
    group: JsonObject,
    questions: List<Question>,
    currentIndex: Int,
    answers: Map<String, String>,
    editable: Boolean,
    submitted: Boolean,
    saving: Boolean,
    onSelect: (String, String) -> Unit,
    onEdit: (String, String) -> Unit,
    onCommit: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onResizeDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onInputFocus: () -> Unit = {},
) {
    val question = questions.getOrNull(currentIndex) ?: return
    val response = group.v4Array("responses").map { it.jsonObject }.firstOrNull { candidate ->
        candidate.v4Array("questionIds").any { it.jsonPrimitive.content == question.id }
    } ?: return
    val slots = group.v4Array("slots").map { it.jsonObject }
        .filter { it.v4String("questionId") == question.id }.sortedBy { it.v4Int("slotIndex") }
    val caption = remember(group, question.id) { imageQuestionCaption(group, question.id, question.number) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focusOnNavigation by remember { mutableStateOf(false) }
    var showInstructions by remember(question.id) { mutableStateOf(false) }
    fun navigate(delta: Int) {
        val target = questions.getOrNull(currentIndex + delta) ?: return
        focusOnNavigation = compact && target.isTextInput && editable && !submitted
        if (!focusOnNavigation) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        onCommit(question.id)
        if (delta < 0) onPrevious() else onNext()
    }
    @Composable
    fun NavigationButton(previous: Boolean) {
        TextButton(onClick = { navigate(if (previous) -1 else 1) },
            enabled = if (previous) currentIndex > 0 else currentIndex < questions.lastIndex,
            contentPadding = if (compact) PaddingValues(0.dp) else ButtonDefaults.TextButtonContentPadding,
            modifier = Modifier.widthIn(min = 48.dp)
                .testTag(if (previous) "v4-image-card-previous" else "v4-image-card-next")
                .semantics { contentDescription = if (previous) "上一题" else "下一题" }) {
            Text(if (compact) { if (previous) "‹" else "›" } else { if (previous) "上一题" else "下一题" })
        }
    }
    if (showInstructions) AlertDialog(onDismissRequest = { showInstructions = false },
        title = { Text("Q${question.number} · 作答要求") },
        text = { AnnotatableText(com.eenglish.listening.domain.annotation.AnnotationDocument.single(
            question.instructions.ifBlank { imageFillLimitHint(question) }, "question", "${question.id}/instructions", "und")) },
        confirmButton = { TextButton(onClick = { showInstructions = false }) { Text("关闭") } })

    Surface(modifier.fillMaxWidth().testTag("v4-image-answer-card"),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 6.dp,
        shadowElevation = 6.dp) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().heightIn(min = if (compact) 16.dp else 36.dp).draggable(
                state = rememberDraggableState { onResizeDelta(it) }, orientation = Orientation.Vertical,
                enabled = !compact,
            ).testTag("v4-image-card-drag-handle").semantics {
                contentDescription = "上下拖动调整答题卡高度"
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                Box(Modifier.width(36.dp).height(4.dp).background(
                    MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Q${question.number}", style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).testTag("v4-image-card-question"))
                Text("${currentIndex + 1}/${questions.size}", style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("v4-image-card-position"))
                if (compact) {
                    NavigationButton(previous = true)
                    NavigationButton(previous = false)
                }
                TextButton(onClick = {
                    focusManager.clearFocus(force = true)
                    onCommit(question.id)
                    onClose()
                }, modifier = Modifier.testTag("v4-image-card-close")) { Text("收起") }
            }
            HorizontalDivider()
            key(question.id) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    if (!compact && caption.isNotBlank()) V4SelectableText(imageCaptionProjection(group, question.id, question.number, questions.associateBy { it.id }),
                        "v4-image-card-caption", style = MaterialTheme.typography.titleMedium)
                    if (!compact && question.instructions.isNotBlank()) AnnotatableText(
                        com.eenglish.listening.domain.annotation.AnnotationDocument.single(question.instructions,
                            "question", "${question.id}/instructions", "und"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        viewTag = "v4-image-card-instructions-${question.number}")
                    when (response.v4String("kind")) {
                        "fill" -> {
                            val parts = splitForLayout(question, response, answers[question.id].orEmpty())
                            val focusRequesters = remember(question.id, slots.size) {
                                List(slots.size) { FocusRequester() }
                            }
                            LaunchedEffect(question.id) {
                                if (focusOnNavigation) {
                                    focusOnNavigation = false
                                    focusRequesters.firstOrNull()?.requestFocus()
                                    keyboard?.show()
                                }
                            }
                            slots.forEachIndexed { ordinal, slot ->
                                val slotIndex = slot.v4Int("slotIndex")
                                var hadFocus by remember(question.id, slotIndex) { mutableStateOf(false) }
                                val bringIntoView = remember { BringIntoViewRequester() }
                                LaunchedEffect(hadFocus, compact) {
                                    if (hadFocus) {
                                        withFrameNanos { }
                                        bringIntoView.bringIntoView()
                                    }
                                }
                                OutlinedTextField(
                                    value = parts.getOrElse(slotIndex) { "" },
                                    onValueChange = { next ->
                                        if (next.length <= 512) {
                                            val values = parts.toMutableList()
                                            while (values.size < slots.size) values += ""
                                            values[slotIndex] = next
                                            onEdit(question.id, encodeForLayout(question, response, values))
                                        }
                                    },
                                    enabled = editable && !submitted,
                                    label = { Text(if (slots.size == 1) "Q${question.number} 答案" else "第 ${ordinal + 1} 空") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = if (ordinal < slots.lastIndex) ImeAction.Next else ImeAction.Done,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = {
                                            if (ordinal < slots.lastIndex) {
                                                onCommit(question.id)
                                                focusRequesters[ordinal + 1].requestFocus()
                                            } else navigate(1)
                                        },
                                        onDone = {
                                            onCommit(question.id)
                                            focusManager.clearFocus(force = true)
                                            keyboard?.hide()
                                        },
                                    ),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequesters[ordinal])
                                        .bringIntoViewRequester(bringIntoView)
                                        .onFocusChanged { state ->
                                            if (state.isFocused) {
                                                hadFocus = true
                                                onInputFocus()
                                            }
                                            else if (hadFocus) {
                                                hadFocus = false
                                                onCommit(question.id)
                                            }
                                        }
                                        .testTag(if (slots.size == 1) "v4-image-card-input-${question.number}"
                                            else "v4-image-card-input-${question.number}-${ordinal + 1}"),
                                )
                            }
                            if (compact) Text(imageFillLimitHint(question) + " · 查看要求",
                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                                    .clickable(role = Role.Button) { showInstructions = true }
                                    .testTag("v4-image-card-compact-instructions"))
                        }
                        "singleChoice", "matching" -> {
                            val poolId = response.v4String("optionPoolId")
                            val allowed = group.v4Array("optionPools").map { it.jsonObject }
                                .firstOrNull { it.v4String("poolId") == poolId }
                                ?.v4Array("optionIds")?.map { it.jsonPrimitive.content }.orEmpty()
                            val options = allowed.mapNotNull { id -> question.options.firstOrNull { it.id == id } }
                            options.forEach { option ->
                                val selected = answers[question.id].equals(option.id, ignoreCase = true)
                                val enabled = editable && !submitted && !saving
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(enabled = enabled) { onSelect(question.id, option.id) }
                                    .testTag("v4-image-card-option-${question.number}-${option.id}")
                                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                                    V4SelectableText(V4AnnotationSources.option(option,
                                        if (response.v4String("kind") == "matching") questions else listOf(question), optionLabel(option)),
                                        "v4-image-card-option-text-${question.number}-${option.id}", Modifier.weight(1f),
                                        onTap = if (enabled) ({ onSelect(question.id, option.id) }) else null)
                                }
                            }
                        }
                    }
                    if (submitted) Text("你的答案：${answers[question.id].orEmpty().ifBlank { "未作答" }}",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!compact) HorizontalDivider()
            if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                NavigationButton(previous = true)
                NavigationButton(previous = false)
            }
        }
    }
}

private fun imageFillLimitHint(question: Question): String {
    val limit = question.wordLimit ?: return "按题目要求填写"
    if (limit.numberOnly) return limit.maxNumbers?.let { "最多${it}个数字" } ?: "限数字"
    val words = "最多${limit.maxWords}个词"
    return when {
        limit.wordsOrNumber -> "$words，或数字"
        limit.maxNumbers == 0 -> words
        limit.maxNumbers == null -> "$words，可含数字"
        else -> "$words，可加${limit.maxNumbers}个数字"
    }
}

/** Pull the row or flow-node label for the current slot without repeating the entire question bank. */
internal fun imageQuestionCaption(group: JsonObject, questionId: String, number: Int): String =
    imageCaptionProjection(group, questionId, number, emptyMap()).text

private fun imageCaptionProjection(group: JsonObject, questionId: String, number: Int,
    questions: Map<String, Question>): AnnotationProjection {
    val targets = group.v4Array("slots").map { it.jsonObject }
        .filter { it.v4String("questionId") == questionId }.map { it.v4String("slotId") }.toSet()
    if (targets.isEmpty()) return AnnotationProjection("", emptyList())
    fun containsSlot(element: JsonElement): Boolean = when (element) {
        is JsonObject -> (element["slotId"]?.jsonPrimitive?.contentOrNull in targets) ||
            (element["slotIds"] as? JsonArray)?.any { it.jsonPrimitive.content in targets } == true ||
            element.values.any(::containsSlot)
        is JsonArray -> element.any(::containsSlot)
        else -> false
    }
    fun texts(element: JsonElement): List<AnnotationProjection> = when (element) {
        is JsonObject -> if (element["type"]?.jsonPrimitive?.contentOrNull == "text") {
            listOf(V4AnnotationSources.block(group.v4String("groupId"), element, questions))
        } else element.values.flatMap(::texts)
        is JsonArray -> element.flatMap(::texts)
        else -> emptyList()
    }
    val captions = mutableListOf<AnnotationProjection>()
    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                if (element["type"]?.jsonPrimitive?.contentOrNull == "image") return
                val type = element["type"]?.jsonPrimitive?.contentOrNull
                val isLabelContainer = type in setOf("formRow", "paragraph", "listItem") ||
                    type == null && element["children"] is JsonArray
                if (isLabelContainer && containsSlot(element)) {
                    val joined = AnnotationProjection.join(texts(element))
                    val label = joined.text.trim()
                    if (label.isNotBlank() && label != number.toString()) {
                        val start = joined.text.length - joined.text.trimStart().length
                        captions += joined.slice(start, start + label.length)
                    }
                    return
                }
                element.values.forEach(::visit)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }
    group.v4Array("blocks").forEach(::visit)
    return AnnotationProjection.join(captions.distinctBy { it.text }, " · ")
}

private fun JsonObject.v4String(name: String): String = this[name]?.jsonPrimitive?.content
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
private fun JsonObject.v4Int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
private fun JsonObject.v4Array(name: String): List<JsonElement> = this[name]?.jsonArray?.toList()
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
private fun JsonObject.v4Object(name: String): JsonObject = this[name]?.jsonObject
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
