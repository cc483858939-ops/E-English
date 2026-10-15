package com.eenglish.listening

import com.eenglish.listening.ui.components.singleChoiceHeading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutV4SingleChoiceHeadingTest {
    @Test fun numberedPromptDoesNotRepeatQuestionNumber() {
        val group = groupWithPrompt("21 Students entering the design competition have to")
        assertNull(singleChoiceHeading(group, group.getValue("blocks").jsonArray[1].jsonObject, 21))
    }

    @Test fun unnumberedPromptKeepsQuestionNumber() {
        val group = groupWithPrompt("Carla and Rob were surprised to learn that coastal cities")
        assertEquals("Q21", singleChoiceHeading(group, group.getValue("blocks").jsonArray[1].jsonObject, 21))
    }

    private fun groupWithPrompt(prompt: String) = Json.parseToJsonElement("""
        {"blocks":[
          {"id":"prompt","type":"paragraph","children":[{"id":"text","type":"text","text":"$prompt"}]},
          {"id":"options","type":"choiceList"}
        ]}
    """).jsonObject
}
