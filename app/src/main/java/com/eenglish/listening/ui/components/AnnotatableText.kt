package com.eenglish.listening.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.core.widget.TextViewCompat
import com.eenglish.listening.domain.annotation.*

data class AnnotationContext(val partId: String? = null, val highlights: List<Highlight> = emptyList(),
    val onChange: (List<AnnotationChange>, Boolean) -> Unit = { _, _ -> })
val LocalAnnotations = staticCompositionLocalOf { AnnotationContext() }

/** Native selection indexes/menu, with spans mutated in place so selection and layout stay intact. */
@Composable
fun AnnotatableText(document: AnnotationDocument, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = MaterialTheme.colorScheme.onSurface,
    viewTag: String = "annotation-text", onTap: (() -> Unit)? = null) {
    val annotations = LocalAnnotations.current
    val density = LocalDensity.current
    val textSize = with(density) { style.fontSize.toPx() }
    val lineHeight = with(density) { style.lineHeight.toPx() }.toInt()
    val ranges = annotations.partId?.let { document.visibleRanges(it, annotations.highlights) }.orEmpty()
    AndroidView(modifier = modifier.fillMaxWidth().semantics { this.text = AnnotatedString(document.text) },
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
                annotations.partId?.let { annotations.onChange(document.selection(it, start, end), add) }
            }
            view.onTap = onTap
            view.render(document.text, ranges)
        })
}

private class PermanentBackground : BackgroundColorSpan(0xFFFFE29A.toInt())
private class PermanentForeground : ForegroundColorSpan(0xFF29230E.toInt())

class SelectableAnnotationTextView(context: Context) : TextView(context) {
    var canAnnotate = false
    var onMark: (Int, Int, Boolean) -> Unit = { _, _, _ -> }
    var onTap: (() -> Unit)? = null
    private var wasSelectingAtDown = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var actionMode: ActionMode? = null
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
            wasSelectingAtDown = actionMode != null
            moved = false
            downX = event.x; downY = event.y
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) moved = true
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !wasSelectingAtDown && !moved &&
            event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) onTap?.invoke()
        return handled
    }
    fun render(value: String, ranges: List<HighlightRange>) {
        val changed = text.toString() != value
        if (changed) {
            actionMode?.finish()
            setText(value, BufferType.SPANNABLE)
        }
        if (!changed && renderedRanges == ranges) return
        val spans = text as Spannable
        spans.getSpans(0, spans.length, PermanentBackground::class.java).forEach(spans::removeSpan)
        spans.getSpans(0, spans.length, PermanentForeground::class.java).forEach(spans::removeSpan)
        ranges.forEach {
            spans.setSpan(PermanentBackground(), it.start, it.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spans.setSpan(PermanentForeground(), it.start, it.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        renderedRanges = ranges
        invalidate()
    }
    companion object { private const val ADD = 0x6E0101; private const val REMOVE = 0x6E0102 }
}
