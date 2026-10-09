package com.eenglish.listening.data.repository

import android.content.res.AssetManager
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.domain.model.PartSummary
import com.eenglish.listening.domain.model.BookIndex
import java.io.File
import java.io.InputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PartRepository(private val assets: AssetManager, private val library: File? = null) {
    private val importer = library?.let { OfflinePackImporter(it) }
    private val locations = java.util.concurrent.ConcurrentHashMap<String, File>()
    private val imageCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 4
    }

    suspend fun loadParts(): List<PartSummary> = withContext(Dispatchers.IO) {
        val local = importer?.installed().orEmpty().flatMap { (_, folder) ->
            val root = File(requireNotNull(library), folder)
            val index = Json.decodeFromString<BookIndex>(File(root, "index.json").readText()).validate()
            index.parts.onEach { locations[it.id] = File(root, "listening/${it.id}") }
        }
        val bundled = assets.list("listening").orEmpty().sorted().map { directory ->
            val part = assets.open("listening/$directory/part.json").bufferedReader().use { PartCodec.decode(it.readText()) }
            require(part.id == directory) { "Part directory mismatch" }
            locations.remove(part.id) // Preserve the existing bundled sample and annotation keys.
            PartSummary(part.id, part.book, part.test, part.part, part.title, part.questions.size,
                part.questions.first().number, part.questions.last().number)
        }
        (bundled + local).distinctBy { it.id }.sortedWith(compareBy({ it.book }, { it.test }, { it.part }))
    }

    suspend fun loadPart(id: String): ListeningPart = withContext(Dispatchers.IO) {
        require(id.matches(Regex("cambridge-\\d+-test-[1-4]-part-[1-4]")))
        val part = locations[id]?.let { PartCodec.decode(File(it, "part.json").readText()) }
            ?: assets.open("listening/$id/part.json").bufferedReader().use { PartCodec.decode(it.readText()) }
        require(part.id == id)
        val hash = locations[id]?.let { File(it, "audio.mp3").inputStream().sha256() }
            ?: assets.open(part.audioPath).sha256()
        require(hash == part.audioSha256) { "Audio checksum mismatch" }
        part
    }

    fun audioPath(part: ListeningPart): String = locations[part.id]?.let { File(it, "audio.mp3").toURI().toString() } ?: part.audioPath

    suspend fun readImage(partId: String, path: String): Bitmap? = withContext(Dispatchers.IO) {
        require(path.matches(Regex("[a-zA-Z0-9_-]+\\.(png|jpg|jpeg|webp)")))
        val key = "$partId/$path"
        synchronized(imageCache) { imageCache[key] } ?: run {
            val raw = locations[partId]?.let { File(it, path).readBytes() }
                ?: assets.open("listening/$partId/$path").use { it.readBytes() }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)?.also { synchronized(imageCache) { imageCache[key] = it } }
        }
    }

    suspend fun importPack(input: InputStream, onProgress: (ImportStage, Int?) -> Unit = { _, _ -> }): BookIndex {
        val sample = withContext(Dispatchers.IO) {
            if (assets.list("listening").orEmpty().contains("cambridge-9-test-1-part-3"))
                assets.open("listening/cambridge-9-test-1-part-3/part.json").bufferedReader().use { it.readText() }
            else null
        }
        return requireNotNull(importer).install(input, sample, onProgress)
    }
}
