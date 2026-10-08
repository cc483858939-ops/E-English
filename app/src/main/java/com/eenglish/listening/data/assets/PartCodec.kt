package com.eenglish.listening.data.assets

import com.eenglish.listening.domain.model.ListeningPart
import kotlinx.serialization.json.Json

object PartCodec {
    private val json = Json { ignoreUnknownKeys = false }
    fun decode(text: String): ListeningPart = json.decodeFromString<ListeningPart>(text).validate()
}
