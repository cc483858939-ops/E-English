package com.eenglish.listening

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.eenglish.listening.viewmodel.PracticeViewModel
import com.eenglish.listening.navigation.ListeningApp
import com.eenglish.listening.ui.theme.ListeningTheme

class MainActivity : ComponentActivity() {
    private val practiceViewModel: PracticeViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ListeningTheme { ListeningApp(practiceViewModel = practiceViewModel) }
        }
    }
    override fun onStop() {
        practiceViewModel.audio.pause()
        super.onStop()
    }
}
