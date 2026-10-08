package com.eenglish.listening

import com.eenglish.listening.domain.model.*

/** Public synthetic fixtures only; never added to the application question bank. */
fun annotationFixture() = ListeningPart(1, "annotation-fixture", "TEST", 0, 0, 0, "Annotation fixture", "Choose one",
    "listening/annotation-fixture/audio.mp3", "0".repeat(64),
    Transcript("one repeat two repeat three.\nA sentence continues onto another line for selection.\n\nNext paragraph with several words.",
        "第一段包含重复词语和可选句子。\n\n第二段翻译用于跨模式验证。",
        listOf(TranscriptSegment("one repeat two repeat three.\nA sentence continues onto another line for selection.", "第一段包含重复词语和可选句子。"),
            TranscriptSegment("Next paragraph with several words.", "第二段翻译用于跨模式验证。"))),
    (21..30).map { Question("annotation-q$it", it, QuestionType.SINGLE_CHOICE, "Select part of this synthetic prompt.",
        listOf(Option("A", "First selectable option"), Option("B", "Second selectable option"), Option("C", "Third selectable option")), "A") })
