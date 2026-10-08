package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eenglish.listening.R
import com.eenglish.listening.ui.components.AudioPlaceholder
import com.eenglish.listening.ui.components.PageHeader

@Composable
fun PracticeScreen(onBack: () -> Unit, onViewTranscript: () -> Unit) {
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PageHeader(stringResource(R.string.practice_title), onBack)
            Text(stringResource(R.string.book_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.part_label))
            AudioPlaceholder()
            TextButton(onClick = onViewTranscript) { Text(stringResource(R.string.view_transcript)) }
            Text(stringResource(R.string.practice_pending), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.practice_pending_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.submit_answers))
            }
        }
    }
}
