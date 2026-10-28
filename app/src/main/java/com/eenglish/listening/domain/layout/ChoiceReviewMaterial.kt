package com.eenglish.listening.domain.layout

import com.eenglish.listening.domain.annotation.AnnotationProjection
import com.eenglish.listening.domain.model.Question
import kotlinx.serialization.json.*

data class ChoiceReviewMaterial(val instructions: List<AnnotationProjection>,
    val paragraphs: List<AnnotationProjection>, val images: List<String>)

/** Select by response slots, keeping original annotation sources and shared reference material. */
fun choiceReviewMaterial(group: JsonObject, questions: List<Question>, questionIds: Set<String>): ChoiceReviewMaterial {
    val byId = questions.associateBy { it.id }
    val targets = (group["slots"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { it["questionId"]?.jsonPrimitive?.content in questionIds }
        .mapNotNull { it["slotId"]?.jsonPrimitive?.content }.toSet()
    fun slots(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> buildSet {
            element["slotId"]?.jsonPrimitive?.content?.let(::add)
            (element["slotIds"] as? JsonArray)?.forEach { add(it.jsonPrimitive.content) }
            element.values.forEach { addAll(slots(it)) }
        }
        is JsonArray -> element.flatMap { slots(it) }.toSet()
        else -> emptySet()
    }
    fun select(elements: List<JsonElement>): List<JsonElement> {
        // Validated single-choice groups pair a prompt paragraph with the next choiceList.
        val pairedPrompts = elements.indices.filter { index ->
            (elements[index] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "paragraph" &&
                (elements.getOrNull(index + 1) as? JsonObject)?.get("type")?.jsonPrimitive?.content == "choiceList"
        }.toSet()
        return elements.filterIndexed { index, element ->
            val bindings = slots(if (index in pairedPrompts) elements[index + 1] else element)
            bindings.isEmpty() || bindings.any { it in targets }
        }
    }
    val images = linkedSetOf<String>()
    val groupId = group["groupId"]?.jsonPrimitive?.content.orEmpty()
    fun content(element: JsonElement): List<AnnotationProjection> = when (element) {
        is JsonArray -> select(element).flatMap { content(it) }
        is JsonObject -> when (element["type"]?.jsonPrimitive?.content) {
            "text" -> listOf(V4AnnotationSources.block(groupId, element, byId))
            "image" -> { element["asset"]?.jsonPrimitive?.content?.let(images::add); emptyList() }
            "slot", "choiceList" -> emptyList()
            else -> listOf("children", "rows", "cells", "nodes").flatMap { key ->
                element[key]?.let { content(it) }.orEmpty()
            }
        }
        else -> emptyList()
    }
    fun paragraphs(key: String): List<AnnotationProjection> = select((group[key] as? JsonArray).orEmpty())
        .map { AnnotationProjection.join(content(it)) }.filter { it.text.isNotBlank() }
    val instructions = paragraphs("instructions")
    val paragraphs = paragraphs("blocks")
    return ChoiceReviewMaterial(instructions, paragraphs, images.toList())
}
