package com.eenglish.listening.data.repository

import android.util.AtomicFile
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.BookIndex
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun InputStream.sha256(): String = use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32768)
    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/** Immutable book directories + one atomic catalogue pointer; no practice DB writes. */
class OfflinePackImporter(private val root: File) {
    private val mutex = Mutex()
    private val json = Json
    private val catalog get() = AtomicFile(File(root, "catalog.json"))

    fun installed(): Map<Int, String> {
        if (!catalog.baseFile.exists() && !File(root, "catalog.json.bak").exists()) return emptyMap()
        return json.decodeFromString<Map<Int, String>>(catalog.readFully().toString(Charsets.UTF_8)).also { entries ->
            require(entries.all { (book, folder) -> book in 5..21 && folder.matches(Regex("book-$book-[a-f0-9]{64}")) })
        }
    }

    suspend fun install(input: InputStream, protectedSample: String? = null,
        onProgress: (ImportStage, Int?) -> Unit = { _, _ -> }): BookIndex = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.mkdirs()
            val staging = File(root, "import-${UUID.randomUUID()}").apply { check(mkdir()) }
            try {
                var total = 0L
                val names = mutableSetOf<String>()
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        require(names.add(name) && names.size <= 256) { "Duplicate/too many pack entries" }
                        require(name == "index.json" || name.matches(Regex("listening/cambridge-\\d+-test-[1-4]-part-[1-4]/(?:part\\.json|audio\\.mp3|[a-zA-Z0-9_-]+\\.(?:png|jpg|jpeg|webp))"))) { "Unsafe pack path" }
                        require(!entry.isDirectory)
                        val file = File(staging, name)
                        require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                        file.parentFile?.mkdirs()
                        var size = 0L
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                val count = zip.read(buffer); if (count < 0) break
                                size += count; total += count
                                require(size <= if (name.endsWith(".json")) 4_000_000 else 128_000_000)
                                require(total <= 512_000_000) { "Offline pack exceeds size limit" }
                                if (root.usableSpace <= 32_000_000) throw PackImportException(ImportFailure.STORAGE)
                                output.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                onProgress(ImportStage.VALIDATING, null)
                val indexFile = File(staging, "index.json")
                if (!indexFile.isFile) throw PackImportException(
                    if (names.isEmpty()) ImportFailure.INVALID_PACK else ImportFailure.DAMAGED)
                val index = json.decodeFromString<BookIndex>(indexFile.readText()).validate()
                onProgress(ImportStage.VALIDATING, index.book)
                val expectedNames = mutableSetOf("index.json")
                index.parts.forEach { summary ->
                    val directory = File(staging, "listening/${summary.id}")
                    val metadata = File(directory, "part.json")
                    if (!metadata.isFile || !File(directory, "audio.mp3").isFile)
                        throw PackImportException(ImportFailure.DAMAGED)
                    require(metadata.inputStream().sha256() == summary.partSha256) { "Part checksum mismatch" }
                    val part = PartCodec.decode(metadata.readText())
                    require(part.id == summary.id && part.book == index.book && part.test == summary.test && part.part == summary.part
                        && part.title == summary.title && part.questions.size == summary.questionCount
                        && part.questions.map { it.number } == (summary.firstNumber..summary.lastNumber).toList())
                    if (part.id == "cambridge-9-test-1-part-3" && protectedSample != null) {
                        if (part != PartCodec.decode(protectedSample)) throw PackImportException(ImportFailure.CONFLICT)
                    }
                    val audio = File(directory, "audio.mp3")
                    require(audio.length() == summary.audioBytes && audio.inputStream().sha256() == summary.audioSha256
                        && part.audioSha256 == summary.audioSha256) { "Audio checksum mismatch" }
                    expectedNames += "listening/${part.id}/part.json"
                    expectedNames += "listening/${part.id}/audio.mp3"
                    require(part.questions.flatMap { it.images }.toSet() == summary.imageHashes.keys) { "Image index mismatch" }
                    summary.imageHashes.forEach { (path, hash) ->
                        if (!File(directory, path).isFile) throw PackImportException(ImportFailure.DAMAGED)
                        require(File(directory, path).inputStream().sha256() == hash) { "Image checksum mismatch" }
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(File(directory, path).path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000)
                        expectedNames += "listening/${part.id}/$path"
                    }
                }
                require(names == expectedNames) { "Undeclared or missing pack entries" }
                val existing = installed()
                existing[index.book]?.let { folder ->
                    val previous = json.decodeFromString<BookIndex>(File(root, "$folder/index.json").readText()).validate()
                    previous.parts.forEach { old ->
                        if (index.parts.singleOrNull { it.id == old.id } != old) throw PackImportException(ImportFailure.CONFLICT)
                    }
                }
                onProgress(ImportStage.SAVING, index.book)
                val folder = "book-${index.book}-${indexFile.inputStream().sha256()}"
                val destination = File(root, folder)
                if (!destination.exists()) check(staging.renameTo(destination)) { "Cannot install book" }
                val stream = catalog.startWrite()
                try {
                    stream.write(json.encodeToString(existing + (index.book to folder)).toByteArray())
                    catalog.finishWrite(stream)
                } catch (error: Throwable) { catalog.failWrite(stream); throw error }
                index
            } finally {
                // Only the task-owned staging directory; exam sources and databases are untouched.
                if (staging.exists()) staging.deleteRecursively()
            }
        }
    }
}
