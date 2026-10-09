package com.eenglish.listening

import android.os.Debug
import android.util.Base64
import android.util.Base64InputStream
import androidx.test.platform.app.InstrumentationRegistry
import com.eenglish.listening.data.repository.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional private inputs, pushed separately. Never published inside test APKs or CI artifacts. */
class BatchImportLargePackTest {
    @Test fun threeRealBooksStreamSeriallyIntoIsolatedLibrary() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Explicit opt-in for private large-pack smoke test",
            InstrumentationRegistry.getArguments().getString("listening.batchRealBooks") == "true")
        val files = listOf(15,20,21).map { File(context.filesDir,"full-library-packs/cambridge-$it.eelpack.b64") }
        assertTrue("Private packs must be supplied separately",files.all { it.isFile })
        val root = File(context.cacheDir,"batch-large-${UUID.randomUUID()}")
        val repository = PartRepository(context.assets,root)
        var observedPss = Debug.getPss()
        val startPss = observedPss
        val start = System.currentTimeMillis()
        try {
            val end = BatchPackImporter(repository::loadParts,repository::importPack).run(files.map { file ->
                PackImportSource({ file.name.removeSuffix(".b64") }, { Base64InputStream(file.inputStream(),Base64.DEFAULT) })
            }, { observedPss = maxOf(observedPss,Debug.getPss()) }, {})
            assertEquals(3,end.succeeded); assertEquals(0,end.failed)
            assertEquals(48,end.addedParts)
            val parts = repository.loadParts().filter { it.book in listOf(15,20,21) }
            assertEquals(48,parts.size)
            assertEquals(parts,PartRepository(context.assets,root).loadParts().filter { it.book in listOf(15,20,21) })
            val audioBytes = parts.sumOf { it.audioBytes }
            assertTrue(audioBytes > 100_000_000)
            val installedBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            instrumentation.sendStatus(2,android.os.Bundle().apply {
                putString("stream","BATCH_LARGE_VERIFIED 3 books, ${end.addedParts} new Parts; audioBytes=$audioBytes; installedBytes=$installedBytes; elapsedMs=${System.currentTimeMillis()-start}; startPssKb=$startPss; observedMaxPssKb=$observedPss\n")
            })
        } finally { root.deleteRecursively() }
    }
}
