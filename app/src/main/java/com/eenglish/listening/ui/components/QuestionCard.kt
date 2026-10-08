package com.eenglish.listening.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eenglish.listening.domain.model.Question

@Composable
fun QuestionCard(question: Question, selected: String?, editable: Boolean, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).selectableGroup()) {
        Text("Q.${question.number}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("question-${question.number}"))
        Text(question.prompt, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
        question.options.forEach { option ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().testTag("option-${question.number}-${option.id}")
                .selectable(selected == option.id, enabled = editable, role = Role.RadioButton, onClick = { onSelect(option.id) })
                .padding(vertical = 12.dp)) {
                RadioButton(selected = selected == option.id, onClick = null, enabled = editable)
                Text("${option.id}. ${option.text}", Modifier.padding(top = 12.dp).weight(1f))
            }
        }
    }
}
