package com.eenglish.listening

import com.eenglish.listening.ui.components.usesImageFillAnswerList
import com.eenglish.listening.ui.components.usesSharedTextMatchingLayout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutV4ImageFillPresentationTest {
    @Test fun sharedTextMatchingUsesTheDeclaredRowShapeForFormsAndParagraphs() {
        assertTrue(usesSharedTextMatchingLayout(matchingGroup("formRow")))
        assertTrue(usesSharedTextMatchingLayout(matchingGroup("paragraph")))
        assertFalse(usesSharedTextMatchingLayout(matchingGroup("formRow", withImage = true)))
    }

    @Test fun imageWithOnlyFillResponsesUsesQuestionNumberAnswerList() {
        assertTrue(usesImageFillAnswerList(group(imageInBlocks = true, responseKinds = listOf("fill"))))
    }

    @Test fun imageInsideMixedTextAndFlowContentUsesQuestionNumberAnswerList() {
        assertTrue(usesImageFillAnswerList(group(
            imageInBlocks = true,
            responseKinds = listOf("fill", "fill"),
            mixedContent = true,
        )))
    }

    @Test fun imageWithoutFillResponseKeepsExistingInteraction() {
        assertFalse(usesImageFillAnswerList(group(imageInBlocks = true, responseKinds = listOf("matching"))))
    }

    @Test fun fillGroupWithoutImageKeepsExistingInteraction() {
        assertFalse(usesImageFillAnswerList(group(imageInBlocks = false, responseKinds = listOf("fill"))))
    }

    @Test fun mixedFillAndChoiceResponsesKeepExistingInteraction() {
        assertFalse(usesImageFillAnswerList(group(
            imageInBlocks = true,
            responseKinds = listOf("fill", "singleChoice"),
        )))
    }

    @Test fun imageInInstructionsAlsoSelectsImageFillPresentation() {
        val group = buildJsonObject {
            put("instructions", buildJsonArray { add(buildJsonObject { put("type", "image") }) })
            put("responses", buildJsonArray { add(buildJsonObject { put("kind", "fill") }) })
            put("blocks", buildJsonArray { })
        }
        assertTrue(usesImageFillAnswerList(group))
    }

    private fun group(imageInBlocks: Boolean, responseKinds: List<String>, mixedContent: Boolean = false) =
        buildJsonObject {
            put("instructions", buildJsonArray { add(buildJsonObject { put("type", "text") }) })
            put("blocks", buildJsonArray {
                if (imageInBlocks) add(buildJsonObject { put("type", "image") })
                if (mixedContent) add(buildJsonObject {
                    put("type", "flow")
                    put("nodes", buildJsonArray { add(buildJsonObject {
                        put("children", buildJsonArray { add(buildJsonObject { put("type", "text") }) })
                    }) })
                })
            })
            put("responses", buildJsonArray {
                responseKinds.forEach { kind -> add(buildJsonObject { put("kind", kind) }) }
            })
        }

    private fun matchingGroup(rowType: String, withImage: Boolean = false) = buildJsonObject {
        put("instructions", buildJsonArray { })
        put("questionIds", buildJsonArray { add("q1"); add("q2") })
        put("optionPools", buildJsonArray { add(buildJsonObject {
            put("poolId", "shared"); put("optionIds", buildJsonArray { add("A"); add("B") })
        }) })
        put("slots", buildJsonArray { (1..2).forEach { number -> add(buildJsonObject {
            put("slotId", "s$number"); put("questionId", "q$number"); put("kind", "matching")
        }) } })
        put("responses", buildJsonArray { add(buildJsonObject {
            put("kind", "matching"); put("questionIds", buildJsonArray { add("q1"); add("q2") })
            put("slotIds", buildJsonArray { add("s1"); add("s2") }); put("optionPoolId", "shared")
        }) })
        put("blocks", buildJsonArray {
            if (withImage) add(buildJsonObject { put("type", "image") })
            (1..2).forEach { number -> add(buildJsonObject {
                put("type", rowType)
                put("children", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "Q$number") })
                    add(buildJsonObject {
                        put("type", "choiceList"); put("optionPoolId", "shared")
                        put("slotIds", buildJsonArray { add("s$number") })
                    })
                })
            }) }
        })
    }
}
