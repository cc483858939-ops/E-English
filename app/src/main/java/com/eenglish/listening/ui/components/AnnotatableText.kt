package com.eenglish.listening.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Canvas
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import android.graphics.Rect
import android.graphics.RectF
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.core.widget.TextViewCompat
import com.eenglish.listening.domain.annotation.*

/** Original-text ranges; the displayed chip never changes the underlying prompt or its hash. */
data class InlineAnswerGap(val number: Int, val start: Int, val end: Int, val answer: String,
    val active: Boolean = false, val correct: Boolean? = null)

data class AnnotationContext(val partId: String? = null, val highlights: List<Highlight> = emptyList(),
    val onChange: (List<AnnotationChange>, Boolean) -> Unit = { _, _ -> })
val LocalAnnotations = staticCompositionLocalOf { AnnotationContext() }

/** Native selection indexes/menu, with spans mutated in place so selection and layout stay intact. */
@Composable
fun AnnotatableText(document: AnnotationDocument, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onSurface,
    viewTag: String = "annotation-text", onTap: (() -> Unit)? = null,
    inlineGaps: List<InlineAnswerGap> = emptyList(), otherDocuments: List<AnnotationDocument> = emptyList(),
    activeDocument: AnnotationDocument? = null, onGapClick: ((Int) -> Unit)? = null) {
    val annotations = LocalAnnotations.current
    val density = LocalDensity.current
    val textSize = with(density) { style.fontSize.toPx() }
    val lineHeight = with(density) { style.lineHeight.toPx() }.toInt()
    val ranges = annotations.partId?.let { partId ->
        (listOf(document) + otherDocuments).flatMap { it.visibleRanges(partId, annotations.highlights) }
    }.orEmpty()
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val foregroundColor = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
    val outlineColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(modifier = modifier.fillMaxWidth().semantics {
        this.text = AnnotatedString(document.text)
        if (inlineGaps.isNotEmpty()) customActions = inlineGaps.map { gap ->
            CustomAccessibilityAction("填写 Q${gap.number}") { onGapClick?.invoke(gap.number); onGapClick != null }
        }
    },
        factory = { context -> SelectableAnnotationTextView(context) },
        update = { view ->
            view.tag = viewTag
            if (view.textSize != textSize) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            val face = if ((style.fontWeight?.weight ?: 400) >= 500) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            if (view.typeface != face) view.typeface = face
            if (view.lineHeight != lineHeight) TextViewCompat.setLineHeight(view, lineHeight)
            if (view.currentTextColor != color.toArgb()) view.setTextColor(color.toArgb())
            view.canAnnotate = annotations.partId != null
            view.onMark = { start, end, add ->
                annotations.partId?.let { partId ->
                    // On removal, include every original question key: old highlights may have
                    // been saved under any member before this display-layer grouping existed.
                    val documents = if (add) listOf(activeDocument ?: document) else listOf(document) + otherDocuments
                    annotations.onChange(documents.flatMap { it.selection(partId, start, end) }, add)
                }
            }
            view.onTap = onTap
            view.onGapClick = onGapClick
            view.gapBackground = backgroundColor
            view.gapForeground = foregroundColor
            view.gapOutline = outlineColor
            view.render(document.text, ranges, inlineGaps)
        })
}

private class PermanentBackground : BackgroundColorSpan(0xFFFFE29A.toInt())
private class PermanentForeground : ForegroundColorSpan(0xFF29230E.toInt())

