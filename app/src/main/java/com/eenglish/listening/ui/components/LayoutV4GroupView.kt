package com.eenglish.listening.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
) {
    val groupId = group.v4String("groupId")
    val imageFillAnswerList = remember(group) { usesImageFillAnswerList(group) }
    val questionById = remember(questions) { questions.associateBy { it.id } }
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
    var activeSlotId by remember(groupId) { mutableStateOf<String?>(null) }
    var activeImageFillQuestionId by remember(groupId) { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = remember(group, questions, answers, editable, submitted, saving, slots, responses, pools,
        imageFillAnswerList, activeImageFillQuestionId) {
        V4RenderContext(group, questionById, slots, responses, pools, answers, editable, submitted,
            saving, onSelect, onEdit, onCommit, onActivate, { slotId -> activeSlotId = slotId },
            imageFillAnswerList, {
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
        if (!hasMatchingChoiceList) {
            group.v4Array("responses").map { it.jsonObject }.filter { it.v4String("kind") == "matching" }
                .distinctBy { it.v4String("optionPoolId") }.forEach { response ->
                    val pool = pools[response.v4String("optionPoolId")] ?: return@forEach
                    val optionIds = pool.v4Array("optionIds").map { it.jsonPrimitive.content }
                    val options = optionsFor(response.v4Array("questionIds").map { it.jsonPrimitive.content },
                        optionIds, questionById)
                    Text("可选项", style = MaterialTheme.typography.labelLarge)
                    options.forEach { option ->
                        Text(optionLabel(option), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("v4-matching-option-${groupId}-${option.id}"))
                    }
                }
        }
        group.v4Array("blocks").forEachIndexed { index, item ->
            V4BlockView(item.jsonObject, context, 0, numberedOrdinalAt(group.v4Array("blocks"), index))
        }
        if (imageFillAnswerList) {
            V4ImageAnswerList(group, context) { questionId -> activeImageFillQuestionId = questionId }
        }
        if (submitted) {
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
                    val usedElsewhere = reusedMatchingOptionIds(question.id, activeResponse, context)
                    // Answers refresh after the serial Room write. Keep choices disabled
                    // while saving so another question cannot reuse a stale selection.
                    MatchingOptionSheet(question, options, answers[question.id].orEmpty(), !saving,
                        onSelect = { option -> onSelect(question.id, option); activeSlotId = null },
                        onDismiss = { activeSlotId = null },
                        disabledOptionIds = usedElsewhere)
                }
            }
        }
    }
}

private data class V4RenderContext(
    val group: JsonObject,
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
    val beforeImageZoom: () -> Unit,
)

@Composable
private fun V4BlockView(block: JsonObject, context: V4RenderContext, depth: Int, numberedOrdinal: Int? = null) {
    if (depth > 8) return
    when (block.v4String("type")) {
        "text" -> Text(block.v4String("text"), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("v4-text-${block.v4String("id")}"))
        "slot" -> if (!context.imageFillAnswerList) {
            context.slots[block.v4String("slotId")]?.let { V4SlotControl(it, context) }
        }
        "choiceList" -> V4ChoiceList(block, context)
        "image" -> V4ImageBlock(block, context)
        "paragraph" -> FlowRowLayout(block.v4Array("children"), context, depth + 1)
        "formRow" -> Row(Modifier.fillMaxWidth().testTag("v4-form-row-${block.v4String("id")}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            block.v4Array("children").forEachIndexed { index, child ->
                V4BlockView(child.jsonObject, context, depth + 1,
                    numberedOrdinalAt(block.v4Array("children"), index))
            }
        }
        "listItem" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val marker = v4ListMarkerLabel(block.v4String("marker"), hasExplicitNumberPrefix(block), numberedOrdinal)
            if (marker.isNotEmpty()) {
                Text(marker, modifier = Modifier.testTag("v4-list-marker-${block.v4String("id")}"))
            }
            FlowRowLayout(block.v4Array("children"), context, depth + 1)
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
private fun FlowRowLayout(children: List<JsonElement>, context: V4RenderContext, depth: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            children.forEachIndexed { index, child ->
                V4BlockView(child.jsonObject, context, depth, numberedOrdinalAt(children, index))
            }
        }
    }
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
    val questionId = slot.v4String("questionId")
    val question = context.questionById[questionId] ?: return
    val slotId = slot.v4String("slotId")
    val response = context.responses[slotId] ?: return
    val index = slot.v4Int("slotIndex")
    val answer = context.answers[questionId].orEmpty()
    val parts = splitForLayout(question, response, answer)
    when (slot.v4String("kind")) {
        "fill" -> {
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
                label = { Text("Q${question.number}${if (slotCount(questionId, context) > 1) " · 空${index + 1}" else ""}") },
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
                Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)
                    .testTag("v4-image-list-row-${question.number}"),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(question.number.toString(), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(min = 48.dp).semantics {
                        contentDescription = "题号 ${question.number}"
                    })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.slots.forEachIndexed { slotOrdinal, slot ->
                        val slotId = slot.v4String("slotId")
                        val slotIndex = slot.v4Int("slotIndex")
                        val targetIndex = focusIndexBySlotId[slotId] ?: return@forEachIndexed
                        val value = answerParts.getOrElse(slotIndex) { "" }
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
            if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private data class ImageFillQuestionRow(val question: Question, val response: JsonObject, val slots: List<JsonObject>)

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
        Column(Modifier.fillMaxWidth()) {
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
                    Text(optionLabel(option), Modifier.weight(1f))
                }
            }
        }
        groupQuestions.forEach { q ->
            Text("Q${q.number} · 本组选项共用 · 当前 ${Grader.selection(context.answers[q.id]).size}/${response.v4Int("selectCount")}",
                style = MaterialTheme.typography.bodySmall)
        }
    } else {
        // A choiceList may itself be a child of formRow. Keep its option rows
        // together vertically instead of emitting each as a horizontal Row sibling.
        Column(Modifier.fillMaxWidth()) {
            slotIds.forEach { slotId ->
                val slot = context.slots[slotId] ?: return@forEach
                val question = context.questionById[slot.v4String("questionId")] ?: return@forEach
                val usedElsewhere = reusedMatchingOptionIds(question.id, response, context)
                Text("Q${question.number} · 选择一项", style = MaterialTheme.typography.labelLarge)
                options.forEach { option ->
                    val selected = context.answers[question.id].equals(option.id, ignoreCase = true)
                    val usedByOtherQuestion = usedElsewhere.any { it.equals(option.id, ignoreCase = true) }
                    val enabled = context.editable && !context.submitted && !context.saving && !usedByOtherQuestion
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = enabled) {
                            context.onActivate(question.number); context.onSelect(question.id, option.id)
                        }
                        .semantics {
                            contentDescription = if (usedByOtherQuestion) "${optionLabel(option)}，已被其他题使用"
                                else optionLabel(option)
                        }
                        .testTag("v4-matching-choice-${context.group.v4String("groupId")}-${question.number}-${option.id}")
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null, enabled = enabled)
                        Text(optionLabel(option), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Options already assigned to other questions in this matching response cannot be reused. */
private fun reusedMatchingOptionIds(
    questionId: String,
    response: JsonObject,
    context: V4RenderContext,
): Set<String> {
    if (response.v4String("kind") != "matching" ||
        response["reuse"]?.jsonPrimitive?.contentOrNull != "forbidden") return emptySet()
    return response.v4Array("questionIds").asSequence()
        .map { it.jsonPrimitive.content }
        .filter { it != questionId }
        .mapNotNull { context.answers[it]?.takeIf(String::isNotBlank)?.trim() }
        .map { it.uppercase() }
        .toSet()
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
    var zoom by remember(asset) { mutableStateOf(false) }
    val interactive = context.editable && !context.submitted
    val overlays = block.v4Array("overlays").map { it.jsonObject }
    val actualWidth = block.v4Int("widthPx")
    val actualHeight = block.v4Int("heightPx")
    val drawOverlays = !context.imageFillAnswerList
    val hitTestOverlays = !context.imageFillAnswerList
    val onMainImageTap: () -> Unit = {
        if (context.imageFillAnswerList) context.beforeImageZoom()
        zoom = true
    }
    if (image != null) {
        V4HotspotCanvas(image!!, actualWidth, actualHeight, overlays, context, interactive,
            Modifier.fillMaxWidth().aspectRatio(actualWidth.toFloat() / actualHeight),
            zoomable = false, canvasTag = "v4-image-canvas-$imageId",
            targetTagPrefix = "v4-hotspot-target", onBlankTap = onMainImageTap,
            drawOverlays = drawOverlays, hitTestOverlays = hitTestOverlays)
        Text(if (context.imageFillAnswerList) "点击图片放大" else "点击图片中的空位作答；点图片空白处可放大",
            style = MaterialTheme.typography.labelSmall)
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
                Text(question.instructions.ifBlank { "按题目要求填写" }, style = MaterialTheme.typography.bodySmall)
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

private fun JsonObject.v4String(name: String): String = this[name]?.jsonPrimitive?.content
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
private fun JsonObject.v4Int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
private fun JsonObject.v4Array(name: String): List<JsonElement> = this[name]?.jsonArray?.toList()
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
private fun JsonObject.v4Object(name: String): JsonObject = this[name]?.jsonObject
    ?: throw IllegalArgumentException("INVALID_LAYOUT_FIELD")
