package com.eenglish.listening.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun ListeningApp(shellViewModel: ShellViewModel = viewModel(), practiceViewModel: PracticeViewModel = viewModel()) {
    val navController = rememberNavController()
    val state by shellViewModel.uiState.collectAsStateWithLifecycle()
    val practice by practiceViewModel.uiState.collectAsStateWithLifecycle()
    val audio by practiceViewModel.audio.state.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = AppDestination.LIST.route) {
        composable(AppDestination.LIST.route) {
            PartListScreen(practice, onOpen = { part ->
                practiceViewModel.openPart(part)
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
                part = practice.part,
                audio = audio,
                onToggle = practiceViewModel.audio::toggle,
                onSeek = practiceViewModel.audio::seekTo,
                mode = state.transcriptMode,
                onModeChange = shellViewModel::selectTranscriptMode,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
