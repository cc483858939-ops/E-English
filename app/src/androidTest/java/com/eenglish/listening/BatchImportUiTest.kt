package com.eenglish.listening

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eenglish.listening.data.repository.*
import com.eenglish.listening.domain.model.PartSummary
import com.eenglish.listening.ui.components.BatchImportDialog
import com.eenglish.listening.ui.screens.PartListScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BatchImportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backgroundQueueShowsProgressAndRefreshesListWithoutRestart() {
        val ui = mutableStateOf(PracticeUiState(loading=false))
        val working = CountDownLatch(1)
        val proceed = CompletableDeferred<Unit>()
        val installed = mutableListOf<PartSummary>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var importsStarted = 0
        compose.setContent { ListeningTheme {
            PartListScreen(ui.value,onImport = { importsStarted++ },onOpen = {})
            ui.value.batchImport?.let { BatchImportDialog(it) { ui.value = ui.value.copy(batchImport=null,saving=false) } }
        } }
        compose.onNodeWithTag("import-packs").performClick()
        assertEquals(1,importsStarted)
        compose.runOnIdle { ui.value = ui.value.copy(saving=true,batchImport=BatchImportUiState(3)) }
        val job = scope.launch {
            BatchPackImporter({ installed.toList() }, { input, progress ->
                val book = input.read()
                progress(ImportStage.VALIDATING,book)
                if (book == 15) { working.countDown(); proceed.await() }
                progress(ImportStage.SAVING,book)
                val item = PartSummary("cambridge-$book-test-1-part-1",book,1,1,"Synthetic",10,1,10)
                installed += item
                com.eenglish.listening.domain.model.BookIndex(1,book,listOf(item))
            }).run(listOf(14,15,16).map { book -> PackImportSource({ "synthetic-$book.eelpack" },
                { ByteArrayInputStream(byteArrayOf(book.toByte())) }) },
                { batch -> compose.runOnUiThread { ui.value = ui.value.copy(batchImport=batch) } },
                { parts -> compose.runOnUiThread { ui.value = ui.value.copy(parts=parts) } })
        }
        try {
            assertTrue(working.await(10,TimeUnit.SECONDS))
            compose.onNodeWithText("选择：3 个资料包 · 已处理 1 / 3").assertIsDisplayed()
            compose.onNodeWithText("synthetic-15.eelpack",substring=true).assertIsDisplayed()
            compose.onNodeWithTag("batch-done").assertDoesNotExist()
            // A frame and semantics query complete while the IO queue waits, proving the UI stays responsive.
            compose.runOnIdle { assertTrue(ui.value.saving); assertEquals(1,importsStarted) }
            proceed.complete(Unit)
            compose.waitUntil(10000) { ui.value.batchImport?.isRunning == false }
            compose.onNodeWithText("导入完成").assertIsDisplayed()
            compose.onNodeWithText("成功：3 · 已存在：0 · 失败：0").assertIsDisplayed()
            compose.onNodeWithTag("batch-done").performClick()
            compose.onNodeWithTag("book-16").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(3,ui.value.parts.size) }
        } finally { proceed.complete(Unit); job.cancel(); scope.cancel() }
    }

    @Test fun summaryDisplaysFailuresAndExistingPartsAndDoneDismisses() {
        val batch = mutableStateOf<BatchImportUiState?>(BatchImportUiState(3,results=listOf(
            PackImportResult("first.eelpack",14,ImportOutcome.INSTALLED,addedParts=16),
            PackImportResult("duplicate.eelpack",15,ImportOutcome.ALREADY_INSTALLED,existingParts=16),
            PackImportResult("broken.eelpack",outcome=ImportOutcome.FAILED,failure=ImportFailure.DAMAGED),
        ),isRunning=false))
        compose.setContent { ListeningTheme { batch.value?.let { BatchImportDialog(it) { batch.value = null } } } }
        compose.onNodeWithText("成功：1 · 已存在：1 · 失败：1").assertIsDisplayed()
        compose.onNodeWithText("新增 Part：16 · 已存在 Part：16").assertIsDisplayed()
        compose.onNodeWithTag("batch-import").performScrollToNode(hasText("文件损坏或不完整"))
        compose.onNodeWithText("文件损坏或不完整").assertIsDisplayed()
        compose.onNodeWithTag("batch-done").performClick()
        compose.onNodeWithTag("batch-import").assertDoesNotExist()
    }
}