class SelectableAnnotationTextView(context: Context) : TextView(context) {
    var canAnnotate = false
    var onMark: (Int, Int, Boolean) -> Unit = { _, _, _ -> }
    var onTap: (() -> Unit)? = null
    var onGapClick: ((Int) -> Unit)? = null
    var gapBackground: Int = 0xFFE2E8FF.toInt()
    var gapForeground: Int = 0xFF16235C.toInt()
    var gapOutline: Int = 0xFF5267CD.toInt()
    private var renderedGaps: List<InlineAnswerGap> = emptyList()
    private var pressedGap: Int? = null
    private var wasSelectingAtDown = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var actionMode: ActionMode? = null
    private var blockActionMode: ActionMode? = null
    private var activeBlock: HighlightRange? = null
    private var renderedRanges: List<HighlightRange> = emptyList()
    init {
        setTextIsSelectable(true)
        // Keep manually chosen ranges exact; no asynchronous smart-selection expansion.
        setTextClassifier(TextClassifier.NO_OP)
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                actionMode = mode
                menu.clear()
                menu.add(0, android.R.id.copy, 0, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, ADD, 1, "高亮").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, REMOVE, 2, "取消高亮").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, android.R.id.selectAll, 3, "全选").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.findItem(ADD)?.isEnabled = canAnnotate
                menu.findItem(REMOVE)?.isEnabled = canAnnotate
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val start = minOf(selectionStart, selectionEnd)
                val end = maxOf(selectionStart, selectionEnd)
                if (start < 0 || end <= start || end > text.length) return false
                when (item.itemId) {
                    android.R.id.copy -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("E-English", text.subSequence(start, end).toString()))
                    ADD -> if (canAnnotate) onMark(start, end, true)
                    REMOVE -> if (canAnnotate) onMark(start, end, false)
                    android.R.id.selectAll -> return onTextContextMenuItem(android.R.id.selectAll)
                    else -> return false
                }
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) { actionMode = null }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            blockActionMode?.finish()
            wasSelectingAtDown = actionMode != null
            moved = false
            downX = event.x; downY = event.y
            pressedGap = gapAt(event.x, event.y)?.number
            invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) moved = true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) moved = true
        if (moved || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressedGap = null
            invalidate()
        }
        val shortTap = event.actionMasked == MotionEvent.ACTION_UP && !moved &&
            event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
        if (shortTap) {
            gapAt(event.x, event.y)?.let { gap ->
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
                pressedGap = null
                invalidate()
                onGapClick?.invoke(gap.number)
                return true
            }
            highlightedAt(event.x, event.y)?.let { range ->
                // Cancel the native tap (including double-tap word selection), not the long press.
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
                showBlockMenu(range, event.x, event.y)
                return true // A highlighted option must never also select an answer.
            }
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            pressedGap = null
            invalidate()
        }
        if (shortTap && !wasSelectingAtDown) onTap?.invoke()
        return handled
    }
    private fun gapAt(x: Float, y: Float): InlineAnswerGap? {
        val layout = layout ?: return null
        val localX = x - totalPaddingLeft + scrollX
        val localY = y - totalPaddingTop + scrollY
        if (localY < 0 || localY >= layout.height) return null
        val line = layout.getLineForVertical(localY.toInt())
        val path = Path()
        val bounds = RectF()
        return renderedGaps.firstOrNull { gap ->
            if (layout.getLineForOffset(gap.start) != line) return@firstOrNull false
            path.reset()
            layout.getSelectionPath(gap.start, gap.end, path)
            path.computeBounds(bounds, true)
            bounds.contains(localX, localY)
        }
    }
    private fun highlightedAt(x: Float, y: Float): HighlightRange? {
        val layout = layout ?: return null
        val localX = x - totalPaddingLeft + scrollX
        val localY = y - totalPaddingTop + scrollY
        if (localY < 0 || localY >= layout.height) return null
        val line = layout.getLineForVertical(localY.toInt())
        if (localX < layout.getLineLeft(line) || localX >= layout.getLineRight(line)) return null
        val caret = layout.getOffsetForHorizontal(line, localX)
        val previous = if (caret > 0) Character.offsetByCodePoints(text, caret, -1) else -1
        // Layout returns the nearest caret, which can be AFTER the tapped character.
        // Check its glyph box and the preceding code point so right-half taps stay exact.
        val path = Path()
        val bounds = RectF()
        for (offset in listOf(caret, previous)) {
            if (offset < layout.getLineStart(line) || offset >= layout.getLineEnd(line) || offset >= text.length) continue
            path.reset()
            layout.getSelectionPath(offset, Character.offsetByCodePoints(text, offset, 1), path)
            path.computeBounds(bounds, true)
            if (bounds.contains(localX, localY)) return renderedRanges.firstOrNull { offset >= it.start && offset < it.end }
        }
        return null
    }
    private fun showBlockMenu(range: HighlightRange, x: Float, y: Float) {
        actionMode?.finish()
        blockActionMode?.finish()
        val original = text.toString()
        activeBlock = range
        blockActionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, COPY_BLOCK, 0, "复制整块高亮").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, REMOVE, 1, "取消整块高亮").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.findItem(REMOVE)?.isEnabled = canAnnotate
                return false
            }
            override fun onGetContentRect(mode: ActionMode, view: View, rect: Rect) {
                val radius = (8 * resources.displayMetrics.density).toInt()
                rect.set(x.toInt() - radius, y.toInt() - radius, x.toInt() + radius, y.toInt() + radius)
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (text.toString() == original && range in renderedRanges) {
                    when (item.itemId) {
                        COPY_BLOCK -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("E-English", original.substring(range.start, range.end)))
                        REMOVE -> if (canAnnotate) onMark(range.start, range.end, false)
                        else -> return false
                    }
                }
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                if (blockActionMode == mode) { blockActionMode = null; activeBlock = null }
            }
        }, ActionMode.TYPE_FLOATING)
        if (blockActionMode == null) activeBlock = null
    }
    override fun onDetachedFromWindow() {
        blockActionMode?.finish()
        super.onDetachedFromWindow()
    }
    fun render(value: String, ranges: List<HighlightRange>, gaps: List<InlineAnswerGap> = emptyList()) {
        val merged = HighlightRanges.merge(ranges)
        val changed = text.toString() != value
        if (changed || activeBlock?.let { it !in merged } == true) blockActionMode?.finish()
        if (changed) {
            actionMode?.finish()
            setText(value, BufferType.SPANNABLE)
        }
        if (!changed && renderedRanges == merged && renderedGaps == gaps) return
        val spans = text as Spannable
        spans.getSpans(0, spans.length, PermanentBackground::class.java).forEach(spans::removeSpan)
        spans.getSpans(0, spans.length, PermanentForeground::class.java).forEach(spans::removeSpan)
        spans.getSpans(0, spans.length, InlineAnswerSpan::class.java).forEach(spans::removeSpan)
        merged.forEach {
            spans.setSpan(PermanentBackground(), it.start, it.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spans.setSpan(PermanentForeground(), it.start, it.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        gaps.forEach { gap ->
            require(gap.start >= 0 && gap.end <= spans.length && gap.start < gap.end)
            val maximum = ((width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 0.72f).toInt()
            spans.setSpan(InlineAnswerSpan(gap, maximum,
                resources.displayMetrics.density, gapBackground, gapForeground, gapOutline,
                { pressedGap == gap.number }),
                gap.start, gap.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        renderedRanges = merged
        renderedGaps = gaps
        invalidate()
    }
    companion object { private const val ADD = 0x6E0101; private const val REMOVE = 0x6E0102; private const val COPY_BLOCK = 0x6E0103 }
}


/** A native ReplacementSpan keeps source selection offsets stable and wraps as one inline glyph. */
private class InlineAnswerSpan(
    private val gap: InlineAnswerGap, private val availablePx: Int, density: Float,
    private val background: Int, private val foreground: Int, private val outline: Int,
    private val isPressed: () -> Boolean
) : ReplacementSpan() {
    private val pad = 9f * density
    private val radius = 8f * density
    private val maximum = minOf(availablePx, (230f * density).toInt()).coerceAtLeast((72f * density).toInt())
    private fun label() = "${gap.number} · ${gap.answer.ifBlank { "填写" }}"
    private fun visibleLabel(paint: Paint): String = TextUtils.ellipsize(label(), TextPaint(paint),
        (maximum - pad * 2).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
        fm: Paint.FontMetricsInt?): Int = ceil(paint.measureText(visibleLabel(paint)) + pad * 2).toInt()
            .coerceAtMost(maximum)
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val label = visibleLabel(paint)
        val width = getSize(paint, text, start, end, null).toFloat()
        val saved = paint.color
        val savedStyle = paint.style
        val savedStroke = paint.strokeWidth
        val bounds = RectF(x, top.toFloat() + 1f, x + width, bottom.toFloat() - 1f)
        paint.style = Paint.Style.FILL
        paint.color = if (isPressed()) outline else background
        canvas.drawRoundRect(bounds, radius, radius, paint)
        if (gap.active || gap.correct != null) {
            paint.color = if (gap.correct == false) 0xFFC64046.toInt() else outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (gap.active) 2.5f else 1.3f
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (isPressed()) 0xFFFFFFFF.toInt() else foreground
        canvas.drawText(label, x + pad, y.toFloat(), paint)
        paint.color = saved
        paint.style = savedStyle
        paint.strokeWidth = savedStroke
    }
}
