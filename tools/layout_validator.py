"""Strict, dependency-free validation for the private-independent layout JSON contract."""
from __future__ import annotations

import hashlib
import json
import re


SHA256 = re.compile(r"^[a-f0-9]{64}$")
PART_ID = re.compile(r"^cambridge-(?:[5-9]|1[0-9]|2[01])-test-[1-4]-part-[1-4]$")
GROUP_ID = re.compile(r"^[a-z0-9-]+-layout-[a-z0-9-]{1,80}$")
LAYOUT_KINDS = {
    "summaryCompletion", "notesCompletion", "formCompletion",
    "sentenceCompletion", "linearTextCompletion", "shortAnswerInline",
}
SUPPORTED_QUESTION_TYPES = {"TEXT_INPUT", "NOTE_COMPLETION", "SHORT_ANSWER", "TABLE_COMPLETION", "FLOW_COMPLETION"}
MAX_DOCUMENT_BYTES = 4_000_000
MAX_GROUPS = 64
MAX_BLOCKS = 512
MAX_TOTAL_BLOCKS = 2_048
MAX_DEPTH = 6
MAX_TEXT_UTF16 = 100_000
MAX_TOTAL_TEXT_UTF16 = 1_000_000
MAX_LOCATOR = 512


class LayoutValidationError(ValueError):
    """A stable, non-content-bearing reason code for invalid layout data."""

    def __init__(self, reason_code: str):
        super().__init__(reason_code)
        self.reason_code = reason_code


def _require(condition: bool, reason_code: str) -> None:
    if not condition:
        raise LayoutValidationError(reason_code)


def _keys(value: dict, required: set[str], optional: set[str] = frozenset()) -> None:
    _require(required <= value.keys() and value.keys() <= required | optional, "UNKNOWN_OR_MISSING_FIELD")


def _utf16_length(value: str) -> int:
    try:
        return len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError as error:
        raise LayoutValidationError("INVALID_UNICODE") from error


