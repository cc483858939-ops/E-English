package com.eenglish.listening.data.repository

import android.content.res.AssetManager
import com.eenglish.listening.data.assets.PartCodec
import com.eenglish.listening.domain.model.ListeningPart
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PartRepository(private val assets: AssetManager) {
    suspend fun loadParts(): List<ListeningPart> = withContext(Dispatchers.IO) {
        assets.list("listening").orEmpty().sorted().map { directory ->
            val part = assets.open("listening/$directory/part.json").bufferedReader().use { PartCodec.decode(it.readText()) }
            require(part.id == directory) { "Part directory mismatch" }
            // Check every bundled MP3 before listing it as playable, without network access.
            val digest = MessageDigest.getInstance("SHA-256")
            assets.open(part.audioPath).use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == part.audioSha256) { "Audio checksum mismatch" }
            part
        }
    }
}
