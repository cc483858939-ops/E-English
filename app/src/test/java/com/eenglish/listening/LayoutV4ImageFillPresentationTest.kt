package com.eenglish.listening

import com.eenglish.listening.ui.components.usesImageFillAnswerList
import com.eenglish.listening.ui.components.usesC11ReportResponsibilitiesPilot
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutV4ImageFillPresentationTest {
    @Test fun reportResponsibilitiesPilotIsLimitedToItsExplicitGroupId() {
        assertTrue(usesC11ReportResponsibilitiesPilot(
            "cambridge-11-test-3-part-3-report-responsibilities-q27-q30"))
        assertFalse(usesC11ReportResponsibilitiesPilot(
            "cambridge-11-test-3-part-3-story-drawing-changes-q21-q26"))
        assertFalse(usesC11ReportResponsibilitiesPilot("some-other-matching-group"))
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
}
