package com.eenglish.listening.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.style.ReplacementSpan
import android.util.TypedValue
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import com.eenglish.listening.domain.annotation.*

@Composable
internal fun V4SelectableText(projection: AnnotationProjection, tag: String,
    modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge,
    onTap: (() -> Unit)? = null, fillWidth: Boolean = false) {
    val document = remember(projection.text) { AnnotationDocument(projection.text, emptyList()) }
    AnnotatableText(document, modifier.testTag(tag), style = style, viewTag = tag,
        onTap = onTap, projection = projection, fillWidth = fillWidth)
}

internal data class V4InlineSlot(val offset: Int, val id: String, val content: @Composable () -> Unit)

/** Native selection underneath the existing Compose editors. Answer values are never part of source offsets. */
@Composable
internal fun V4SelectableRun(projection: AnnotationProjection, slots: List<V4InlineSlot>, tag: String,
    modifier: Modifier, style: TextStyle) {
    val annotations = LocalAnnotations.current
    val density = LocalDensity.current
    val fontPx = with(density) { style.fontSize.toPx() }
    val linePx = with(density) { style.lineHeight.toPx().toInt() }
    val widthPx = with(density) { 112.dp.roundToPx() }
    val heightPx = with(density) { 48.dp.roundToPx() }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    var positions by remember(projection.text) { mutableStateOf<List<IntOffset>>(emptyList()) }
    var firstBaseline by remember(projection.text) { mutableIntStateOf(0) }
    val ranges = annotations.partId?.let { projection.visibleRanges(it, annotations.highlights) }.orEmpty()
    Box(modifier.layout { measurable, constraints ->
        val measured = measurable.measure(constraints)
        layout(measured.width, measured.height, mapOf(FirstBaseline to firstBaseline)) { measured.placeRelative(0, 0) }
    }) {
        AndroidView(modifier = Modifier.fillMaxWidth().testTag(tag), factory = { V4RunTextView(it) }, update = { view ->
            view.tag = tag
            view.canAnnotate = annotations.partId != null
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
            TextViewCompat.setLineHeight(view, linePx)
            view.setTextColor(color)
            view.copyTransform = { start, end -> buildString {
                for (index in start until end) when {
                    projection.text[index] == '\uFFFC' -> append("_____")
                    projection.text[index] == '\u00a0' && slots.any { it.offset - 1 == index } -> append(' ')
                    else -> append(projection.text[index])
                }
            } }
            view.onMark = { start, end, add -> annotations.partId?.let {
                annotations.onChange(projection.selection(it, start, end, add), add)
            } }
            view.render(projection.text, ranges)
            view.anchors = slots.map { it.offset }
            view.onPositions = { if (positions != it) positions = it }
            view.onBaseline = { if (firstBaseline != it) firstBaseline = it }
            view.installAnchors(widthPx, heightPx)
        })
        slots.forEachIndexed { index, slot -> positions.getOrNull(index)?.let { position ->
            key(slot.id) {
                Box(Modifier.offset { position }.width(112.dp).height(48.dp)) { slot.content() }
            }
        } }
    }
}

private class V4RunTextView(context: Context) : SelectableAnnotationTextView(context) {
    var anchors = emptyList<Int>()
    var onPositions: (List<IntOffset>) -> Unit = {}
    var onBaseline: (Int) -> Unit = {}
    private var anchorHeight = 0
    fun installAnchors(width: Int, height: Int) {
        anchorHeight = height
        val spans = text as Spannable
        spans.getSpans(0, spans.length, EditorAnchor::class.java).forEach(spans::removeSpan)
        anchors.forEach { spans.setSpan(EditorAnchor(width, height), it, it + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        requestLayout()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val lines = layout ?: return
        onBaseline(totalPaddingTop + lines.getLineBaseline(0))
        onPositions(anchors.map { offset ->
            val line = lines.getLineForOffset(offset)
            IntOffset(totalPaddingLeft + lines.getPrimaryHorizontal(offset).toInt(),
                (totalPaddingTop + lines.getLineBaseline(line) +
                    (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f - anchorHeight / 2f).toInt())
        })
    }
}

private class EditorAnchor(private val width: Int, private val height: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let {
            // Grow both sides of the original font center. Growing only ascent leaves
            // surrounding prose at the bottom of a tall editor's line.
            val center = (it.ascent + it.descent) / 2
            it.ascent = minOf(it.ascent, center - height / 2)
            it.descent = maxOf(it.descent, center + (height + 1) / 2)
            it.top = minOf(it.top, it.ascent)
            it.bottom = maxOf(it.bottom, it.descent)
        }
        return width
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
}
