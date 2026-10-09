package com.eenglish.listening

import com.eenglish.listening.data.repository.*
import com.eenglish.listening.domain.model.*
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchImportTest {
    private fun summary(book: Int) = PartSummary("cambridge-$book-test-1-part-1", book, 1, 1, "Synthetic", 10, 1, 10)

    @Test fun serialQueueClosesEachStreamAndCountsActualNewAndExistingParts() = runBlocking {
        val parts = mutableListOf<PartSummary>()
        val states = mutableListOf<BatchImportUiState>()
        var openStreams = 0
        var closed = 0
        val sources = listOf(14,15,16,14).map { book -> PackImportSource({ "$book.eelpack" }, {
            assertEquals(0, openStreams)
            openStreams++
            object : ByteArrayInputStream(byteArrayOf(book.toByte())) {
                override fun close() { openStreams--; closed++; super.close() }
            }
        }) }
        val runner = BatchPackImporter({ parts.toList() }, { input, progress ->
            val book = input.read()
            progress(ImportStage.VALIDATING, book)
            progress(ImportStage.SAVING, book)
            val item = summary(book)
            if (parts.none { it.id == item.id }) parts += item
            BookIndex(1,book,listOf(item))
        })
        var refreshed = 0
        val end = runner.run(sources, states::add) { refreshed++ }
        assertEquals(4, closed)
        assertEquals(0, openStreams)
        assertEquals(4, refreshed)
        assertEquals(3, end.succeeded)
        assertEquals(1, end.alreadyInstalled)
        assertEquals(3, end.addedParts)
        assertEquals(1, end.existingParts)
        assertEquals(0, end.failed)
        assertFalse(end.isRunning)
        assertTrue(states.any { it.processed == 1 && it.currentFile == "15.eelpack" && it.isRunning })
        assertTrue(states.any { it.stage == ImportStage.SAVING && it.currentBook == 16 })
        val duplicate = runner.run(sources.take(3), {}, {})
        assertEquals(0, duplicate.addedParts)
        assertEquals(3, duplicate.alreadyInstalled)
        assertEquals(3, parts.size)
    }

    @Test fun readAndValidationFailuresDoNotPreventLaterSuccessfulFile() = runBlocking {
        val parts = mutableListOf<PartSummary>()
        val runner = BatchPackImporter({ parts.toList() }, { input, _ ->
            val book = input.read()
            if (book == 0) throw PackImportException(ImportFailure.DAMAGED)
            summary(book).also(parts::add).let { BookIndex(1, book, listOf(it)) }
        })
        val end = runner.run(listOf(
            PackImportSource({ "unreadable" }, { throw IOException("private path") }),
            PackImportSource({ "broken" }, { ByteArrayInputStream(byteArrayOf(0)) }),
            PackImportSource({ "valid" }, { ByteArrayInputStream(byteArrayOf(14)) }),
        ), {}, {})
        assertEquals(2, end.failed)
        assertEquals(1, end.succeeded)
        assertEquals(listOf(ImportFailure.READ, ImportFailure.DAMAGED), end.results.take(2).map { it.failure })
        assertEquals(listOf(summary(14)), parts)
    }

    @Test fun cancellationIsNotReportedAsFileFailureOrContinued() = runBlocking {
        val runner = BatchPackImporter({ emptyList() }, { _, _ -> error("Not reached") })
        var nextOpened = false
        try {
            runner.run(listOf(PackImportSource({ "cancelled" }, { throw CancellationException() }),
                PackImportSource({ "next" }, { nextOpened = true; ByteArrayInputStream(byteArrayOf()) })), {}, {})
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertFalse(nextOpened)
    }
}
