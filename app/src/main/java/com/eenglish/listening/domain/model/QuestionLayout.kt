package com.eenglish.listening.domain.model

import kotlinx.serialization.Serializable

/** Independent, versioned display metadata. It never contains answers or scoring state. */
@Serializable
data class QuestionLayoutDocument(
    val schemaVersion: Int,
    val partId: String,
    val basePartSha256: String,
    val groups: List<QuestionLayoutGroup>,
)

@Serializable
data class QuestionLayoutGroup(
    val groupId: String,
    val layoutKind: String,
    val questionIds: List<String>,
    val promptHashes: Map<String, String>,
    val source: LayoutSourceTrace,
    val blocks: List<LayoutBlock>,
)

@Serializable
data class LayoutSourceTrace(
    val resource: String,
    val sha256: String,
    val locator: String,
    val sourceKind: String,
)

@Serializable
data class LayoutBlock(
    val type: String,
    val text: String? = null,
    val questionId: String? = null,
    val questionNumber: Int? = null,
    val slotIndex: Int? = null,
    val source: LayoutBlockSource? = null,
    val children: List<LayoutBlock> = emptyList(),
    val marker: String? = null,
    val label: String? = null,
)

@Serializable
data class LayoutBlockSource(
    val kind: String,
    val questionId: String? = null,
    val startUtf16: Int? = null,
    val endUtf16: Int? = null,
    val resource: String? = null,
    val locator: String? = null,
)
