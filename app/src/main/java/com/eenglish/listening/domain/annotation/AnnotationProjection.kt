package com.eenglish.listening.domain.annotation

/** Exact UTF-16 mappings. Separators and answer controls have no annotation source. */
data class AnnotationProjection(val text: String, val spans: List<Span>) {
    data class Source(val textId: String, val hash: String, val offset: Int, val length: Int) {
        fun key(partId: String) = HighlightKey(partId, "question", textId, "und", hash)
    }
    data class Span(val start: Int, val end: Int, val sources: List<Source>)

    init {
        require(spans.all { it.start >= 0 && it.end > it.start && it.end <= text.length &&
            it.sources.isNotEmpty() && it.sources.all { s -> s.offset >= 0 && s.offset + it.end - it.start <= s.length } })
    }

    fun selection(partId: String, start: Int, end: Int, add: Boolean): List<AnnotationChange> {
        require(start >= 0 && end > start && end <= text.length)
        return spans.flatMap { span ->
            val from = maxOf(start, span.start)
            val to = minOf(end, span.end)
            if (from >= to) emptyList() else (if (add) span.sources.take(1) else span.sources).map { source ->
                AnnotationChange(source.key(partId), source.offset + from - span.start,
                    source.offset + to - span.start, source.length)
            }
        }.distinct()
    }

    fun visibleRanges(partId: String, highlights: List<Highlight>): List<HighlightRange> =
        HighlightRanges.merge(spans.flatMap { span -> span.sources.flatMap { source ->
            highlights.filter { it.key == source.key(partId) }.mapNotNull { highlight ->
                val start = maxOf(highlight.range.start, source.offset)
                val end = minOf(highlight.range.end, source.offset + span.end - span.start)
                if (start >= end) null else highlight.range.copy(
                    start = span.start + start - source.offset, end = span.start + end - source.offset)
            }
        } })

    fun slice(start: Int, end: Int): AnnotationProjection {
        require(start >= 0 && end >= start && end <= text.length)
        return AnnotationProjection(text.substring(start, end), spans.mapNotNull { span ->
            val from = maxOf(start, span.start)
            val to = minOf(end, span.end)
            if (from >= to) null else Span(from - start, to - start,
                span.sources.map { it.copy(offset = it.offset + from - span.start) })
        })
    }

    companion object {
        fun join(parts: List<AnnotationProjection>, separator: String = " "): AnnotationProjection {
            val text = StringBuilder()
            val spans = mutableListOf<Span>()
            parts.forEachIndexed { index, part ->
                if (index > 0) text.append(separator)
                val offset = text.length
                text.append(part.text)
                spans += part.spans.map { it.copy(start = it.start + offset, end = it.end + offset) }
            }
            return AnnotationProjection(text.toString(), spans)
        }
    }
}
