"""Conservative HTML-to-layout prototype for text-only Cambridge gap groups.

The module emits only groups backed by explicit source blank markers and explicit
question-number anchors. It never reads or copies answers into layout.json.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from bs4 import BeautifulSoup, NavigableString, Tag

from import_library import clean, text as decode_source_text
from layout_validator import LayoutValidationError, validate_layout


GAP_TOKEN = "\ue000GAP:{}\ue001"
SUPPORTED_TYPES = {"TEXT_INPUT", "NOTE_COMPLETION", "SHORT_ANSWER", "TABLE_COMPLETION", "FLOW_COMPLETION"}
MAX_HTML_BYTES = 16_000_000
MAX_HTML_CHARS = 8_000_000
QUESTION_NUMBER = re.compile(r"(?<![\w])([1-9]\d{0,2})(?![\w])")
PROMPT_GAP = re.compile(r"(?<![\w])(?P<number>[1-9]\d{0,2})[ \t\r\n]*(?:[.:][ \t\r\n]*)?(?P<gap>_{2,}(?:[ \t]*_{2,})*|[＿﹍]{2,})(?![\w])")
LIMIT_PREFIX = re.compile(r"\b(?:NO MORE THAN|ONE|TWO|THREE|FOUR|FIVE|SIX|[1-6])\b", re.I)


class LayoutGenerationIssue(ValueError):
    def __init__(self, reason_code: str, status: str = "requiresManualReview"):
        super().__init__(reason_code)
        self.reason_code = reason_code
        self.status = status


@dataclass
class Event:
    kind: str
    value: str = ""
    locator: str = ""
    start: int = 0
    end: int = 0
    list_item: bool = False


def _sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _utf16_offset(value: str, index: int) -> int:
    return len(value[:index].encode("utf-16-le")) // 2


def _all_occurrences(haystack: str, needle: str):
    if not needle:
        return []
    starts = []
    offset = 0
    while True:
        found = haystack.find(needle, offset)
        if found < 0:
            return starts
        starts.append(found)
        offset = found + 1


def _layout_kind(title: str, questions: list[dict]) -> str:
    lower = title.lower()
    for phrase, kind in (
        ("complete the summary", "summaryCompletion"),
        ("complete the notes", "notesCompletion"),
        ("complete the form", "formCompletion"),
        ("complete the sentences", "sentenceCompletion"),
    ):
        if phrase in lower:
            return kind
    if any(q.get("type") == "SHORT_ANSWER" for q in questions):
        return "shortAnswerInline"
    return "linearTextCompletion"


def _events(title: Tag, wrapper_index: int, expected_numbers: set[int]) -> list[Event]:
    result: list[Event] = []
    token_index = 0
    list_depth = 0

    def visit(node) -> None:
        nonlocal token_index, list_depth
        if isinstance(node, NavigableString):
            raw = re.sub(r"\s+", " ", str(node))
            if raw:
                result.append(Event("text", raw, f"wrapper:{wrapper_index}/text:{token_index}", list_item=list_depth > 0))
                token_index += 1
            return
        if not isinstance(node, Tag):
            return
        classes = node.get("class", [])
        class_text = " ".join(classes) if isinstance(classes, list) else str(classes)
        if node.name in {"script", "style", "img", "svg", "table", "canvas", "iframe"}:
            if node.name in {"img", "svg", "table", "canvas", "iframe"}:
                raise LayoutGenerationIssue("UNSUPPORTED_IMAGE_OR_TABLE_LAYOUT", "unsupported")
            return
        if "style_islogin" in class_text or "answer-con" in class_text:
            return
        if node.has_attr("data-ielts-blank"):
            marker_index = sum(event.kind == "gap" for event in result)
            result.append(Event("gap", str(marker_index), f"wrapper:{wrapper_index}/gap:{marker_index}", list_item=list_depth > 0))
            return
        if node.name == "br":
            result.append(Event("break", "", f"wrapper:{wrapper_index}/break:{token_index}", list_item=list_depth > 0))
            return
        is_list = node.name == "li"
        is_block = node.name == "p"
        if is_list or is_block:
            if result and result[-1].kind != "break":
                result.append(Event("break", "", f"wrapper:{wrapper_index}/block-start:{token_index}", list_item=list_depth > 0))
            if is_list:
                list_depth += 1
        if node.name == "strong":
            label = clean(node.get_text(" ", strip=True))
            if label.isdigit() and int(label) in expected_numbers:
                result.append(Event("qmark", label, f"wrapper:{wrapper_index}/q-label:{token_index}", list_item=list_depth > 0))
                token_index += 1
                if is_list:
                    list_depth -= 1
                if is_list or is_block:
                    result.append(Event("break", "", f"wrapper:{wrapper_index}/block-end:{token_index}", list_item=list_depth > 0))
                return
        for child in node.children:
            visit(child)
        if is_list:
            list_depth -= 1
        if is_list or is_block:
            if result and result[-1].kind != "break":
                result.append(Event("break", "", f"wrapper:{wrapper_index}/block-end:{token_index}", list_item=list_depth > 0))

    for child in title.children:
        visit(child)
    return result


def _tape(events: list[Event]) -> str:
    pieces = []
    for event in events:
        if event.kind in ("text", "qmark"):
            pieces.append(event.value)
        elif event.kind == "gap":
            pieces.append(GAP_TOKEN.format(event.value))
        elif event.kind == "break":
            pieces.append("\n")
    cursor = 0
    for event in events:
        piece = event.value if event.kind in ("text", "qmark") else (
            GAP_TOKEN.format(event.value) if event.kind == "gap" else "\n" if event.kind == "break" else "")
        event.start = cursor
        cursor += len(piece)
        event.end = cursor
    return "".join(pieces)


def _question_anchors(events: list[Event], tape: str, questions: dict[int, dict]) -> tuple[dict[str, list[tuple[Event, int]]], list[tuple[int, int]]]:
    expected = set(questions)
    candidates: list[tuple[int, int, int, bool, Event | None]] = []
    for event in events:
        if event.kind == "qmark":
            number = int(event.value)
            if number in expected:
                candidates.append((event.start, event.end, number, True, event))
        elif event.kind == "text":
            for match in QUESTION_NUMBER.finditer(event.value):
                number = int(match[1])
                if number in expected:
                    candidates.append((event.start + match.start(), event.start + match.end(), number, False, event))
    # The Questions x-y range is a section heading, not an answer-location marker.
    header_end = 0
    for line in tape.splitlines(keepends=True):
        if re.search(r"\bQuestions?\s+\d+\s*[-–—]\s*\d+", line, re.I):
            header_end += len(line)
            break
        header_end += len(line)
    candidates = [candidate for candidate in candidates if candidate[0] >= header_end]
    gap_events = [event for event in events if event.kind == "gap"]
    mapped: dict[str, list[tuple[Event, int]]] = {}
    remove_spans: list[tuple[int, int]] = []
    for gap in gap_events:
        before = [candidate for candidate in candidates if candidate[1] <= gap.start]
        if not before:
            raise LayoutGenerationIssue("GAP_WITHOUT_EXPLICIT_QUESTION_NUMBER", "invalid")
        anchor = max(before, key=lambda item: (item[1], item[3]))
        number = anchor[2]
        question = questions[number]
        question_id = question["id"]
        previous = mapped.get(question_id, [])
        if previous and question.get("answerSeparator") is None:
            raise LayoutGenerationIssue("DUPLICATE_QUESTION_ANCHOR", "invalid")
        if len(previous) >= 2:
            raise LayoutGenerationIssue("TOO_MANY_SLOTS_FOR_QUESTION", "invalid")
        mapped.setdefault(question_id, []).append((gap, number))
        between = tape[anchor[1]:gap.start]
        is_number_label = anchor[3] or re.fullmatch(r"[\s:.)\-]*", between) is not None
        if not is_number_label:
            raise LayoutGenerationIssue("QUESTION_NUMBER_NOT_A_DISPLAY_LABEL", "requiresManualReview")
        remove_spans.append((anchor[0], anchor[1]))
    for question_id, slots in mapped.items():
        expected_count = 2 if questions[slots[0][1]].get("answerSeparator") is not None else 1
        if len(slots) != expected_count:
            raise LayoutGenerationIssue("INCOMPLETE_SPECIAL_ANSWER_SLOTS" if expected_count == 2 else "INCOMPLETE_GROUP", "requiresManualReview")
    return mapped, remove_spans


def _prompt_anchor(question: dict, number: int, slot_index: int) -> tuple[int, int] | None:
    prompt = question.get("prompt", "")
    matches = list(PROMPT_GAP.finditer(prompt))
    related = [match for match in matches if int(match["number"]) == number]
    if slot_index >= len(related):
        return None
    match = related[slot_index]
    return match.start(), match.end()


def _source_ref(prompt: str, value: str, question_id: str, cursor: int, locator: str) -> tuple[dict, int]:
    matches = _all_occurrences(prompt, value)
    candidates = [position for position in matches if position >= cursor]
    if len(matches) == 1 and candidates:
        start = candidates[0]
        end = start + len(value)
        return ({"kind": "prompt", "questionId": question_id,
                 "startUtf16": _utf16_offset(prompt, start), "endUtf16": _utf16_offset(prompt, end)}, end)
    return ({"kind": "html", "resource": "content.html", "locator": locator}, cursor)


def _prompt_anchor_ref(question: dict, number: int, slot_index: int) -> tuple[dict, int | None]:
    location = _prompt_anchor(question, number, slot_index)
    if location is None:
        return ({"kind": "html-marker", "resource": "content.html",
                 "locator": f"question:{number}/slot:{slot_index}"}, None)
    start, end = location
    prompt = question["prompt"]
    return ({"kind": "prompt-anchor", "questionId": question["id"],
             "startUtf16": _utf16_offset(prompt, start), "endUtf16": _utf16_offset(prompt, end)}, end)


def _source_projection(events: list[Event], remove_spans: list[tuple[int, int]]) -> str:
    """Source-visible text after dropping explicit question labels and replacing marked gaps."""
    lines: list[list[dict]] = [[]]
    for event in events:
        if event.kind == "break":
            lines.append([])
        elif event.kind == "gap":
            lines[-1].append({"kind": "blank", "listItem": event.list_item})
        elif event.kind == "qmark":
            if not any(start == event.start and end == event.end for start, end in remove_spans):
                lines[-1].append({"kind": "text", "text": event.value, "listItem": event.list_item})
        elif event.kind == "text":
            cuts = sorted((max(event.start, start), min(event.end, end)) for start, end in remove_spans
                          if start < event.end and end > event.start)
            cursor = 0
            for start, end in cuts:
                local_start, local_end = start - event.start, end - event.start
                if local_start > cursor:
                    lines[-1].append({"kind": "text", "text": event.value[cursor:local_start],
                                      "listItem": event.list_item})
                cursor = max(cursor, local_end)
            if cursor < len(event.value):
                lines[-1].append({"kind": "text", "text": event.value[cursor:], "listItem": event.list_item})

    rendered_lines = []
    for line in lines:
        merged: list[dict] = []
        for item in line:
            if item["kind"] == "text" and merged and merged[-1]["kind"] == "text":
                merged[-1]["text"] += item["text"]
                merged[-1]["listItem"] = merged[-1]["listItem"] or item["listItem"]
            else:
                merged.append(dict(item))
        while merged and merged[0]["kind"] == "text" and not merged[0]["text"].strip():
            merged.pop(0)
        while merged and merged[-1]["kind"] == "text" and not merged[-1]["text"].strip():
            merged.pop()
        content = []
        for item in merged:
            if item["kind"] == "blank":
                content.append("\uFFFC")
            else:
                value = re.sub(r"[ \t\r\n]+", " ", item["text"])
                if value:
                    content.append(value)
        marker = "• " if any(item.get("listItem") for item in merged) else ""
        rendered_lines.append(marker + "".join(content))
    return "\n".join(rendered_lines).rstrip("\n")


def _layout_projection(blocks: list[dict]) -> str:
    pieces = []
    for block in blocks:
        kind = block["type"]
        if kind in {"text", "fixedText"}:
            pieces.append(block["text"])
        elif kind == "blank":
            pieces.append("\uFFFC")
        elif kind == "lineBreak":
            pieces.append("\n")
        elif kind == "listItem":
            pieces.append((block.get("marker") or "•") + " " + _layout_projection(block["children"]))
        elif kind == "formRow" and block.get("label"):
            pieces.append(block["label"] + " " + _layout_projection(block["children"]))
        elif kind in {"paragraph", "listItem", "formRow"}:
            pieces.append(_layout_projection(block["children"]))
        else:
            raise LayoutGenerationIssue("UNSUPPORTED_BLOCK_TYPE", "unsupported")
    return "".join(pieces).rstrip("\n")


def _make_group(events: list[Event], tape: str, wrapper_index: int, part: dict,
                html_sha: str) -> tuple[dict, dict]:
    by_number = {question["number"]: question for question in part["questions"]}
    mapped, remove_spans = _question_anchors(events, tape, by_number)
    ordered = []
    for event in events:
        if event.kind == "gap":
            number = next(number for values in mapped.values() for gap, number in values if gap is event)
            question = by_number[number]
            question_id = question["id"]
            slot_index = next(index for index, (gap, _) in enumerate(mapped[question_id]) if gap is event)
            if slot_index == 0:
                ordered.append({"kind": "flush"})
            source, prompt_end = _prompt_anchor_ref(question, number, slot_index)
            ordered.append({"kind": "blank", "questionId": question_id, "questionNumber": number,
                            "slotIndex": slot_index, "source": source, "promptEnd": prompt_end,
                            "locator": event.locator})
            continue
        if event.kind == "break":
            ordered.append({"kind": "break", "locator": event.locator})
            continue
        if event.kind == "qmark":
            if any(start == event.start and end == event.end for start, end in remove_spans):
                continue
            ordered.append({"kind": "text", "text": event.value, "locator": event.locator,
                            "listItem": event.list_item})
            continue
        if event.kind == "text":
            cuts = [(max(event.start, start), min(event.end, end)) for start, end in remove_spans
                    if start < event.end and end > event.start]
            cuts.sort()
            local_cursor = event.start
            for start, end in cuts:
                if start > local_cursor:
                    ordered.append({"kind": "text", "text": tape[local_cursor:start],
                                    "locator": event.locator, "listItem": event.list_item})
                local_cursor = max(local_cursor, end)
            if local_cursor < event.end:
                ordered.append({"kind": "text", "text": tape[local_cursor:event.end],
                                "locator": event.locator, "listItem": event.list_item})

    first_number = min(number for values in mapped.values() for _, number in values)
    last_number = max(number for values in mapped.values() for _, number in values)
    question_ids = [question["id"] for question in part["questions"] if question["id"] in mapped]
    question_list = [next(q for q in part["questions"] if q["id"] == question_id) for question_id in question_ids]
    title_text = clean(re.sub(r"\ue000GAP:\d+\ue001", " ", tape))
    kind = _layout_kind(title_text, question_list)
    if any(q.get("type") not in SUPPORTED_TYPES for q in question_list):
        raise LayoutGenerationIssue("UNSUPPORTED_QUESTION_TYPE", "unsupported")
    prompt = question_list[0]["prompt"]
    prompt_id = question_list[0]["id"]
    prompt_cursor = 0
    line: list[dict] = []
    blocks: list[dict] = []

    def flush_line() -> None:
        nonlocal prompt_cursor, line
        # Merge adjacent text nodes so that inline HTML element boundaries do not split words.
        merged: list[dict] = []
        for item in line:
            if item["kind"] == "text" and merged and merged[-1]["kind"] == "text":
                merged[-1]["text"] += item["text"]
                merged[-1]["locator"] += "+" + item["locator"]
                merged[-1]["listItem"] = merged[-1]["listItem"] or item["listItem"]
            else:
                merged.append(item)
        while merged and merged[0]["kind"] == "text" and not merged[0]["text"].strip():
            merged.pop(0)
        while merged and merged[-1]["kind"] == "text" and not merged[-1]["text"].strip():
            merged.pop()
        children = []
        for item in merged:
            if item["kind"] == "blank":
                children.append({"type": "blank", "questionId": item["questionId"],
                                 "questionNumber": item["questionNumber"], "slotIndex": item["slotIndex"],
                                 "source": item["source"]})
                if item["promptEnd"] is not None:
                    prompt_cursor = max(prompt_cursor, item["promptEnd"])
                continue
            value = re.sub(r"[ \t\r\n]+", " ", item["text"])
            if not value:
                continue
            # A source connector between a declared pair of slots is fixed display text.
            between_pair = any(
                q.get("answerSeparator") and len(mapped.get(q["id"], [])) == 2
                and value.strip().lower() == q["answerSeparator"]
                for q in question_list
            )
            ref, prompt_cursor = _source_ref(prompt, value, prompt_id, prompt_cursor, item["locator"])
            children.append({"type": "fixedText" if between_pair else "text", "text": value, "source": ref})
        if children:
            if kind == "formCompletion":
                blocks.append({"type": "formRow", "children": children})
            elif any(item.get("listItem") for item in merged):
                blocks.append({"type": "listItem", "marker": "•", "children": children})
            else:
                # Every rendered paragraph/line remains a separate structural block.
                blocks.append({"type": "paragraph", "children": children})
        line = []

    for item in ordered:
        if item["kind"] == "flush":
            continue
        if item["kind"] == "text":
            line.append(item)
        elif item["kind"] == "blank":
            line.append(item)
        elif item["kind"] == "break":
            flush_line()
            if blocks and blocks[-1].get("type") == "lineBreak":
                blocks.append({"type": "lineBreak"})
            else:
                blocks.append({"type": "lineBreak"})
    flush_line()
    while blocks and blocks[-1].get("type") == "lineBreak":
        blocks.pop()

    group_id = f"{part['id']}-layout-{kind.lower()}-q{first_number}-q{last_number}"
    group = {
        "groupId": group_id,
        "layoutKind": kind,
        "questionIds": question_ids,
        "promptHashes": {q["id"]: _sha(q["prompt"].encode("utf-8")) for q in question_list},
        "source": {"resource": "content.html", "sha256": html_sha,
                   "locator": f"wrapper:{wrapper_index}/questions:{first_number}-{last_number}",
                   "sourceKind": "data-ielts-blank"},
        "blocks": blocks,
    }
    if _source_projection(events, remove_spans) != _layout_projection(blocks):
        raise LayoutGenerationIssue("SOURCE_RENDER_MISMATCH", "requiresManualReview")
    status = {"groupId": group_id, "status": "converted", "questionNumbers": [q["number"] for q in question_list],
              "sourceProjectionMatches": True}
    return group, status


def generate_layout(part_json_bytes: bytes, content_html_bytes: bytes, *,
                    question_range: tuple[int, int] | None = None) -> tuple[dict, list[dict]]:
    """Build a Schema v1 document and per-group status from one source HTML + Part JSON."""
    if len(part_json_bytes) > MAX_HTML_BYTES:
        raise LayoutGenerationIssue("PART_JSON_TOO_LARGE", "invalid")
    if len(content_html_bytes) > MAX_HTML_BYTES:
        raise LayoutGenerationIssue("SOURCE_HTML_TOO_LARGE", "invalid")
    try:
        part = json.loads(part_json_bytes.decode("utf-8-sig"))
        html_text = decode_source_text(content_html_bytes)
    except (UnicodeError, json.JSONDecodeError, ValueError) as error:
        raise LayoutGenerationIssue("INVALID_INPUT_ENCODING_OR_JSON", "invalid") from error
    if not isinstance(part, dict) or not isinstance(part.get("questions"), list):
        raise LayoutGenerationIssue("INVALID_PART_JSON", "invalid")
    if len(html_text) > MAX_HTML_CHARS:
        raise LayoutGenerationIssue("SOURCE_HTML_TOO_LARGE", "invalid")
    soup = BeautifulSoup(html_text, "html.parser")
    if soup.select_one("section.questions") is None:
        raise LayoutGenerationIssue("MISSING_QUESTIONS_SECTION", "invalid")
    by_number = {q.get("number"): q for q in part["questions"] if isinstance(q, dict)}
    if len(by_number) != len(part["questions"]):
        raise LayoutGenerationIssue("INVALID_PART_QUESTION_NUMBERS", "invalid")
    expected = set(by_number)
    html_sha = _sha(content_html_bytes)
    groups: list[dict] = []
    statuses: list[dict] = []
    wrappers = soup.select("section.questions #analys-wrap-container > div")
    for wrapper_index, wrapper in enumerate(wrappers):
        outer = wrapper.find("div", recursive=False)
        title = outer.find("div", recursive=False) if outer else None
        if title is None:
            continue
        if not title.select("[data-ielts-blank]"):
            continue
        try:
            events = _events(title, wrapper_index, expected)
            tape = _tape(events)
            mapped, _ = _question_anchors(events, tape, by_number)
            numbers = sorted(number for values in mapped.values() for _, number in values)
            if question_range is not None and (min(numbers), max(numbers)) != question_range:
                continue
            group, status = _make_group(events, tape, wrapper_index, part, html_sha)
            groups.append(group)
            statuses.append(status)
        except LayoutGenerationIssue as issue:
            statuses.append({"groupId": f"{part['id']}-layout-wrapper-{wrapper_index}",
                             "status": issue.status, "reasonCode": issue.reason_code})
    layout = {"schemaVersion": 1, "partId": part["id"],
              "basePartSha256": _sha(part_json_bytes), "groups": groups}
    if groups:
        try:
            validate_layout(layout, part, part_json_bytes=part_json_bytes, source_html_bytes=content_html_bytes)
        except LayoutValidationError as issue:
            # A generator/validator disagreement is visible as invalid, never success.
            statuses = [{"groupId": g["groupId"], "status": "invalid", "reasonCode": issue.reason_code}
                        for g in groups]
            layout["groups"] = []
    if not statuses:
        statuses.append({"groupId": f"{part['id']}-layout-no-groups", "status": "unsupported",
                         "reasonCode": "NO_EXPLICIT_TEXT_GAP_GROUP"})
    return layout, statuses


def encode_layout(layout: dict) -> bytes:
    """Stable UTF-8 serialization used by prototype packs and output hash tests."""
    return (json.dumps(layout, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
