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
    private data class Editor(val number: Int, val id: String, val box: LinearLayout,
        val label: TextView, val input: EditText, var programmatic: Boolean = false)
    private val editors = mutableListOf<Editor>()
    private var projection: InlinePromptProjection? = null
    private var enabledForEdit = false
    private var currentActiveNumber: Int? = null
    private var onActivated: (Int) -> Unit = {}
    private var onChanged: (String, String) -> Unit = { _, _ -> }
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

    private fun makeEditor(number: Int, id: String): Editor {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = boxHeight
            background = border(false, false)
            isClickable = true
        }
        val label = TextView(context).apply {
            text = number.toString()
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
            contentDescription = "Q§number 答案".replace("§number", number.toString())
            tag = "inline-input-$number"
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
        val editor = Editor(number, id, row, label, input)
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
                    if (s != null && s.length <= 512) onChanged(id, s.toString())
                }
            }
            override fun afterTextChanged(s: Editable?) {
                if (s != null && s.length > 512) {
                    editor.programmatic = true
                    s.delete(512, s.length)
                    editor.programmatic = false
                    onChanged(id, s.toString())
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
        projection = display
        enabledForEdit = editable
        onActivated = onActivate
        onChanged = onEdit
        onBlur = onCommit
        currentActiveNumber = activeNumber
        val document = AnnotationDocument.single(group.prompt, "question",
            "${group.questions.first().id}/prompt", "und")
        val otherDocuments = group.questions.drop(1).map {
            AnnotationDocument.single(it.prompt, "question", "${it.id}/prompt", "und")
        }
        textView.canAnnotate = annotations.partId != null
        textView.onTap = null
        textView.onGapClick = null
        textView.copyTransform = { start, end ->
            val originalStart = display.sourceOffset(start)
            val originalEnd = display.sourceOffset(end)
            group.prompt.substring(originalStart, originalEnd)
        }
        textView.onMark = { start, end, add ->
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
        }
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
        // Keep normal prose compact; GapAnchorSpan expands ONLY the lines that contain editors.
        if (linePx > 0) TextViewCompat.setLineHeight(textView, linePx)
        textView.setTextColor(0xFF22222A.toInt())
        textView.render(display.text, display.visibleHighlights(ranges))
        val spannable = textView.text as Spannable
        spannable.getSpans(0, spannable.length, GapAnchorSpan::class.java)
            .forEach { spannable.removeSpan(it) }
        display.slots.forEach { slot ->
            spannable.setSpan(GapAnchorSpan(boxWidth, boxHeight), slot.offset, slot.offset + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (editors.map { it.id } != group.questions.map { it.id }) {
            editors.forEach { removeView(it.box) }
            editors.clear()
            group.questions.forEach { editors += makeEditor(it.number, it.id) }
        }
        editors.forEach { e ->
            e.input.isEnabled = editable
            e.box.isClickable = editable
            val current = answers[e.id].orEmpty()
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
        val display = projection ?: return
        display.slots.forEach { slot ->
            val editor = editors.firstOrNull { it.number == slot.gap.number } ?: return@forEach
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
