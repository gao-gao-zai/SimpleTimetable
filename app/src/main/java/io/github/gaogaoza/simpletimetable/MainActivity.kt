package io.github.gaogaoza.simpletimetable

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gaogaoza.simpletimetable.ui.TimetableApp

class MainActivity : ComponentActivity() {
    private var launchIntent by mutableStateOf(Intent())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        launchIntent = intent
        setContent { TimetableApp(application as TimetableApplication, launchIntent) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); launchIntent = Intent(intent) }
}