def _utf16_slice(value: str, start: int, end: int) -> str | None:
    if start < 0 or end < start:
        return None
    try:
        raw = value.encode("utf-16-le")
    except UnicodeEncodeError:
        return None
    if end * 2 > len(raw):
        return None
    try:
        return raw[start * 2:end * 2].decode("utf-16-le")
    except UnicodeDecodeError:
        # An offset that bisects a supplementary character is never a valid source range.
        return None


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def validate_layout(layout: object, part: dict, *, part_json_bytes: bytes | None = None,
                    source_html_bytes: bytes | None = None, declared_images: set[str] | None = None) -> dict:
    """Validate a decoded layout and its cross-references to one canonical Part.

    Offsets are UTF-16 code units, matching Android String/TextView offsets. The validator
    intentionally does not infer answer positions from question order or answer text.
    """
    _require(isinstance(layout, dict), "LAYOUT_NOT_OBJECT")
    try:
        document_size = len(json.dumps(layout, ensure_ascii=True, separators=(",", ":")).encode("ascii"))
    except (TypeError, ValueError, RecursionError) as error:
        raise LayoutValidationError("INVALID_LAYOUT_ENCODING") from error
    _require(document_size <= MAX_DOCUMENT_BYTES, "LAYOUT_TOO_LARGE")
    _keys(layout, {"schemaVersion", "partId", "basePartSha256", "groups"})
    _require(isinstance(layout["schemaVersion"], int) and not isinstance(layout["schemaVersion"], bool)
             and layout["schemaVersion"] == 1, "UNSUPPORTED_LAYOUT_SCHEMA")
    _require(isinstance(layout["partId"], str) and PART_ID.fullmatch(layout["partId"]), "INVALID_PART_ID")
    _require(layout["partId"] == part.get("id"), "PART_ID_MISMATCH")
    _require(isinstance(layout["basePartSha256"], str) and SHA256.fullmatch(layout["basePartSha256"]),
             "INVALID_BASE_PART_HASH")
    if part_json_bytes is not None:
        _require(sha256(part_json_bytes) == layout["basePartSha256"], "BASE_PART_HASH_MISMATCH")
    questions = part.get("questions")
    _require(isinstance(questions, list) and questions
             and all(isinstance(question, dict) for question in questions), "INVALID_PART_QUESTIONS")
    _require(all(isinstance(question.get("id"), str) and question["id"]
                 and isinstance(question.get("number"), int) and not isinstance(question["number"], bool)
                 for question in questions), "INVALID_PART_QUESTION_MAP")
    by_id = {question["id"]: question for question in questions}
    by_number = {question["number"]: question for question in questions}
    _require(len(by_id) == len(questions) == len(by_number), "INVALID_PART_QUESTION_MAP")
    groups = layout["groups"]
    _require(isinstance(groups, list) and len(groups) <= MAX_GROUPS, "INVALID_GROUPS")
    if source_html_bytes is not None:
        _require(all(isinstance(g, dict) and isinstance(g.get("source"), dict)
                    and g["source"].get("sha256") == sha256(source_html_bytes) for g in groups),
                 "SOURCE_HTML_HASH_MISMATCH")

    seen_group_ids: set[str] = set()
    seen_questions: set[str] = set()
    total_blocks = total_text = 0

    for group in groups:
        _require(isinstance(group, dict), "INVALID_GROUP")
        _keys(group, {"groupId", "layoutKind", "questionIds", "promptHashes", "source", "blocks"})
        group_id = group["groupId"]
        _require(isinstance(group_id, str) and GROUP_ID.fullmatch(group_id), "INVALID_GROUP_ID")
        _require(group_id.startswith(layout["partId"] + "-layout-"), "GROUP_ID_PART_MISMATCH")
        _require(group_id not in seen_group_ids, "DUPLICATE_GROUP_ID")
        seen_group_ids.add(group_id)
        _require(isinstance(group["layoutKind"], str) and group["layoutKind"] in LAYOUT_KINDS,
                 "UNSUPPORTED_LAYOUT_KIND")
        question_ids = group["questionIds"]
        _require(isinstance(question_ids, list) and 1 <= len(question_ids) <= 10
                 and all(isinstance(item, str) for item in question_ids), "INVALID_GROUP_QUESTIONS")
        _require(len(set(question_ids)) == len(question_ids), "DUPLICATE_GROUP_QUESTION")
        _require(not (set(question_ids) & seen_questions), "QUESTION_IN_MULTIPLE_GROUPS")
        _require(all(item in by_id for item in question_ids), "UNKNOWN_QUESTION_ID")
        seen_questions.update(question_ids)
        prompt_hashes = group["promptHashes"]
        _require(isinstance(prompt_hashes, dict) and set(prompt_hashes) == set(question_ids), "PROMPT_HASH_SET_MISMATCH")
        for question_id in question_ids:
            question = by_id[question_id]
            images = question.get("images", [])
            _require(isinstance(question.get("type"), str) and question["type"] in SUPPORTED_QUESTION_TYPES
                     and isinstance(images, list) and not images, "UNSUPPORTED_QUESTION_TYPE")
            prompt = question.get("prompt")
            _require(isinstance(prompt, str) and prompt_hashes[question_id] == sha256(prompt.encode("utf-8")),
                     "PROMPT_HASH_MISMATCH")

        source = group["source"]
        _require(isinstance(source, dict), "INVALID_SOURCE_TRACE")
        _keys(source, {"resource", "sha256", "locator", "sourceKind"})
        _require(source["resource"] == "content.html", "INVALID_SOURCE_RESOURCE")
        _require(isinstance(source["sha256"], str) and SHA256.fullmatch(source["sha256"]), "INVALID_SOURCE_HASH")
        _require(isinstance(source["locator"], str) and 1 <= len(source["locator"]) <= MAX_LOCATOR,
                 "INVALID_SOURCE_LOCATOR")
        _require(isinstance(source["sourceKind"], str) and 1 <= len(source["sourceKind"]) <= 64,
                 "INVALID_SOURCE_KIND")

        blocks = group["blocks"]
        _require(isinstance(blocks, list) and 1 <= len(blocks) <= MAX_BLOCKS, "INVALID_BLOCKS")
        counts: dict[str, list[tuple[int, int]]] = {question_id: [] for question_id in question_ids}
        group_block_count = 0

        def visit(items: object, depth: int) -> None:
            nonlocal total_blocks, total_text, group_block_count
            _require(isinstance(items, list), "INVALID_BLOCK_CHILDREN")
            _require(depth <= MAX_DEPTH, "LAYOUT_NESTING_TOO_DEEP")
            for block in items:
                _require(isinstance(block, dict) and isinstance(block.get("type"), str), "INVALID_BLOCK")
                group_block_count += 1
                total_blocks += 1
                _require(group_block_count <= MAX_BLOCKS and total_blocks <= MAX_TOTAL_BLOCKS, "TOO_MANY_BLOCKS")
                kind = block["type"]
                if kind in ("text", "fixedText"):
                    _keys(block, {"type", "text", "source"})
                    value = block["text"]
                    _require(isinstance(value, str) and 1 <= _utf16_length(value) <= MAX_TEXT_UTF16,
                             "INVALID_TEXT_BLOCK")
                    total_text += _utf16_length(value)
                    _require(total_text <= MAX_TOTAL_TEXT_UTF16, "LAYOUT_TEXT_TOO_LARGE")
                    ref = block["source"]
                    _require(isinstance(ref, dict) and isinstance(ref.get("kind"), str), "INVALID_TEXT_SOURCE")
                    if ref["kind"] == "prompt":
                        _keys(ref, {"kind", "questionId", "startUtf16", "endUtf16"})
                        question_id = ref["questionId"]
                        _require(isinstance(question_id, str) and question_id in question_ids,
                                 "SOURCE_QUESTION_OUTSIDE_GROUP")
                        start, end = ref["startUtf16"], ref["endUtf16"]
                        prompt = by_id[question_id]["prompt"]
                        _require(isinstance(start, int) and not isinstance(start, bool)
                                 and isinstance(end, int) and not isinstance(end, bool), "INVALID_SOURCE_OFFSET")
                        _require(end - start == _utf16_length(value) and _utf16_slice(prompt, start, end) == value,
                                 "SOURCE_TEXT_MISMATCH")
                    elif ref["kind"] == "html":
                        _keys(ref, {"kind", "resource", "locator"})
                        _require(ref["resource"] == "content.html" and isinstance(ref["locator"], str)
                                 and 1 <= len(ref["locator"]) <= MAX_LOCATOR, "INVALID_HTML_TEXT_SOURCE")
                    else:
                        raise LayoutValidationError("UNSUPPORTED_TEXT_SOURCE")
                elif kind == "blank":
                    _keys(block, {"type", "questionId", "questionNumber", "slotIndex", "source"})
                    question_id = block["questionId"]
                    _require(isinstance(question_id, str) and question_id in question_ids,
                             "BLANK_QUESTION_OUTSIDE_GROUP")
                    question = by_id[question_id]
                    question_number = block["questionNumber"]
                    _require(isinstance(question_number, int) and not isinstance(question_number, bool)
                             and question_number == question.get("number"), "QUESTION_NUMBER_MISMATCH")
                    slot = block["slotIndex"]
                    _require(isinstance(slot, int) and not isinstance(slot, bool) and 0 <= slot <= 1,
                             "INVALID_SLOT_INDEX")
                    ref = block["source"]
                    _require(isinstance(ref, dict) and isinstance(ref.get("kind"), str), "INVALID_BLANK_SOURCE")
                    if ref["kind"] == "prompt-anchor":
                        _keys(ref, {"kind", "questionId", "startUtf16", "endUtf16"})
                        _require(ref["questionId"] == question_id, "BLANK_SOURCE_QUESTION_MISMATCH")
                        start, end = ref["startUtf16"], ref["endUtf16"]
                        prompt = question["prompt"]
                        _require(isinstance(start, int) and not isinstance(start, bool)
                                 and isinstance(end, int) and not isinstance(end, bool), "INVALID_SOURCE_OFFSET")
                        anchor = _utf16_slice(prompt, start, end)
                        expected = re.compile(
                            rf"(?<![\w]){question_number}[ \t\r\n]*(?:[.:][ \t\r\n]*)?(?:_{{2,}}|[＿﹍]{{2,}})(?![\w])"
                        )
                        _require(anchor is not None and expected.search(anchor) is not None,
                                 "INVALID_PROMPT_GAP_ANCHOR")
                    elif ref["kind"] == "html-marker":
                        _keys(ref, {"kind", "resource", "locator"})
                        _require(ref["resource"] == "content.html" and isinstance(ref["locator"], str)
                                 and 1 <= len(ref["locator"]) <= MAX_LOCATOR, "INVALID_HTML_GAP_SOURCE")
                    else:
                        raise LayoutValidationError("UNSUPPORTED_BLANK_SOURCE")
                    counts[question_id].append((slot, question["number"]))
                elif kind == "lineBreak":
                    _keys(block, {"type"})
                elif kind in ("paragraph", "listItem", "formRow"):
                    if kind == "listItem":
                        _keys(block, {"type", "children"}, {"marker"})
                        if "marker" in block:
                            _require(block["marker"] in {"•", "●", "▪", "-", "*"}, "INVALID_LIST_MARKER")
                    else:
                        _keys(block, {"type", "children"}, {"label"} if kind == "formRow" else frozenset())
                    if "label" in block:
                        _require(isinstance(block["label"], str) and len(block["label"]) <= 2000,
                                 "INVALID_FORM_LABEL")
                    _require(isinstance(block["children"], list) and block["children"], "EMPTY_STRUCTURED_BLOCK")
                    visit(block["children"], depth + 1)
                else:
                    # Schema v1 has no image/table/flow positioning. Unknown blocks fall back.
                    raise LayoutValidationError("UNSUPPORTED_BLOCK_TYPE")

        visit(blocks, 1)
        _require(group_block_count <= MAX_BLOCKS, "TOO_MANY_BLOCKS")
        for question_id in question_ids:
            question = by_id[question_id]
            slots = sorted(slot for slot, _ in counts[question_id])
            expected_count = 2 if question.get("answerSeparator") is not None else 1
            _require(len(slots) == expected_count, "BLANK_COUNT_MISMATCH")
            _require(slots == list(range(expected_count)), "SLOT_INDEX_GAP_OR_DUPLICATE")
    return layout
