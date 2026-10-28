package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Reuse the material's read-only answer tokens; their taps open a result rather than an editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FillReviewMaterialInteraction(enabled: Boolean, content: @Composable () -> Unit) {
    val review = LocalQuestionReview.current
    var resultId by remember(review?.items) { mutableStateOf<String?>(null) }
    val result = if (enabled) review?.items?.firstOrNull { it.id == resultId } else null
    // The tapped blank is already visible. Opening/closing its result must not relocate the material.
    LaunchedEffect(resultId, review?.activeId) {
        if (resultId != null && resultId == review?.activeId) review?.onLocated?.invoke()
    }
    val interaction = if (enabled && review != null) review.copy(
        select = { id -> resultId = id; review.select(id) },
        locateActive = review.locateActive && resultId == null,
    ) else review
    CompositionLocalProvider(LocalQuestionReview provides interaction, content = content)
    if (result != null) {
        ModalBottomSheet(onDismissRequest = { resultId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.65f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("fill-review-result-sheet"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("fill-review-result-title"))
                    TextButton(onClick = { resultId = null }, modifier = Modifier.testTag("fill-review-result-close")) {
                        Text("关闭")
                    }
                }
                ReviewResultContent(result, showUserAnswer = true)
            }
        }
    }
}
