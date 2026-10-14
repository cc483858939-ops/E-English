package com.eenglish.listening.data.assets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Bounded parser for display-only layout v4 documents. */
object LayoutV4Codec {
    const val MAX_BYTES = 4_000_000
    private const val MAX_JSON_DEPTH = 32
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }

    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..MAX_BYTES) { "LAYOUT_SIZE_LIMIT" }
        validateJsonDepth(bytes)
        return json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("INVALID_LAYOUT_JSON")
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

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
