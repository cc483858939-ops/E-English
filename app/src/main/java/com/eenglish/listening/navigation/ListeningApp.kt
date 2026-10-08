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

@Composable
fun ListeningApp(shellViewModel: ShellViewModel = viewModel()) {
    val navController = rememberNavController()
    val state by shellViewModel.uiState.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = AppDestination.LIST.route) {
        composable(AppDestination.LIST.route) {
            PartListScreen(onPreviewPractice = {
                navController.navigate(AppDestination.PRACTICE.route) { launchSingleTop = true }
            })
        }
        composable(AppDestination.PRACTICE.route) {
            PracticeScreen(
                onBack = { navController.popBackStack() },
                onViewTranscript = {
                    navController.navigate(AppDestination.TRANSCRIPT.route) { launchSingleTop = true }
                },
            )
        }
        composable(AppDestination.TRANSCRIPT.route) {
            TranscriptScreen(
                mode = state.transcriptMode,
                onModeChange = shellViewModel::selectTranscriptMode,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
