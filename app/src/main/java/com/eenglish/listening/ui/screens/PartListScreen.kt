package com.eenglish.listening.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eenglish.listening.R
import com.eenglish.listening.ui.components.PageHeader

@Composable
fun PartListScreen(onPreviewPractice: () -> Unit) {
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PageHeader(stringResource(R.string.list_title))
            Text(stringResource(R.string.list_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.book_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.part_label))
                    Text(stringResource(R.string.question_count), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.not_imported), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = {}, enabled = false) { Text(stringResource(R.string.start_practice)) }
                }
            }
            Text(stringResource(R.string.import_pending), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onPreviewPractice) { Text(stringResource(R.string.preview_practice)) }
        }
    }
}
