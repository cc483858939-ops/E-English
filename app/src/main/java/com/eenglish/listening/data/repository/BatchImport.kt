package com.eenglish.listening.data.repository

import com.eenglish.listening.domain.model.BookIndex
import com.eenglish.listening.domain.model.PartSummary
import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ImportStage(val label: String) {
    READING("读取文件"), VALIDATING("验证资源"), SAVING("保存题库")
}

enum class ImportFailure(val label: String) {
    INVALID_PACK("不是有效的资料包"), DAMAGED("文件损坏或不完整"),
    CONFLICT("资料内容冲突，已保留现有内容"), STORAGE("存储空间不足"),
    READ("文件读取或保存失败")
}

class PackImportException(val reason: ImportFailure) : IllegalArgumentException(reason.label)
enum class ImportOutcome { INSTALLED, UPDATED, ALREADY_INSTALLED, FAILED }

data class PackImportResult(
    val fileName: String,
    val book: Int? = null,
    val outcome: ImportOutcome,
    val addedParts: Int = 0,
    val existingParts: Int = 0,
    val updatedLayoutParts: Int = 0,
    val failure: ImportFailure? = null,
)

data class BatchImportUiState(
    val total: Int,
    val currentFile: String? = null,
    val currentBook: Int? = null,
    val stage: ImportStage = ImportStage.READING,
    val results: List<PackImportResult> = emptyList(),
    val isRunning: Boolean = true,
) {
    val processed get() = results.size
    val succeeded get() = results.count {
        it.outcome == ImportOutcome.INSTALLED || it.outcome == ImportOutcome.UPDATED
    }
    val updated get() = results.count { it.outcome == ImportOutcome.UPDATED }
    val alreadyInstalled get() = results.count { it.outcome == ImportOutcome.ALREADY_INSTALLED }
    val failed get() = results.count { it.outcome == ImportOutcome.FAILED }
    val addedParts get() = results.sumOf { it.addedParts }
    val updatedLayoutParts get() = results.sumOf { it.updatedLayoutParts }
    val existingParts get() = results.sumOf { it.existingParts }
}

/** Opens only the current document. The caller retains URI access, not a file-system path. */
data class PackImportSource(val name: suspend () -> String, val open: suspend () -> InputStream)

/** One job, one stream at a time; successful books remain published when a later file fails. */
class BatchPackImporter(
    private val listParts: suspend () -> List<PartSummary>,
    private val install: suspend (InputStream, (ImportStage, Int?) -> Unit) -> BookIndex,
) {
    suspend fun run(
        sources: List<PackImportSource>,
        onState: (BatchImportUiState) -> Unit,
        onParts: (List<PartSummary>) -> Unit,
    ): BatchImportUiState = withContext(Dispatchers.IO) {
        var state = BatchImportUiState(sources.size)
        onState(state)
        sources.forEachIndexed { position, source ->
            currentCoroutineContext().ensureActive()
            val name = try { source.name() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "资料包 ${position + 1}" }
            state = state.copy(currentFile = name, currentBook = null, stage = ImportStage.READING)
            onState(state)
            val result = try {
                val before = listParts().associateBy { it.id }
                val index = source.open().use { input ->
                    install(input) { stage, book ->
                        state = state.copy(stage = stage, currentBook = book ?: state.currentBook)
                        onState(state)
                    }
                }
                val after = listParts()
                onParts(after)
                val afterById = after.associateBy { it.id }
                val ids = index.parts.map { it.id }.toSet()
                val added = ids.count { it !in before && it in afterById }
                val existing = ids.count { it in before }
                val updatedLayouts = index.parts.count { summary ->
                    val old = before[summary.id]
                    val current = afterById[summary.id]
                    old != null && current != null && old.layoutSha256 != current.layoutSha256
                }
                val outcome = when {
                    added > 0 -> ImportOutcome.INSTALLED
                    updatedLayouts > 0 -> ImportOutcome.UPDATED
                    else -> ImportOutcome.ALREADY_INSTALLED
                }
                PackImportResult(name, index.book,
                    outcome, added, existing, updatedLayouts)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                PackImportResult(name, state.currentBook, ImportOutcome.FAILED, failure = classifyFailure(error))
            }
            state = state.copy(results = state.results + result)
            onState(state)
        }
        state.copy(currentFile = null, currentBook = null, isRunning = false).also(onState)
    }
}

internal fun classifyFailure(error: Exception): ImportFailure {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<PackImportException>().firstOrNull()?.let { return it.reason }
    // Android wraps ENOSPC in IOException/ErrnoException. Do not expose exception messages in UI.
    if (causes.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space left on device") == true })
        return ImportFailure.STORAGE
    return when (error) {
        is ZipException, is java.io.EOFException -> ImportFailure.DAMAGED
        is kotlinx.serialization.SerializationException -> ImportFailure.INVALID_PACK
        is IllegalArgumentException -> ImportFailure.DAMAGED
        is IOException, is SecurityException -> ImportFailure.READ
        else -> ImportFailure.READ
    }
}
