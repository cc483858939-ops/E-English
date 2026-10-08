package com.eenglish.listening

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eenglish.listening.navigation.ListeningApp
import com.eenglish.listening.ui.theme.ListeningTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ListeningTheme { ListeningApp() }
        }
    }
}
