package com.eenglish.listening.data.repository

import android.content.res.AssetManager
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.data.assets.LayoutV4Codec
import com.eenglish.listening.data.assets.QuestionLayoutCodec
import com.eenglish.listening.domain.model.ListeningPart
import com.eenglish.listening.domain.model.PartSummary
import com.eenglish.listening.domain.model.BookIndex
import com.eenglish.listening.domain.model.TranscriptTimeline
import com.eenglish.listening.domain.model.TranscriptTimelineCatalog
import com.eenglish.listening.domain.model.QuestionLayoutDocument
import com.eenglish.listening.domain.layout.QuestionLayoutValidator
import com.eenglish.listening.domain.layout.LayoutV4ImageMetadata
import com.eenglish.listening.domain.layout.LayoutV4Validator
import java.io.File
import java.io.InputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }

/** Keep a bundled Part primary while checking V4 against the package's exact raw bytes. */
internal fun validateV4ForDisplayedPart(
    displayedPart: ListeningPart,
    packPartBytes: ByteArray,
    layoutBytes: ByteArray,
    imageMetadata: Map<String, LayoutV4ImageMetadata>,
): JsonObject {
    val decodedPackPart = PartCodec.decode(packPartBytes.toString(Charsets.UTF_8))
    require(decodedPackPart == displayedPart) { "DISPLAYED_PART_MISMATCH" }
    val layout = LayoutV4Codec.decode(layoutBytes)
    return LayoutV4Validator.validate(layout, decodedPackPart, packPartBytes, imageMetadata)
}

class PartRepository(private val assets: AssetManager, private val library: File? = null) {
    private val timelines by lazy {
        try {
            assets.open("transcript-timelines.json").bufferedReader().use {
                Json.decodeFromString<TranscriptTimelineCatalog>(it.readText())
            }.takeIf { it.schemaVersion == 1 }?.parts?.associateBy { it.partId }.orEmpty()
        } catch (_: Exception) { emptyMap<String, TranscriptTimeline>() }
    }

    suspend fun loadTranscriptTimeline(part: ListeningPart): TranscriptTimeline? = withContext(Dispatchers.IO) {
        timelines[part.id]?.takeIf { it.matches(part) }
    }
    private val importer = library?.let { OfflinePackImporter(it) }
    private val locations = java.util.concurrent.ConcurrentHashMap<String, File>()
    private data class LayoutSource(val directory: File, val sha256: String, val imageHashes: Map<String, String>)
    private val layoutSources = java.util.concurrent.ConcurrentHashMap<String, LayoutSource>()
    private val imageCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 4
    }

    suspend fun loadParts(): List<PartSummary> = withContext(Dispatchers.IO) {
        locations.clear()
        layoutSources.clear()
        val local = importer?.installed().orEmpty().flatMap { (_, folder) ->
            val root = File(requireNotNull(library), folder)
            val index = Json.decodeFromString<BookIndex>(File(root, "index.json").readText()).validate()
            index.parts.onEach {
                val directory = File(root, "listening/${it.id}")
                locations[it.id] = directory
                it.layoutSha256?.let { hash -> layoutSources[it.id] = LayoutSource(directory, hash, it.imageHashes) }
            }
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

    /** Returns only a hash-verified layout bound to the exact Part bytes selected by loadPart(). */
    suspend fun loadLayout(part: ListeningPart): QuestionLayoutDocument? = withContext(Dispatchers.IO) {
        val source = layoutSources[part.id] ?: return@withContext null
        try {
            val bytes = File(source.directory, "layout.json").readBytes()
            if (bytes.sha256() != source.sha256) return@withContext null
            if ((Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject)
                    ?.get("schemaVersion")?.jsonPrimitive?.intOrNull != 1) return@withContext null
            val partBytes = locations[part.id]?.let { File(it, "part.json").readBytes() }
                ?: assets.open("listening/${part.id}/part.json").use { it.readBytes() }
            val decodedPart = PartCodec.decode(partBytes.toString(Charsets.UTF_8))
            if (decodedPart != part) return@withContext null
            val layout = QuestionLayoutCodec.decode(bytes)
            QuestionLayoutValidator.validate(layout, part, partBytes)
        } catch (_: Exception) {
            // Unexpected runtime corruption falls back safely. Do not log exam text or answers.
            android.util.Log.w("PartRepository", "Invalid structured layout; using legacy rendering")
            null
        }
    }

    /** V4 binds to the installed pack's original Part bytes, even when a bundled C9 sample is primary. */
    suspend fun loadLayoutV4(part: ListeningPart): JsonObject? = withContext(Dispatchers.IO) {
        val source = layoutSources[part.id] ?: return@withContext null
        try {
            val bytes = File(source.directory, "layout.json").readBytes()
            if (bytes.sha256() != source.sha256) return@withContext null
            val layout = LayoutV4Codec.decode(bytes)
            if ((layout["schemaVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull != 4) {
                return@withContext null
            }
            val partBytes = File(source.directory, "part.json").readBytes()
            val images = linkedMapOf<String, LayoutV4ImageMetadata>()
            source.imageHashes.forEach { (path, hash) ->
                val raw = File(source.directory, path).readBytes()
                if (raw.sha256() != hash) return@withContext null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0 ||
                    options.outWidth.toLong() * options.outHeight > 32_000_000) return@withContext null
                images[path] = LayoutV4ImageMetadata(hash, options.outWidth, options.outHeight)
            }
            // This also confirms that any bundled sample used for practice decodes to the same
            // Part, while basePartSha256 is checked against the installed package's raw bytes.
            validateV4ForDisplayedPart(part, partBytes, bytes, images)
        } catch (_: Exception) {
            android.util.Log.w("PartRepository", "Invalid v4 layout; using legacy rendering")
            null
        }
    }

    fun audioPath(part: ListeningPart): String = locations[part.id]?.let { File(it, "audio.mp3").toURI().toString() } ?: part.audioPath

    suspend fun readImage(partId: String, path: String): Bitmap? = withContext(Dispatchers.IO) {
        require(path.matches(Regex("[a-zA-Z0-9_-]+\\.(png|jpg|jpeg|webp)")))
        val key = "$partId/$path"
        synchronized(imageCache) { imageCache[key] } ?: run {
            val raw = layoutSources[partId]?.directory?.let { File(it, path) }?.takeIf { it.isFile }?.readBytes()
                ?: locations[partId]?.let { File(it, path).readBytes() }
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
