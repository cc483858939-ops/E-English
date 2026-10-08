package com.eenglish.listening

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.AnnotationRepository
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.ui.theme.ListeningTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Also run with annotationPhase=write/read around an external adb force-stop. */
class AnnotationProcessTest {
    @get:Rule val compose = createAndroidComposeRule<AnnotationTestActivity>()
    @Test fun nativeUiRestoresPersistedHighlight() {
        val phase = InstrumentationRegistry.getArguments().getString("annotationPhase", "roundtrip")
        val context = compose.activity.applicationContext
        val file = "annotation-process-test.db" // Dedicated disposable test file, never practice.db.
        if (phase != "read") context.deleteDatabase(file)
        fun open() = Room.databaseBuilder(context, PracticeDatabase::class.java, file)
            .addMigrations(PracticeDatabase.MIGRATION_1_2).build()
        var db = open()
        var repository by mutableStateOf(AnnotationRepository(db))
        var visible by mutableStateOf(true)
        val part = annotationFixture()
        val document = AnnotationDocument.transcript(part.transcript, "en")
        compose.setContent {
            ListeningTheme {
                if (visible) {
                    val highlights by repository.observeAll().collectAsState(initial = emptyList())
                    val scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalAnnotations provides AnnotationContext(part.id, highlights,
                        { changes, add -> scope.launch { repository.change(changes, add) } })) {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                            Text("Process recovery fixture")
                            AnnotatableText(document, viewTag = "process-transcript")
                        }
                    }
                }
            }
        }
        try {
            if (phase != "read") {
                textAt("process-transcript").perform(pressWord(17))
                selectionMenu("高亮")
                compose.waitUntil(10000) { runBlocking(Dispatchers.IO) { repository.all().size == 1 } }
            }
            if (phase == "roundtrip") {
                compose.runOnIdle { visible = false }
                compose.waitForIdle()
                db.close(); db = open()
                compose.runOnIdle { repository = AnnotationRepository(db); visible = true }
            }
            compose.waitUntil(10000) { runBlocking(Dispatchers.IO) { repository.all().size == 1 } }
            compose.waitForIdle()
            compose.runOnIdle {
                val text = nativeText(compose.activity, "process-transcript").text as Spanned
                val spans = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
                assertEquals(1, spans.size)
                assertEquals(15, text.getSpanStart(spans.single()))
                assertEquals(21, text.getSpanEnd(spans.single()))
                assertEquals(part.transcript.english, text.toString())
                visible = false
            }
            compose.waitForIdle()
        } finally { db.close() }
    }
}
