package com.eenglish.listening.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.eenglish.listening.ui.components.AnnotationContext
import com.eenglish.listening.ui.components.LocalAnnotations
import com.eenglish.listening.viewmodel.AnnotationViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.eenglish.listening.ui.screens.PartListScreen
import com.eenglish.listening.ui.screens.PracticeScreen
import com.eenglish.listening.ui.screens.TranscriptScreen
import com.eenglish.listening.viewmodel.ShellViewModel
import com.eenglish.listening.viewmodel.PracticeViewModel
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import com.eenglish.listening.ListeningApplication
import com.eenglish.listening.ui.components.LocalAnswerSession
import com.eenglish.listening.ui.components.LocalAnswerSaving
import com.eenglish.listening.ui.components.LocalQuestionImages
import com.eenglish.listening.ui.components.BatchImportDialog

@Composable
fun ListeningApp(shellViewModel: ShellViewModel = viewModel(), practiceViewModel: PracticeViewModel = viewModel()) {
    val annotationViewModel: AnnotationViewModel = viewModel()
    val highlights by annotationViewModel.highlights.collectAsStateWithLifecycle()
    val annotationError by annotationViewModel.error.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val state by shellViewModel.uiState.collectAsStateWithLifecycle()
    val practice by practiceViewModel.uiState.collectAsStateWithLifecycle()
    val audio by practiceViewModel.audio.state.collectAsStateWithLifecycle()
    val application = LocalContext.current.applicationContext as ListeningApplication
    val imageLoader: suspend (String) -> android.graphics.Bitmap? = remember(application, practice.part?.id) {
        { path -> practice.part?.id?.let { id ->
            try { application.parts.readImage(id, path) } catch (_: java.io.IOException) { null }
        } }
    }
    val importLauncher = rememberLauncherForActivityResult(OfflinePackDocuments()) { uris ->
        practiceViewModel.importPacks(uris)
    }

    CompositionLocalProvider(LocalAnnotations provides AnnotationContext(practice.part?.id, highlights, annotationViewModel::change),
        LocalAnswerSession provides practice.attempt?.session?.id, LocalAnswerSaving provides practice.saving, LocalQuestionImages provides imageLoader) {
    annotationError?.let { message ->
        AlertDialog(onDismissRequest = annotationViewModel::dismissError, text = { Text(message) },
            confirmButton = { TextButton(onClick = annotationViewModel::dismissError) { Text("知道了") } })
    }
    practice.batchImport?.let { BatchImportDialog(it, practiceViewModel::dismissBatchImport) }
    NavHost(navController = navController, startDestination = AppDestination.LIST.route) {
        composable(AppDestination.LIST.route) {
            PartListScreen(practice, onImport = { importLauncher.launch(arrayOf("*/*")) }, onOpen = { id ->
                practiceViewModel.openPart(id)
                navController.navigate(AppDestination.PRACTICE.route) { launchSingleTop = true }
            })
        }
        composable(AppDestination.PRACTICE.route) {
            PracticeScreen(
                state = practice,
                audio = audio,
                onToggle = practiceViewModel.audio::toggle,
                onSeek = practiceViewModel.audio::seekTo,
                onSelect = practiceViewModel::selectAnswer,
                onSubmit = practiceViewModel::requestSubmit,
                onConfirm = practiceViewModel::confirmSubmit,
                onDismiss = practiceViewModel::dismissSubmit,
                onNew = practiceViewModel::newPractice,
                onHistory = practiceViewModel::viewHistory,
                onBack = { navController.popBackStack() },
                onViewTranscript = {
                    navController.navigate(AppDestination.TRANSCRIPT.route) { launchSingleTop = true }
                },
            )
        }
        composable(AppDestination.TRANSCRIPT.route) {
            TranscriptScreen(
                state = practice,
                audio = audio,
                onToggle = practiceViewModel.audio::toggle,
                onSeek = practiceViewModel.audio::seekTo,
                mode = state.transcriptMode,
                onModeChange = shellViewModel::selectTranscriptMode,
                onSelect = practiceViewModel::selectAnswer,
                onBack = { navController.popBackStack() },
            )
        }
    }
    }
}
