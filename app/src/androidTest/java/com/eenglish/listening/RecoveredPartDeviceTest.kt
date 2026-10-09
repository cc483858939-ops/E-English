package com.eenglish.listening

import android.util.Base64
import android.util.Base64InputStream
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.audio.ListeningAudioController
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.*
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.grading.Grader
import com.eenglish.listening.domain.model.*
import com.eenglish.listening.ui.components.*
import com.eenglish.listening.ui.screens.PartListScreen
import com.eenglish.listening.ui.theme.ListeningTheme
import com.eenglish.listening.viewmodel.PracticeUiState
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in private fixtures; only the six restored recordings are played. */
class RecoveredPartDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun sixPartsAnswerGradeRenderPlayAndIncrementallyInstallWithoutChangingOldData() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit private recovery fixtures required",arguments.getString("listening.recoveredParts")=="true")
        val books=listOf(6,9,11)
        val pending=File(context.filesDir,"pending-six-packs")
        val base=File(context.filesDir,"full-library-packs")
        assertTrue(books.all { File(base,"cambridge-$it.eelpack.b64").isFile && File(pending,"cambridge-$it.eelpack.b64").isFile })
        val root=File(context.cacheDir,"recovered-${UUID.randomUUID()}")
        val repo=PartRepository(context.assets,root)
        val name="recovered-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,PracticeDatabase::class.java,name).build()
        var practice=PracticeRepository(db)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        lateinit var audio: ListeningAudioController
        instrumentation.runOnMainSync { audio=ListeningAudioController(context,scope) }
        val displayPart=mutableStateOf<ListeningPart?>(null)
        val question=mutableStateOf<Question?>(null)
        val value=mutableStateOf("")
        val session=mutableStateOf<String?>(null)
        val listState=mutableStateOf<PracticeUiState?>(null)
        val writes=Channel<Triple<String,String,String>>(Channel.UNLIMITED)
        val writer=scope.launch { for ((s,q,v) in writes) practice.saveAnswer(s,q,v) }
        fun hash(raw: ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        fun jsonHashes()=root.walkTopDown().filter { it.name=="part.json" }.associate {
            requireNotNull(it.parentFile).name to hash(it.readBytes()) }
        suspend fun install(directory: File) {
            books.forEach { book -> Base64InputStream(File(directory,"cambridge-$book.eelpack.b64").inputStream(),Base64.DEFAULT)
                .use { repo.importPack(it) } }
        }
        compose.setContent { ListeningTheme {
            val current=displayPart.value
            val loader:suspend (String)->android.graphics.Bitmap?=remember(current?.id) {
                { path -> current?.let { repo.readImage(it.id,path) } }
            }
            CompositionLocalProvider(LocalQuestionImages provides loader,LocalAnswerSession provides session.value) {
                listState.value?.let { PartListScreen(it,onOpen={}) } ?: Column(Modifier.verticalScroll(rememberScrollState())) {
                    question.value?.let { q -> QuestionCard(q,value.value,true) {
                        value.value=it; writes.trySend(Triple(requireNotNull(session.value),q.id,it))
                    } }
                }
            }
        } }
        try {
            install(base)
            val before=repo.loadParts()
            assertEquals(42,before.size)
            val frozen=jsonHashes()
            val oldPart=repo.loadPart("cambridge-9-test-1-part-3")
            val oldSession=practice.resumeOrCreate(oldPart)
            practice.saveAnswer(oldSession,oldPart.questions.first().id,oldPart.questions.first().correctAnswer)
            practice.submit(oldSession)
            AnnotationRepository(db).change(AnnotationDocument.transcript(oldPart.transcript,"en").selection(oldPart.id,0,3),true)
            val oldHistory=practice.all(); val oldHighlights=AnnotationRepository(db).all()
            val batch=BatchPackImporter(repo::loadParts,repo::importPack).run(books.map { book ->
                PackImportSource({ "Cambridge $book" }, { Base64InputStream(File(pending,"cambridge-$book.eelpack.b64").inputStream(),Base64.DEFAULT) })
            }, {}, { listState.value=PracticeUiState(loading=false,parts=it) })
            assertEquals(3,batch.succeeded); assertEquals(6,batch.addedParts); assertEquals(42,batch.existingParts)
            val after=repo.loadParts(); assertEquals(48,after.size)
            frozen.forEach { (id,digest) -> assertEquals("Old JSON changed: $id",digest,jsonHashes()[id]) }
            compose.onNodeWithTag("book-11").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("library-search").performScrollTo().performTextInput("cambridge-11-test-1-part-2")
            compose.onNodeWithTag("open-cambridge-11-test-1-part-2").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { listState.value=null }
            val added=after.filter { a -> before.none { it.id==a.id } }
            assertEquals(6,added.size)
            for (summary in added) {
                val part=repo.loadPart(summary.id)
                assertEquals(10,part.questions.size)
                assertEquals(((part.part-1)*10+1..part.part*10).toList(),part.questions.map { it.number })
                assertTrue(part.transcript.english.isNotBlank() && part.transcript.chinese.isNotBlank())
                part.questions.flatMap { it.images }.distinct().forEach { assertNotNull(repo.readImage(part.id,it)) }
                val id=practice.resumeOrCreate(part)
                val target=part.questions.firstOrNull { it.answerSeparator!=null } ?: part.questions.first { it.number==15 }
                compose.runOnIdle { displayPart.value=part; question.value=target; session.value=id; value.value="" }
                compose.onNodeWithTag("result-${target.number}").assertDoesNotExist()
                if (target.answerSeparator!=null) {
                    val typed=Grader.answerParts(target,target.correctAnswer)
                    compose.onNodeWithTag("input-${target.number}-1").performScrollTo().performTextInput(typed[0])
                    compose.onNodeWithTag("input-${target.number}-2").performScrollTo().performTextInput(typed[1])
                    Espresso.closeSoftKeyboard()
                } else {
                    compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("题目图片，点击放大").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription("题目图片，点击放大").performScrollTo().assertIsDisplayed()
                    assertEquals(('A'..'I').map(Char::toString),target.options.map { it.id })
                    target.options.forEach { compose.onNodeWithTag("option-${target.number}-${it.id}").performScrollTo().assertIsDisplayed() }
                    compose.onNodeWithTag("option-${target.number}-${target.correctAnswer}").performScrollTo().performClick()
                }
                withTimeout(10000) { while(practice.all().first { it.session.id==id }.selectedAnswers[target.id]!=target.correctAnswer) delay(30) }
                part.questions.filter { it.id!=target.id }.forEach { q ->
                    val correct=if(q.type==QuestionType.MULTIPLE_CHOICE) part.questions.filter { it.groupId==q.groupId }
                        .map { it.correctAnswer }.sorted().joinToString(",") else q.correctAnswer
                    practice.saveAnswer(id,q.id,correct)
                }
                assertEquals(10,practice.submit(id).correctCount)
                try { practice.saveAnswer(id,target.id,"modified");fail("Submitted attempt changed") } catch (_: IllegalStateException) { }
                instrumentation.runOnMainSync { audio.load(repo.audioPath(part)) }
                withTimeout(15000) { while(!audio.state.value.ready) { check(audio.state.value.error==null);delay(50) } }
                assertTrue(audio.state.value.durationMs>1000)
                instrumentation.runOnMainSync { audio.toggle() }
                withTimeout(10000) { while(!audio.state.value.isPlaying || audio.state.value.positionMs<300) delay(50) }
                instrumentation.runOnMainSync { audio.pause();audio.seekTo(1000) }
                withTimeout(5000) { while(audio.state.value.positionMs !in 900..1100) delay(50) }
            }
            val allHistory=practice.all()
            assertEquals(oldHistory.single(),allHistory.single { it.session.id==oldSession })
            assertEquals(oldHighlights,AnnotationRepository(db).all())
            db.close();db=Room.databaseBuilder(context,PracticeDatabase::class.java,name).build();practice=PracticeRepository(db)
            assertEquals(allHistory,practice.all());assertEquals(oldHighlights,AnnotationRepository(db).all())
            install(pending);assertEquals(after,PartRepository(context.assets,root).loadParts())
            if (arguments.getString("listening.installRecoveredParts")=="true") {
                val production=PartRepository(context.assets,File(context.filesDir,"library"))
                val installed=production.loadParts(); assertEquals(266,installed.size)
                books.forEach { book -> Base64InputStream(File(pending,"cambridge-$book.eelpack.b64").inputStream(),Base64.DEFAULT)
                    .use { production.importPack(it) } }
                val full=production.loadParts();assertEquals(272,full.size);assertEquals(2720,full.sumOf { it.questionCount })
                assertTrue(installed.all { old -> full.single { it.id==old.id }==old })
            }
            instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","RECOVERED_SIX_VERIFIED 6 Parts / 60 questions; 42 old -> 48; old JSON and Room records preserved\n") })
        } finally {
            writer.cancel();writes.close();instrumentation.runOnMainSync { audio.release() };scope.cancel()
            db.close();context.deleteDatabase(name);root.deleteRecursively()
        }
    }
}
