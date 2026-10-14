package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.model.Option
import com.eenglish.listening.domain.model.Question
import kotlinx.coroutines.launch

/** A full-text, scrollable answer picker for one Matching question. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchingOptionSheet(
    question: Question,
    options: List<Option>,
    selectedAnswer: String,
    editable: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    disabledOptionIds: Set<String> = emptySet(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selecting by remember(question.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("matching-option-sheet-${question.number}"),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Q${question.number} · 选择答案",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)
                    .testTag("matching-option-sheet-title-${question.number}"),
            )
            options.forEach { option ->
                val label = matchingOptionLabel(option)
                val chosen = selectedAnswer.equals(option.id, ignoreCase = true)
                val usedElsewhere = disabledOptionIds.any { it.equals(option.id, ignoreCase = true) }
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = chosen,
                            enabled = editable && !selecting && !usedElsewhere,
                            role = Role.RadioButton,
                            onClick = {
                                if (!editable || selecting || usedElsewhere) return@selectable
                                selecting = true
                                onSelect(option.id)
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                }
                            },
                        )
                        .semantics {
                            contentDescription = if (usedElsewhere) "$label，已被其他题使用" else label
                        }
                        .testTag("matching-option-${question.number}-${option.id}")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen, onClick = null,
                        enabled = editable && !selecting && !usedElsewhere)
                    Spacer(Modifier.width(12.dp))
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.size(1.dp))
        }
    }
}

internal fun matchingOptionLabel(option: Option): String {
    val text = option.text.trim()
    return if (text.isBlank() || text.equals(option.id, ignoreCase = true)) option.id else "${option.id} — $text"
}
