package com.eenglish.listening.ui.components

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.Editable
import android.text.style.ReplacementSpan
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.eenglish.listening.domain.annotation.AnnotationDocument
import com.eenglish.listening.domain.annotation.HighlightRange
import com.eenglish.listening.domain.gapfill.GapFillGroup
import com.eenglish.listening.domain.gapfill.InlinePromptProjection
import com.eenglish.listening.domain.gapfill.StructuredLayoutProjection
import com.eenglish.listening.domain.model.Question
import com.eenglish.listening.domain.model.QuestionLayoutGroup
import com.eenglish.listening.domain.grading.Grader

/**
 * Real Android EditTexts, arranged on EXACTLY the glyph boxes reserved in TextView.layout.
 * Only the single U+FFFC placeholder is a ReplacementSpan: it never contains source CR/LF.
 * This is one native layout, not a dialog, detached input or absolute screen overlay.
 */
class NativeInlineGapFillView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val boxHeight = (48f * density).toInt()
    private val boxWidth = (146f * density).toInt()
    private val textView = SelectableAnnotationTextView(context)
    private data class Editor(val number: Int, val id: String, val slotIndex: Int, val box: LinearLayout,
        val label: TextView, val input: EditText, var programmatic: Boolean = false)
    private val editors = mutableListOf<Editor>()
    private data class Slot(val number: Int, val questionId: String, val slotIndex: Int, val offset: Int)
    private var renderedSlots: List<Slot> = emptyList()
    private var enabledForEdit = false
    private var currentActiveNumber: Int? = null
    private var onActivated: (Int) -> Unit = {}
    private var onChanged: (String, Int, String) -> Unit = { _, _, _ -> }
    private var onBlur: (String) -> Unit = {}

    init {
        clipChildren = false
        clipToPadding = false
        textView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(textView)
    }

    private fun border(focused: Boolean, wrong: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = density * 7
            setColor(if (focused) android.graphics.Color.WHITE else 0xFFE8E9FD.toInt())
            if (focused || wrong) setStroke((if (focused) 2.0f else 1.2f).times(density).toInt(),
                if (wrong) 0xFFCE3E4C.toInt() else 0xFF5364D5.toInt())
        }

    private fun makeEditor(number: Int, id: String, slotIndex: Int): Editor {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = boxHeight
            background = border(false, false)
            isClickable = true
        }
        val label = TextView(context).apply {
            text = if (slotIndex == 0) number.toString() else (slotIndex + 1).toString()
            setTextColor(0xFF334599.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding((10f * density).toInt(), 0, (4f * density).toInt(), 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val input = EditText(context).apply {
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF243671.toInt())
            setHintTextColor(0xFF6675A8.toInt())
            hint = "填写"
            minWidth = 0
            setPadding(0, 0, (6f * density).toInt(), 0)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            isFocusableInTouchMode = true
            contentDescription = if (slotIndex == 0) "Q$number 答案" else "Q$number 第${slotIndex + 1}空答案"
            tag = if (slotIndex == 0) "inline-input-$number" else "inline-input-$number-${slotIndex + 1}"
        }
        row.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(input, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.setOnClickListener {
            if (enabledForEdit) {
                input.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                input.post {
                    imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                    input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height))
                }
            }
        }
        val editor = Editor(number, id, slotIndex, row, label, input)
        input.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) {
                currentActiveNumber = number
                onActivated(number)
                input.post {
                    input.requestRectangleOnScreen(Rect(0, 0, input.width, input.height))
                }
            } else {
                onBlur(id)
            }
            refreshAppearance()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!editor.programmatic && enabledForEdit) {
                    if (s != null && s.length <= 512) onChanged(id, slotIndex, s.toString())
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (s != null && s.length > 512) {
                    editor.programmatic = true
                    s.delete(512, s.length)
                    editor.programmatic = false
                    onChanged(id, slotIndex, s.toString())
                }
            }
        })
        addView(row, LayoutParams(boxWidth, boxHeight))
        return editor
    }

    private fun refreshAppearance(wrong: Set<Int> = emptySet()) {
        editors.forEach { e ->
            val focus = e.input.hasFocus()
            e.box.background = border(focus || (!enabledForEdit && currentActiveNumber == e.number),
                e.number in wrong)
            e.box.alpha = if (enabledForEdit) 1f else 0.82f
        }
    }

    fun render(
        group: GapFillGroup, display: InlinePromptProjection,
        answers: Map<String, String>, editable: Boolean, activeNumber: Int?,
        ranges: List<HighlightRange>, annotations: AnnotationContext,
        fontPx: Float, linePx: Int, onActivate: (Int) -> Unit,
        onEdit: (String, String) -> Unit, onCommit: (String) -> Unit,
        submitted: Boolean = false, incorrect: Set<Int> = emptySet()
    ) {
        val nativeSlots = display.slots.map { slot ->
            Slot(slot.gap.number, slot.gap.questionId, 0, slot.offset)
        }
        val document = AnnotationDocument.single(group.prompt, "question",
            "${group.questions.first().id}/prompt", "und")
        val otherDocuments = group.questions.drop(1).map {
            AnnotationDocument.single(it.prompt, "question", "${it.id}/prompt", "und")
        }
        renderContent(
            questions = group.questions,
            displayText = display.text,
            slots = nativeSlots,
            answers = answers,
            editable = editable,
            activeNumber = activeNumber,
            ranges = display.visibleHighlights(ranges),
            annotations = annotations,
            fontPx = fontPx,
            linePx = linePx,
            onActivate = onActivate,
            onEditSlot = { id, _, value -> onEdit(id, value) },
            onCommit = onCommit,
            copy = { start, end ->
                val originalStart = display.sourceOffset(start)
                val originalEnd = display.sourceOffset(end)
                group.prompt.substring(originalStart, originalEnd)
            },
            mark = { start, end, add ->
                annotations.partId?.let { partId ->
                    val originalStart = display.sourceOffset(start)
                    val originalEnd = display.sourceOffset(end)
                    if (originalEnd > originalStart) {
                        val docs = if (add) listOf(group.questions.find { it.number == currentActiveNumber }?.let {
                            AnnotationDocument.single(it.prompt, "question", "${it.id}/prompt", "und")
                        } ?: document) else listOf(document) + otherDocuments
                        annotations.onChange(docs.flatMap { it.selection(partId, originalStart, originalEnd) }, add)
                    }
                }
            },
            submitted = submitted,
            incorrect = incorrect,
        )
    }

    fun renderStructured(
        group: QuestionLayoutGroup, questions: List<Question>, display: StructuredLayoutProjection,
        answers: Map<String, String>, editable: Boolean, activeNumber: Int?,
        ranges: List<HighlightRange>, annotations: AnnotationContext,
        fontPx: Float, linePx: Int, onActivate: (Int) -> Unit,
        onEditSlot: (String, Int, String) -> Unit, onCommit: (String) -> Unit,
        submitted: Boolean = false, incorrect: Set<Int> = emptySet()
    ) {
        val nativeSlots = display.slots.map { slot ->
            Slot(slot.number, slot.questionId, slot.slotIndex, slot.offset)
        }
        val mappedRanges = annotations.partId?.let { partId ->
            display.visibleHighlights(partId, group, questions, annotations.highlights)
        }.orEmpty()
        renderContent(
            questions = questions,
            displayText = display.text,
            slots = nativeSlots,
            answers = answers,
            editable = editable,
            activeNumber = activeNumber,
            ranges = mappedRanges,
            annotations = annotations,
            fontPx = fontPx,
            linePx = linePx,
            onActivate = onActivate,
            onEditSlot = onEditSlot,
            onCommit = onCommit,
            copy = display::copyVisible,
            mark = { start, end, add ->
                annotations.partId?.let { partId ->
                    annotations.onChange(display.selection(partId, group, questions,
                        activeQuestionId = currentActiveNumber?.let { number -> questions.firstOrNull { it.number == number }?.id },
                        start = start, end = end, add = add), add)
                }
            },
            submitted = submitted,
            incorrect = incorrect,
        )
    }

    private fun renderContent(
        questions: List<Question>, displayText: String, slots: List<Slot>,
        answers: Map<String, String>, editable: Boolean, activeNumber: Int?,
        ranges: List<HighlightRange>, annotations: AnnotationContext,
        fontPx: Float, linePx: Int, onActivate: (Int) -> Unit,
        onEditSlot: (String, Int, String) -> Unit, onCommit: (String) -> Unit,
        copy: (Int, Int) -> String, mark: (Int, Int, Boolean) -> Unit,
        submitted: Boolean, incorrect: Set<Int>
    ) {
        renderedSlots = slots
        enabledForEdit = editable
        onActivated = onActivate
        onChanged = onEditSlot
        onBlur = onCommit
        currentActiveNumber = activeNumber
        textView.canAnnotate = annotations.partId != null
        textView.onTap = null
        textView.onGapClick = null
        textView.copyTransform = copy
        textView.onMark = mark
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
        // Keep normal prose compact; GapAnchorSpan expands ONLY the lines that contain editors.
        if (linePx > 0) TextViewCompat.setLineHeight(textView, linePx)
        textView.setTextColor(0xFF22222A.toInt())
        textView.render(displayText, ranges)
        val spannable = textView.text as Spannable
        spannable.getSpans(0, spannable.length, GapAnchorSpan::class.java)
            .forEach { spannable.removeSpan(it) }
        slots.forEach { slot ->
            spannable.setSpan(GapAnchorSpan(boxWidth, boxHeight), slot.offset, slot.offset + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val expectedEditorKeys = slots.map { "${it.questionId}:${it.slotIndex}" }
        if (editors.map { "${it.id}:${it.slotIndex}" } != expectedEditorKeys) {
            editors.forEach { removeView(it.box) }
            editors.clear()
            slots.forEach { slot -> editors += makeEditor(slot.number, slot.questionId, slot.slotIndex) }
        }
        editors.forEach { e ->
            e.input.isEnabled = editable
            e.box.isClickable = editable
            val question = questions.firstOrNull { it.id == e.id }
            val current = question?.let { Grader.answerParts(it, answers[e.id]).getOrElse(e.slotIndex) { "" } }.orEmpty()
            if (e.input.text.toString() != current && !e.input.hasFocus()) {
                e.programmatic = true
                e.input.setText(current)
                e.programmatic = false
            }
        }
        refreshAppearance(if (submitted) incorrect else emptySet())
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val textLayout = textView.layout ?: return
        renderedSlots.forEach { slot ->
            val editor = editors.firstOrNull { it.id == slot.questionId && it.slotIndex == slot.slotIndex } ?: return@forEach
            val line = textLayout.getLineForOffset(slot.offset)
            val x = (textView.left + textView.totalPaddingLeft + textLayout.getPrimaryHorizontal(slot.offset)).toInt()
            val y = textView.top + textView.totalPaddingTop +
                (textLayout.getLineTop(line) + textLayout.getLineBottom(line) - boxHeight) / 2
            editor.box.layout(x, y, x + boxWidth, y + boxHeight)
        }
    }
}

/** One measured inline token: never crosses a newline and is drawn by a REAL EditText above. */
private class GapAnchorSpan(private val boxWidth: Int, private val boxHeight: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
        fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = minOf(fm.ascent, fm.descent - boxHeight)
            fm.top = minOf(fm.top, fm.ascent)
        }
        return boxWidth
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        // The native focusable EditText draws the answer, caret and focus border.
    }
}
