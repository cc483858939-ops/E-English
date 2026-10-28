package com.eenglish.listening.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.rememberLazyListState
import com.eenglish.listening.ui.screens.rememberTranscriptPageState
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.navigation.compose.currentBackStackEntryAsState
import android.app.Activity
import com.eenglish.listening.ListeningApplication
import com.eenglish.listening.ui.components.LocalAnswerSession
import com.eenglish.listening.ui.components.LocalAnswerSaving
import com.eenglish.listening.ui.components.LocalQuestionImages
import com.eenglish.listening.ui.components.BatchImportDialog
import com.eenglish.listening.ui.screens.MeScreen
import com.eenglish.listening.ui.screens.PracticeHistoryScreen
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

@Composable
fun ListeningApp(shellViewModel: ShellViewModel = viewModel(), practiceViewModel: PracticeViewModel = viewModel()) {
    val annotationViewModel: AnnotationViewModel = viewModel()
    val highlights by annotationViewModel.highlights.collectAsStateWithLifecycle()
    val annotationError by annotationViewModel.error.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val activity = LocalContext.current as? Activity
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(currentRoute) {
        // The Activity remains started when navigation returns to the library.
        if (currentRoute != null && currentRoute !in setOf(AppDestination.PRACTICE.route, AppDestination.TRANSCRIPT.route))
            practiceViewModel.audio.pause()
    }
    val navigateUp: () -> Unit = {
        practiceViewModel.flushInlineAnswers()
        if (practiceViewModel.uiState.value.batchImport?.isRunning != true) {
            val targetRoute = navController.previousBackStackEntry?.destination?.route
            // Pause before popping; both toolbar and system Back use this path.
            // Practice and transcript share one playback region.
            if (targetRoute != AppDestination.PRACTICE.route && targetRoute != AppDestination.TRANSCRIPT.route) {
                practiceViewModel.audio.pause()
            }
            if (!navController.popBackStack()) {
                navController.navigate(AppDestination.LIST.route) { launchSingleTop = true }
            }
        }
    }
    val state by shellViewModel.uiState.collectAsStateWithLifecycle()
    val practice by practiceViewModel.uiState.collectAsStateWithLifecycle()
    val audio by practiceViewModel.audio.state.collectAsStateWithLifecycle()
    // Page state outlives individual navigation entries, including a popped transcript.
    val practiceListState = key(practice.part?.id, practice.attempt?.session?.id, practice.submitted) {
        rememberLazyListState()
    }
    val transcriptPageState = key(practice.part?.id, practice.attempt?.session?.id) {
        rememberTranscriptPageState(practice.questions.firstOrNull()?.number ?: 0)
    }
    val application = LocalContext.current.applicationContext as ListeningApplication
    val imageLoader: suspend (String) -> android.graphics.Bitmap? = remember(application, practice.part?.id) {
        { path -> practice.part?.id?.let { id ->
            try { application.parts.readImage(id, path) } catch (_: java.io.IOException) { null }
        } }
    }
    val importLauncher = rememberLauncherForActivityResult(OfflinePackDocuments()) { uris ->
        practiceViewModel.importPacks(uris)
    }
    val selectTab: (AppDestination) -> Unit = { destination ->
        if (navController.currentDestination?.route != destination.route) {
            navController.navigate(destination.route) {
                popUpTo(AppDestination.LIST.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
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
            PartListScreen(practice, onImport = { importLauncher.launch(arrayOf("*/*")) },
                bottomBar = { MainTabs(AppDestination.LIST, selectTab) },
                backEnabled = currentRoute == AppDestination.LIST.route, onExit = {
                    practiceViewModel.audio.pause()
                    activity?.finish()
                }, onOpen = { id ->
                practiceViewModel.openPart(id)
                navController.navigate(AppDestination.PRACTICE.route) { launchSingleTop = true }
            })
        }
        composable(AppDestination.ME.route) {
            BackHandler(enabled = currentRoute == AppDestination.ME.route) { selectTab(AppDestination.LIST) }
            MeScreen(onHistory = { navController.navigate(AppDestination.HISTORY.route) { launchSingleTop = true } },
                bottomBar = { MainTabs(AppDestination.ME, selectTab) })
        }
        composable(AppDestination.HISTORY.route) {
            BackHandler(enabled = currentRoute == AppDestination.HISTORY.route, onBack = navigateUp)
            PracticeHistoryScreen(practice, onBack = navigateUp, onOpen = { id ->
                practiceViewModel.openHistory(id) {
                    // A slow load must not navigate after the user has left this list.
                    if (navController.currentDestination?.route == AppDestination.HISTORY.route)
                        navController.navigate(AppDestination.PRACTICE.route) { launchSingleTop = true }
                }
            })
        }
        composable(AppDestination.PRACTICE.route) {
            BackHandler(enabled = currentRoute == AppDestination.PRACTICE.route && !imeVisible, onBack = navigateUp)
            PracticeScreen(
                state = practice,
                listState = practiceListState,
                audio = audio,
                onToggle = practiceViewModel.audio::toggle,
                onSeek = practiceViewModel.audio::seekTo,
                onSeekBy = practiceViewModel.audio::seekBy,
                onSpeed = practiceViewModel.audio::setSpeed,
                onSelect = practiceViewModel::selectAnswer,
                onInlineEdit = practiceViewModel::editInlineAnswer,
                onInlineCommit = practiceViewModel::flushInlineAnswer,
                onFlushInline = practiceViewModel::flushInlineAnswers,
                onSubmit = practiceViewModel::requestSubmit,
                onConfirm = practiceViewModel::confirmSubmit,
                onDismiss = practiceViewModel::dismissSubmit,
                onNew = practiceViewModel::newPractice,
                onHistory = practiceViewModel::viewHistory,
                onBack = navigateUp,
                onReviewTranscript = { transcriptPageState.questionNumber.intValue = it },
                onViewTranscript = {
                    if (practice.submitted) transcriptPageState.reviewAnswers.value = false
                    navController.navigate(AppDestination.TRANSCRIPT.route) { launchSingleTop = true }
                },
            )
        }
        composable(AppDestination.TRANSCRIPT.route) {
            BackHandler(enabled = currentRoute == AppDestination.TRANSCRIPT.route && !imeVisible, onBack = navigateUp)
            TranscriptScreen(
                state = practice,
                pageState = transcriptPageState,
                audio = audio,
                onToggle = practiceViewModel.audio::toggle,
                onSeek = practiceViewModel.audio::seekTo,
                onSeekBy = practiceViewModel.audio::seekBy,
                onSpeed = practiceViewModel.audio::setSpeed,
                mode = state.transcriptMode,
                onModeChange = shellViewModel::selectTranscriptMode,
                onSubmit = { navigateUp(); practiceViewModel.requestSubmit() },
                onSelect = practiceViewModel::selectAnswer,
                onInlineEdit = practiceViewModel::editInlineAnswer,
                onInlineCommit = practiceViewModel::flushInlineAnswer,
                onFlushInline = practiceViewModel::flushInlineAnswers,
                onBack = navigateUp,
            )
        }
    }
    }
}

@Composable
private fun MainTabs(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationBar(Modifier.testTag("main-tabs")) {
        listOf(AppDestination.LIST to "练习", AppDestination.ME to "我").forEach { (destination, label) ->
            NavigationBarItem(selected = selected == destination, onClick = { onSelect(destination) },
                modifier = Modifier.testTag(if (destination == AppDestination.LIST) "tab-practice" else "tab-me"),
                label = { Text(label) }, icon = {
                    val color = MaterialTheme.colorScheme.onSurface
                    Canvas(Modifier.size(24.dp)) {
                        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                            if (destination == AppDestination.LIST) drawPath(Path().apply {
                                moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
                                lineTo(21f, 21f); lineTo(15f, 21f); lineTo(15f, 14f)
                                lineTo(9f, 14f); lineTo(9f, 21f); lineTo(3f, 21f); close()
                            }, color, style = Stroke(1.7f)) else {
                                drawCircle(color, 4f, Offset(12f, 7f), style = Stroke(1.7f))
                                drawArc(color, 180f, 180f, false, Offset(3f, 14f), Size(18f, 14f), style = Stroke(1.7f))
                            }
                        }
                    }
                })
        }
    }
}
