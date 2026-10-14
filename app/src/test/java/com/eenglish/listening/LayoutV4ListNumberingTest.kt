package com.eenglish.listening

import com.eenglish.listening.ui.components.numberedOrdinalAt
import com.eenglish.listening.ui.components.v4ListMarkerLabel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutV4ListNumberingTest {
    @Test fun numberedMarkersFollowSequenceAndYieldToPrintedQuestionNumbers() {
        val first = numberedItem("first")
        val second = numberedItem("second")
        val third = buildJsonObject { put("type", "paragraph") }
        val blocks = listOf(first, second, third)

        assertEquals(1, numberedOrdinalAt(blocks, 0))
        assertEquals(2, numberedOrdinalAt(blocks, 1))
        assertEquals(null, numberedOrdinalAt(blocks, 2))
        assertEquals("1.", v4ListMarkerLabel("numbered", explicitNumberPrefix = false, ordinal = 1))
        assertEquals("2.", v4ListMarkerLabel("numbered", explicitNumberPrefix = false, ordinal = 2))
        assertEquals("", v4ListMarkerLabel("numbered", explicitNumberPrefix = true, ordinal = 2))
        assertEquals("•", v4ListMarkerLabel("bullet", explicitNumberPrefix = true, ordinal = 2))
    }

    private fun numberedItem(id: String) = buildJsonObject {
        put("id", id)
        put("type", "listItem")
        put("marker", "numbered")
        put("children", buildJsonArray { })
    }
}
