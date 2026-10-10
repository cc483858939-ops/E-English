package com.eenglish.listening.data.assets

import com.eenglish.listening.domain.model.QuestionLayoutDocument
import kotlinx.serialization.json.Json

object QuestionLayoutCodec {
    private const val MAX_BYTES = 4_000_000
    private const val MAX_JSON_DEPTH = 32
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun decode(bytes: ByteArray): QuestionLayoutDocument {
        require(bytes.size in 1..MAX_BYTES) { "LAYOUT_SIZE_LIMIT" }
        validateJsonDepth(bytes)
        return json.decodeFromString<QuestionLayoutDocument>(bytes.toString(Charsets.UTF_8))
    }

    /** Reject deeply nested untrusted JSON before recursive model decoding can exhaust the stack. */
    private fun validateJsonDepth(bytes: ByteArray) {
        var depth = 0
        var inString = false
        var escaped = false
        bytes.forEach { raw ->
            val value = raw.toInt().toChar()
            if (inString) {
                when {
                    escaped -> escaped = false
                    value == '\\' -> escaped = true
                    value == '"' -> inString = false
                }
            } else when (value) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    require(depth <= MAX_JSON_DEPTH) { "LAYOUT_NESTING_TOO_DEEP" }
                }
                '}', ']' -> {
                    depth--
                    require(depth >= 0) { "INVALID_LAYOUT_JSON" }
                }
            }
        }
        require(!inString && !escaped && depth == 0) { "INVALID_LAYOUT_JSON" }
    }
}
