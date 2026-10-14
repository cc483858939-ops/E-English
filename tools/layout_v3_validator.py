"""Dependency-free offline validator for the frozen layout.json v3 contract."""
from __future__ import annotations

import hashlib
from html.parser import HTMLParser
import json
import re
import unicodedata
from typing import Any


ID_RE = re.compile(r"^[a-z][a-z0-9-]{0,79}$")
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")
PART_ID_RE = re.compile(r"^[a-z0-9-]+$")
MAX_DOCUMENT_BYTES = 4_000_000
MAX_GROUPS = 64
MAX_GROUP_QUESTIONS = 10
MAX_GROUP_SLOTS = 32
MAX_GROUP_RESPONSES = 32
MAX_GROUP_POOLS = 32
MAX_GROUP_INSTRUCTIONS = 32
MAX_GROUP_BLOCKS = 512
MAX_DOCUMENT_BLOCKS = 2_048
MAX_NESTING_DEPTH = 8
MAX_TEXT_UTF16 = 100_000
MAX_TOTAL_TEXT_UTF16 = 1_000_000
MAX_TABLE_ROWS = 64
MAX_TABLE_CELLS = 64
MAX_FLOW_NODES = 64
MAX_FLOW_EDGES = 128
MAX_IMAGE_OVERLAYS = 128
MAX_IMAGE_CONNECTORS = 128
RESOURCE_NAMES = {"content.html", "content.txt"}
QUESTION_TYPES = {
    "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TEXT_INPUT", "MATCHING", "IMAGE_BASED",
    "SHORT_ANSWER", "TABLE_COMPLETION", "NOTE_COMPLETION", "FLOW_COMPLETION",
}
SLOT_KINDS = {"fill", "singleChoice", "multipleChoice", "matching"}
SOURCE_FIELDS = {"part.instructions", "question.prompt", "question.instructions", "question.context"}
RESPONSE_KEYS = {
    "fill": {"kind", "questionIds", "slotIds", "scoring"},
    "singleChoice": {"kind", "questionIds", "slotIds", "scoring", "optionPoolId"},
    "matching": {"kind", "questionIds", "slotIds", "scoring", "optionPoolId", "reuse"},
    "multipleChoice": {
        "kind", "questionIds", "slotIds", "scoring", "optionPoolId", "selectCount", "order", "storage",
    },
}
BLOCK_TYPES = {"text", "paragraph", "listItem", "formRow", "table", "flow", "image", "slot", "choiceList"}


class LayoutV3ValidationError(ValueError):
    """A stable reason code without answer or source content."""

    def __init__(self, reason_code: str):
        super().__init__(reason_code)
        self.reason_code = reason_code


def _fail(reason_code: str) -> None:
    raise LayoutV3ValidationError(reason_code)


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_nonempty_string(value: object) -> bool:
    return isinstance(value, str) and bool(value)


def _require(condition: bool, reason_code: str = "INVALID_LAYOUT_SHAPE") -> None:
    if not condition:
        _fail(reason_code)


def _object(value: object) -> dict[str, Any]:
    _require(isinstance(value, dict))
    return value


def _exact(value: object, required: set[str], optional: set[str] | frozenset[str] = frozenset()) -> dict[str, Any]:
    obj = _object(value)
    allowed = required | set(optional)
    if any(key not in allowed for key in obj):
        _fail("UNKNOWN_FIELD")
    if not required <= obj.keys():
        _fail("INVALID_LAYOUT_SHAPE")
    return obj


def _strict_json(raw: bytes, reason_code: str) -> object:
    if not isinstance(raw, bytes):
        _fail(reason_code)

    def pairs(items: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def reject_constant(_: str) -> object:
        raise ValueError("non-standard JSON constant")

    def check_unicode(value: object) -> None:
        if isinstance(value, str):
            value.encode("utf-8", errors="strict")
        elif isinstance(value, list):
            for item in value:
                check_unicode(item)
        elif isinstance(value, dict):
            for key, item in value.items():
                key.encode("utf-8", errors="strict")
                check_unicode(item)

    try:
        text = raw.decode("utf-8", errors="strict")
        parsed = json.loads(text, object_pairs_hook=pairs, parse_constant=reject_constant)
        check_unicode(parsed)
        return parsed
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError, RecursionError):
        _fail(reason_code)


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _utf16_length(value: str) -> int:
    try:
        return len(value.encode("utf-16-le", errors="strict")) // 2
    except UnicodeEncodeError:
        _fail("INVALID_LAYOUT_SHAPE")


def _utf16_slice(value: str, start: int, end: int) -> str | None:
    if start < 0 or end < start:
        return None
    try:
        raw = value.encode("utf-16-le", errors="strict")
        if end * 2 > len(raw):
            return None
        return raw[start * 2:end * 2].decode("utf-16-le", errors="strict")
    except UnicodeError:
        return None


def _string_list(value: object, *, minimum: int = 0, maximum: int | None = None) -> list[str]:
    _require(isinstance(value, list) and len(value) >= minimum
             and (maximum is None or len(value) <= maximum)
             and all(_is_nonempty_string(item) for item in value))
    return value


def _check_id(value: object) -> None:
    _require(isinstance(value, str) and ID_RE.fullmatch(value) is not None)


def _check_sha(value: object) -> None:
    _require(isinstance(value, str) and SHA256_RE.fullmatch(value) is not None)


def _shape_source(value: object) -> None:
    source = _object(value)
    kind = source.get("kind")
    if kind == "partField":
        field = source.get("field")
        _require(isinstance(field, str) and field in SOURCE_FIELDS)
        required = {"kind", "field", "startUtf16", "endUtf16"}
        if field != "part.instructions":
            required.add("questionId")
        _exact(source, required)
        if "questionId" in source:
            _require(_is_nonempty_string(source["questionId"]))
        _require(_is_int(source["startUtf16"]) and source["startUtf16"] >= 0)
        _require(_is_int(source["endUtf16"]) and source["endUtf16"] >= 0)
        return
    if kind == "resourceRange":
        _exact(source, {"kind", "resource", "sha256", "normalization", "startUtf16", "endUtf16"})
        _require(isinstance(source["resource"], str) and source["resource"] in RESOURCE_NAMES)
        _check_sha(source["sha256"])
        _require(isinstance(source["normalization"], str)
                 and source["normalization"] in {"htmlTextV1", "rawTextV1"})
        _require((source["resource"] == "content.html" and source["normalization"] == "htmlTextV1")
                 or (source["resource"] == "content.txt" and source["normalization"] == "rawTextV1"))
        _require(_is_int(source["startUtf16"]) and source["startUtf16"] >= 0)
        _require(_is_int(source["endUtf16"]) and source["endUtf16"] >= 0)
        return
    _fail("INVALID_LAYOUT_SHAPE")


def _shape_text(value: object, totals: dict[str, int], *, allow_connector: bool = True) -> None:
    candidate = _object(value)
    connector_keys = {"role", "questionId"}
    has_connector_field = bool(candidate.keys() & connector_keys)
    if has_connector_field and allow_connector:
        text = _exact(value, {"id", "type", "textId", "text", "source", "role", "questionId"})
        _require(text["role"] == "answerConnector" and _is_nonempty_string(text["questionId"]))
    else:
        text = _exact(value, {"id", "type", "textId", "text", "source"})
    _check_id(text["id"])
    _require(text["type"] == "text")
    _check_id(text["textId"])
    _require(_is_nonempty_string(text["text"]))
    length = _utf16_length(text["text"])
    _require(1 <= length <= MAX_TEXT_UTF16)
    totals["text"] += length
    _shape_source(text["source"])


def _shape_rect(value: object) -> dict[str, Any]:
    rect = _exact(value, {"x", "y", "width", "height"})
    _require(_is_int(rect["x"]) and rect["x"] >= 0)
    _require(_is_int(rect["y"]) and rect["y"] >= 0)
    _require(_is_int(rect["width"]) and rect["width"] >= 1)
    _require(_is_int(rect["height"]) and rect["height"] >= 1)
    return rect


def _shape_option_pool(value: object) -> dict[str, Any]:
    pool = _exact(value, {"poolId", "questionIds", "optionIds"})
    _check_id(pool["poolId"])
    _string_list(pool["questionIds"], minimum=1, maximum=MAX_GROUP_QUESTIONS)
    _string_list(pool["optionIds"], minimum=1)
    _require(len(set(pool["optionIds"])) == len(pool["optionIds"]))
    return pool


def _shape_slot(value: object) -> dict[str, Any]:
    slot = _exact(value, {"slotId", "questionId", "slotIndex", "kind"})
    _check_id(slot["slotId"])
    _require(_is_nonempty_string(slot["questionId"]))
    _require(_is_int(slot["slotIndex"]) and slot["slotIndex"] >= 0)
    _require(isinstance(slot["kind"], str) and slot["kind"] in SLOT_KINDS)
    return slot


def _shape_response(value: object) -> dict[str, Any]:
    response = _object(value)
    kind = response.get("kind")
    _require(isinstance(kind, str) and kind in RESPONSE_KEYS)
    if kind == "fill":
        has_joiner = "joiner" in response
        _exact(response, RESPONSE_KEYS[kind] | ({"joiner"} if has_joiner else set()))
    else:
        _exact(response, RESPONSE_KEYS[kind])
    _string_list(response["questionIds"], minimum=1, maximum=MAX_GROUP_QUESTIONS)
    _string_list(response["slotIds"], minimum=1, maximum=MAX_GROUP_SLOTS)
    _require(isinstance(response["scoring"], str))
    if kind == "fill":
        if "joiner" in response:
            _require(response["joiner"] in {"and", "or", "to"})
            _require(len(response["slotIds"]) == 2)
        else:
            _require(len(response["slotIds"]) == 1)
    if kind in {"singleChoice", "matching", "multipleChoice"}:
        _check_id(response["optionPoolId"])
    if kind == "matching":
        _require(isinstance(response["reuse"], str) and response["reuse"] in {"allowed", "forbidden"})
    if kind == "multipleChoice":
        _require(_is_int(response["selectCount"]) and response["selectCount"] >= 1)
        _require(response["order"] == "unordered")
        _require(response["storage"] == "sameSortedSetOnEveryQuestion")
    return response


def _shape_block(value: object, depth: int, totals: dict[str, int]) -> None:
    _require(depth <= MAX_NESTING_DEPTH)
    block = _object(value)
    kind = block.get("type")
    _require(isinstance(kind, str) and kind in BLOCK_TYPES)

    if kind == "text":
        _shape_text(block, totals)
        return
    if kind in {"paragraph", "formRow"}:
        block = _exact(block, {"id", "type", "children"})
        _check_id(block["id"])
        _require(block["type"] == kind)
        children = block["children"]
        _require(isinstance(children, list) and 1 <= len(children) <= MAX_GROUP_BLOCKS)
        for child in children:
            _shape_block(child, depth + 1, totals)
        return
    if kind == "listItem":
        block = _exact(block, {"id", "type", "marker", "children"})
        _check_id(block["id"])
        _require(block["type"] == "listItem" and isinstance(block["marker"], str)
                 and block["marker"] in {"bullet", "numbered", "none"})
        children = block["children"]
        _require(isinstance(children, list) and 1 <= len(children) <= MAX_GROUP_BLOCKS)
        for child in children:
            _shape_block(child, depth + 1, totals)
        return
    if kind == "slot":
        block = _exact(block, {"id", "type", "slotId"})
        _check_id(block["id"])
        _check_id(block["slotId"])
        return
    if kind == "choiceList":
        block = _exact(block, {"id", "type", "optionPoolId", "slotIds"})
        _check_id(block["id"])
        _check_id(block["optionPoolId"])
        _string_list(block["slotIds"], minimum=1, maximum=MAX_GROUP_SLOTS)
        return
    if kind == "image":
        block = _exact(block, {"id", "type", "asset", "widthPx", "heightPx", "overlays", "printedConnectors"})
        _check_id(block["id"])
        _require(_is_nonempty_string(block["asset"]))
        _require(_is_int(block["widthPx"]) and block["widthPx"] >= 1)
        _require(_is_int(block["heightPx"]) and block["heightPx"] >= 1)
        overlays = block["overlays"]
        _require(isinstance(overlays, list) and len(overlays) <= MAX_IMAGE_OVERLAYS)
        for overlay in overlays:
            overlay = _exact(overlay, {"slotId", "rectPx"}, {"evidence"})
            _check_id(overlay["slotId"])
            _shape_rect(overlay["rectPx"])
            if "evidence" in overlay:
                evidence = _exact(overlay["evidence"], {"kind", "reviewId", "imageSha256"})
                _require(evidence["kind"] == "reviewedMeasurement")
                _check_id(evidence["reviewId"])
                _check_sha(evidence["imageSha256"])
        connectors = block["printedConnectors"]
        _require(isinstance(connectors, list) and len(connectors) <= MAX_IMAGE_CONNECTORS)
        for connector in connectors:
            connector = _exact(connector, {"questionId", "text", "rectPx"}, {"evidence"})
            _require(_is_nonempty_string(connector["questionId"]) and _is_nonempty_string(connector["text"]))
            _shape_rect(connector["rectPx"])
            if "evidence" in connector:
                evidence = _exact(connector["evidence"], {"kind", "reviewId", "imageSha256"})
                _require(evidence["kind"] == "reviewedMeasurement")
                _check_id(evidence["reviewId"])
                _check_sha(evidence["imageSha256"])
        return
    if kind == "table":
        block = _exact(block, {"id", "type", "rows"})
        _check_id(block["id"])
        rows = block["rows"]
        _require(isinstance(rows, list) and 1 <= len(rows) <= MAX_TABLE_ROWS)
        for row in rows:
            row = _exact(row, {"id", "cells"})
            _check_id(row["id"])
            cells = row["cells"]
            _require(isinstance(cells, list) and 1 <= len(cells) <= MAX_TABLE_CELLS)
            for cell in cells:
                cell = _exact(cell, {"id", "rowSpan", "colSpan", "children"})
                _check_id(cell["id"])
                _require(_is_int(cell["rowSpan"]) and cell["rowSpan"] >= 1)
                _require(_is_int(cell["colSpan"]) and cell["colSpan"] >= 1)
                children = cell["children"]
                _require(isinstance(children, list) and 1 <= len(children) <= MAX_GROUP_BLOCKS)
                for child in children:
                    _shape_block(child, depth + 2, totals)
        return
    if kind == "flow":
        block = _exact(block, {"id", "type", "nodes", "edges"})
        _check_id(block["id"])
        nodes = block["nodes"]
        edges = block["edges"]
        _require(isinstance(nodes, list) and 1 <= len(nodes) <= MAX_FLOW_NODES)
        _require(isinstance(edges, list) and len(edges) <= MAX_FLOW_EDGES)
        for node in nodes:
            node = _exact(node, {"id", "children"})
            _check_id(node["id"])
            children = node["children"]
            _require(isinstance(children, list) and 1 <= len(children) <= MAX_GROUP_BLOCKS)
            for child in children:
                _shape_block(child, depth + 2, totals)
        for edge in edges:
            edge = _exact(edge, {"from", "to"})
            _check_id(edge["from"])
            _check_id(edge["to"])
        return
    _fail("INVALID_LAYOUT_SHAPE")


def _shape_layout(layout: object) -> dict[str, Any]:
    doc = _exact(layout, {"schemaVersion", "partId", "basePartSha256", "groups"})
    _require(_is_nonempty_string(doc["partId"]))
    _check_sha(doc["basePartSha256"])
    groups = doc["groups"]
    _require(isinstance(groups, list) and 1 <= len(groups) <= MAX_GROUPS)
    totals = {"blocks": 0, "text": 0}
    doc_block_count = 0

    for group in groups:
        group = _exact(group, {
            "groupId", "questionIds", "promptHashes", "instructions", "optionPools", "slots", "responses", "blocks",
        })
        _check_id(group["groupId"])
        question_ids = _string_list(group["questionIds"], minimum=1, maximum=MAX_GROUP_QUESTIONS)
        hashes = _object(group["promptHashes"])
        if set(hashes) != set(question_ids):
            _fail("INVALID_LAYOUT_SHAPE")
        for digest in hashes.values():
            _check_sha(digest)

        instructions = group["instructions"]
        _require(isinstance(instructions, list) and len(instructions) <= MAX_GROUP_INSTRUCTIONS)
        group_blocks = len(instructions)
        for text in instructions:
            _shape_text(text, totals, allow_connector=False)

        pools = group["optionPools"]
        _require(isinstance(pools, list) and len(pools) <= MAX_GROUP_POOLS)
        for pool in pools:
            _shape_option_pool(pool)

        slots = group["slots"]
        _require(isinstance(slots, list) and len(slots) <= MAX_GROUP_SLOTS)
        for slot in slots:
            _shape_slot(slot)

        responses = group["responses"]
        _require(isinstance(responses, list) and len(responses) <= MAX_GROUP_RESPONSES)
        for response in responses:
            _shape_response(response)

        blocks = group["blocks"]
        _require(isinstance(blocks, list) and 1 <= len(blocks) <= MAX_GROUP_BLOCKS)
        for block in blocks:
            _shape_block(block, 1, totals)

        def count(block: object) -> int:
            kind = block["type"]
            result = 1
            if kind in {"paragraph", "listItem", "formRow"}:
                result += sum(count(child) for child in block["children"])
            elif kind == "table":
                for row in block["rows"]:
                    for cell in row["cells"]:
                        result += 1 + sum(count(child) for child in cell["children"])
            elif kind == "flow":
                for node in block["nodes"]:
                    result += 1 + sum(count(child) for child in node["children"])
            return result

        group_blocks += sum(count(block) for block in blocks)
        doc_block_count += group_blocks
        _require(group_blocks <= MAX_GROUP_BLOCKS and doc_block_count <= MAX_DOCUMENT_BLOCKS)

    _require(totals["text"] <= MAX_TOTAL_TEXT_UTF16)
    return doc


def _part_valid(part: object) -> bool:
    if not isinstance(part, dict):
        return False
    root_keys = {
        "schemaVersion", "id", "examType", "book", "test", "part", "title", "instructions",
        "audioPath", "audioSha256", "transcript", "questions",
    }
    if not root_keys <= part.keys() or part.keys() - root_keys:
        return False
    if (not _is_int(part["schemaVersion"]) or part["schemaVersion"] not in (1, 2)
            or not isinstance(part["id"], str) or not PART_ID_RE.fullmatch(part["id"])
            or not isinstance(part["examType"], str)
            or any(not _is_int(part[name]) for name in ("book", "test", "part"))
            or not isinstance(part["title"], str) or not part["title"].strip()
            or not isinstance(part["instructions"], str) or not part["instructions"].strip()
            or part["audioPath"] != f"listening/{part['id']}/audio.mp3"
            or not isinstance(part["audioSha256"], str) or SHA256_RE.fullmatch(part["audioSha256"]) is None):
        return False

    transcript = part["transcript"]
    if not isinstance(transcript, dict) or set(transcript) != {"english", "chinese", "segments"}:
        return False
    if (not isinstance(transcript["english"], str) or not isinstance(transcript["chinese"], str)
            or not isinstance(transcript["segments"], list)):
        return False
    for segment in transcript["segments"]:
        if not isinstance(segment, dict) or set(segment) != {"english", "chinese"}:
            return False
        if not all(isinstance(segment[key], str) for key in ("english", "chinese")):
            return False
    if part["schemaVersion"] == 1 and (not transcript["english"].strip()
                                       or not transcript["chinese"].strip() or not transcript["segments"]):
        return False
    if "\n\n".join(s["english"].strip() for s in transcript["segments"] if s["english"].strip()) != transcript["english"].strip():
        return False
    if "\n\n".join(s["chinese"].strip() for s in transcript["segments"] if s["chinese"].strip()) != transcript["chinese"].strip():
        return False

    questions = part["questions"]
    if not isinstance(questions, list) or not questions:
        return False
    ids: set[str] = set()
    numbers: set[int] = set()
    allowed_question_keys = {
        "id", "number", "type", "prompt", "options", "correctAnswer", "acceptedAnswers",
        "instructions", "context", "images", "wordLimit", "groupId", "groupNumbers", "answerSeparator",
    }
    for question in questions:
        required = {"id", "number", "type", "prompt", "options", "correctAnswer"}
        if not isinstance(question, dict) or not required <= question.keys() or question.keys() - allowed_question_keys:
            return False
        qid, number = question["id"], question["number"]
        if (not isinstance(qid, str) or not qid or not _is_int(number) or number in numbers or qid in ids
                or not isinstance(question["type"], str) or question["type"] not in QUESTION_TYPES
                or not isinstance(question["prompt"], str) or not question["prompt"].strip()
                or not isinstance(question["correctAnswer"], str) or not question["correctAnswer"]):
            return False
        ids.add(qid)
        numbers.add(number)
        options = question["options"]
        if not isinstance(options, list):
            return False
        option_ids: list[str] = []
        for option in options:
            if (not isinstance(option, dict) or set(option) != {"id", "text"}
                    or not isinstance(option["id"], str) or not option["id"].strip()
                    or not isinstance(option["text"], str) or not option["text"].strip()):
                return False
            option_ids.append(option["id"])
        if len(set(option_ids)) != len(option_ids):
            return False
        if not options and (question["type"] in {"SINGLE_CHOICE", "MULTIPLE_CHOICE", "MATCHING"}
                            or question.get("wordLimit") is None):
            return False
        if options and len(options) < 2:
            return False
        accepted = question.get("acceptedAnswers", [])
        if not isinstance(accepted, list) or any(not isinstance(answer, str) or not answer.strip() for answer in accepted):
            return False
        effective_answers = accepted or [question["correctAnswer"]]
        if options and any(not any(answer.casefold() == option_id.casefold() for option_id in option_ids)
                           for answer in effective_answers):
            return False
        if not isinstance(question.get("instructions", ""), str) or not isinstance(question.get("context", ""), str):
            return False
        images = question.get("images", [])
        if not isinstance(images, list) or any(not isinstance(image, str)
                                               or re.fullmatch(r"[a-zA-Z0-9_-]+\.(?:png|jpg|jpeg|webp)", image) is None
                                               for image in images):
            return False
        if question["type"] == "IMAGE_BASED" and not images:
            return False
        word_limit = question.get("wordLimit")
        if word_limit is not None:
            if (not isinstance(word_limit, dict)
                    or not {"maxWords"} <= word_limit.keys()
                    or word_limit.keys() - {"maxWords", "maxNumbers", "numberOnly", "wordsOrNumber"}
                    or not _is_int(word_limit["maxWords"]) or not 0 <= word_limit["maxWords"] <= 10
                    or (word_limit.get("maxNumbers", 0) is not None
                        and (not _is_int(word_limit.get("maxNumbers", 0)) or not 0 <= word_limit.get("maxNumbers", 0) <= 10))
                    or not isinstance(word_limit.get("numberOnly", False), bool)
                    or not isinstance(word_limit.get("wordsOrNumber", False), bool)):
                return False
        group_id = question.get("groupId")
        group_numbers = question.get("groupNumbers", [])
        if group_id is not None and not isinstance(group_id, str):
            return False
        if not isinstance(group_numbers, list) or any(not _is_int(item) for item in group_numbers):
            return False
        separator = question.get("answerSeparator")
        if separator is not None and (not isinstance(separator, str) or separator not in {"and", "or", "to"}
                                      or options or word_limit is None):
            return False
        if question["type"] == "MULTIPLE_CHOICE":
            members = [item for item in questions if isinstance(item, dict) and item.get("groupId") == group_id]
            if (group_id is None or not 2 <= len(group_numbers) <= 6
                    or sorted(item.get("number") for item in members) != sorted(group_numbers)
                    or any(item.get("type") != "MULTIPLE_CHOICE" or item.get("options") != options
                           or item.get("groupNumbers", []) != group_numbers for item in members)
                    or len({item.get("correctAnswer") for item in members}) != len(members)):
                return False
        elif group_id is not None or group_numbers:
            return False
    return True


def _check_group_ownership(layout: dict[str, Any], part: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], dict[str, int]]:
    questions = part["questions"]
    by_id = {question["id"]: question for question in questions}
    order = {question["id"]: index for index, question in enumerate(questions)}
    seen_group_ids: set[str] = set()
    seen_questions: set[str] = set()

    if layout["partId"] != part["id"]:
        _fail("INVALID_REFERENCE")
    for group in layout["groups"]:
        group_id = group["groupId"]
        if group_id in seen_group_ids:
            _fail("INVALID_REFERENCE")
        seen_group_ids.add(group_id)
        qids = group["questionIds"]
        if len(qids) != len(set(qids)) or any(qid in seen_questions for qid in qids):
            _fail("QUESTION_IN_MULTIPLE_GROUPS")
        if any(qid not in by_id for qid in qids):
            _fail("INVALID_REFERENCE")
        if qids != sorted(qids, key=order.__getitem__):
            _fail("INVALID_REFERENCE")
        seen_questions.update(qids)
        for qid in qids:
            question = by_id[qid]
            expected_hash = sha256(question["prompt"].encode("utf-8"))
            if group["promptHashes"][qid] != expected_hash:
                _fail("INVALID_REFERENCE")
    if seen_questions != set(by_id):
        _fail("QUESTION_COVERAGE_MISMATCH")
    return by_id, order


def _has_injective_assignment(answer_sets: list[list[str]], option_sets: list[list[str]]) -> bool:
    """Return whether every question can receive a different accepted option ID."""
    candidates = []
    for answers, option_ids in zip(answer_sets, option_sets):
        option_keys = {option_id.casefold() for option_id in option_ids}
        candidates.append(list(dict.fromkeys(answer.casefold() for answer in answers
                                             if answer.casefold() in option_keys)))
    if any(not choices for choices in candidates):
        return False

    assigned_question_by_option: dict[str, int] = {}

    def assign(question_index: int, seen_options: set[str]) -> bool:
        for option_key in candidates[question_index]:
            if option_key in seen_options:
                continue
            seen_options.add(option_key)
            owner = assigned_question_by_option.get(option_key)
            if owner is None or assign(owner, seen_options):
                assigned_question_by_option[option_key] = question_index
                return True
        return False

    return all(assign(index, set()) for index in range(len(candidates)))


def _grader_normalize(value: str) -> str:
    value = unicodedata.normalize("NFC", value).strip()
    return re.sub(r"[\s\u00a0\u202f]+", " ", value).lower()


def _grader_within_limit(question: dict[str, Any], value: str) -> bool:
    """Mirror the current Grader.withinLimit behavior for reviewed legacy answers."""
    limit = question.get("wordLimit")
    if limit is None:
        return True
    normalized = _grader_normalize(value)
    separator = question.get("answerSeparator")
    if separator is None:
        parts = [normalized]
    else:
        parts = re.split(r"(?:^|\s+)" + re.escape(separator) + r"(?:\s+|$)", normalized, flags=re.IGNORECASE)
        if len(parts) != 2 or any(not part.strip() for part in parts):
            return False
    counted = " ".join(parts)
    counted = re.sub(r"(?<!\S)(\d{1,2}(?:[.:]\d{2})?)\s+([ap]\.?m\.?)(?!\S)", r"\1\2", counted,
                     flags=re.IGNORECASE)
    tokens = [token for token in counted.split(" ") if token]
    if not tokens:
        return False
    number_pattern = re.compile(
        r"(?:[£$€+-]?\d+(?:[.,:/-]\d+)*(?:st|nd|rd|th)?%?|\d{1,2}(?:[.:]\d{2})?[ap]\.?m\.?)",
        re.IGNORECASE,
    )
    numbers = sum(number_pattern.fullmatch(token) is not None for token in tokens)
    words = len(tokens) - numbers
    max_numbers = limit.get("maxNumbers", 0)
    return (
        words <= limit["maxWords"]
        and (max_numbers is None or numbers <= max_numbers)
        and (not limit.get("numberOnly", False) or words == 0)
        and (not limit.get("wordsOrNumber", False) or words == 0 or numbers == 0)
    )


def _reviewed_joiner_compatible(question: dict[str, Any], joiner: str) -> bool:
    """Check a no-legacy-separator candidate against the unchanged Grader contract."""
    answers = question.get("acceptedAnswers") or [question["correctAnswer"]]
    accepted = {_grader_normalize(answer) for answer in answers}
    pattern = r"(?:^|\s+)" + re.escape(joiner) + r"(?:\s+|$)"
    for candidate in answers:
        parts = re.split(pattern, _grader_normalize(candidate), flags=re.IGNORECASE)
        if len(parts) != 2 or any(not part.strip() for part in parts):
            continue
        reconstructed = f"{parts[0].strip()} {joiner} {parts[1].strip()}"
        if (_grader_normalize(reconstructed) in accepted
                and _grader_within_limit(question, reconstructed)):
            return True
    return False


def compose_fill_answer(slot_values: list[str], joiner: str | None = None) -> str | None:
    """Return the one legacy stored answer, or None when any required input is blank."""
    if joiner is None:
        if len(slot_values) != 1 or not isinstance(slot_values[0], str) or not slot_values[0].strip():
            return None
        return slot_values[0].strip()
    if (joiner not in {"and", "or", "to"} or len(slot_values) != 2
            or any(not isinstance(value, str) or not value.strip() for value in slot_values)):
        return None
    return f"{slot_values[0].strip()} {joiner} {slot_values[1].strip()}"


def _check_response_rules(groups: list[dict[str, Any]], by_id: dict[str, dict[str, Any]], order: dict[str, int]) -> None:
    for group in groups:
        group_qids = group["questionIds"]
        covered: set[str] = set()
        for response in group["responses"]:
            qids = response["questionIds"]
            if (len(qids) != len(set(qids)) or any(qid not in group_qids for qid in qids)
                    or any(qid in covered for qid in qids)):
                _fail("RESPONSE_COVERAGE_MISMATCH")
            if qids != sorted(qids, key=order.__getitem__):
                _fail("INVALID_LAYOUT_SHAPE")
            covered.update(qids)
            kind = response["kind"]
            members = [by_id[qid] for qid in qids]
            if kind == "multipleChoice":
                legacy_group_id = members[0].get("groupId")
                expected_members = [question["id"] for question in by_id.values()
                                    if question.get("groupId") == legacy_group_id]
                if (legacy_group_id is None
                        or any(question["type"] != "MULTIPLE_CHOICE" for question in members)
                        or qids != expected_members):
                    _fail("LEGACY_INTERACTION_MISMATCH")
            elif any(question["type"] == "MULTIPLE_CHOICE" for question in members):
                _fail("LEGACY_INTERACTION_MISMATCH")
            if kind == "fill":
                q = by_id[qids[0]]
                slot_count = len(response["slotIds"])
                separator = q.get("answerSeparator")
                has_joiner = "joiner" in response
                if (len(qids) != 1 or slot_count not in {1, 2} or q["options"]
                        or q.get("wordLimit") is None or response["scoring"] != "legacyPerQuestion"):
                    _fail("INVALID_LAYOUT_SHAPE")
                if ((separator is not None and (slot_count != 2 or response.get("joiner") != separator))
                        or (separator is None and ((slot_count == 1 and has_joiner)
                                                   or (slot_count == 2 and not has_joiner)))):
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                if separator is None and slot_count == 2 and not _reviewed_joiner_compatible(q, response["joiner"]):
                    _fail("ANSWER_JOINER_INCOMPATIBLE")
            elif kind == "singleChoice":
                q = by_id[qids[0]]
                if (len(qids) != 1 or len(response["slotIds"]) != 1 or len(q["options"]) < 2
                        or response["scoring"] != "legacyPerQuestion"):
                    _fail("INVALID_LAYOUT_SHAPE")
            elif kind == "matching":
                if (len(response["slotIds"]) != len(qids)
                        or response["scoring"] != "legacyPerQuestion"):
                    _fail("INVALID_LAYOUT_SHAPE")
                if response["reuse"] == "forbidden" and not _has_injective_assignment(
                        [question.get("acceptedAnswers") or [question["correctAnswer"]]
                         for question in members],
                        [[option["id"] for option in question["options"]] for question in members]):
                    _fail("MATCHING_REUSE_CONFLICT")
            elif kind == "multipleChoice":
                same_group = bool(members) and members[0].get("groupId") is not None and all(
                    q.get("groupId") == members[0].get("groupId")
                    and q.get("groupNumbers", []) == members[0].get("groupNumbers", []) for q in members
                )
                group_numbers = members[0].get("groupNumbers", []) if members else []
                if (not 2 <= len(qids) <= 6 or len(response["slotIds"]) != len(qids)
                        or response["scoring"] != "onePointPerCorrectOption"
                        or response["selectCount"] != len(qids) or response["order"] != "unordered"
                        or response["storage"] != "sameSortedSetOnEveryQuestion" or not same_group
                        or sorted(group_numbers) != sorted(by_id[qid]["number"] for qid in qids)):
                    _fail("INVALID_LAYOUT_SHAPE")
        if covered != set(group_qids):
            _fail("RESPONSE_COVERAGE_MISMATCH")


def _check_slots(groups: list[dict[str, Any]]) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    slots_by_id: dict[str, dict[str, Any]] = {}
    response_by_question: dict[str, dict[str, Any]] = {}
    for group in groups:
        for response in group["responses"]:
            for qid in response["questionIds"]:
                response_by_question[qid] = response

    for group in groups:
        group_ids = set(group["questionIds"])
        slot_by_q: dict[str, list[dict[str, Any]]] = {qid: [] for qid in group_ids}
        for slot in group["slots"]:
            if slot["slotId"] in slots_by_id or slot["slotIndex"] in [s["slotIndex"] for s in slot_by_q.get(slot["questionId"], [])]:
                _fail("DUPLICATE_SLOT")
            if slot["questionId"] not in group_ids:
                _fail("INVALID_REFERENCE")
            slots_by_id[slot["slotId"]] = slot
            slot_by_q[slot["questionId"]].append(slot)

        for qid in group["questionIds"]:
            qslots = sorted(slot_by_q[qid], key=lambda item: item["slotIndex"])
            response = response_by_question.get(qid)
            if response is None:
                _fail("RESPONSE_COVERAGE_MISMATCH")
            expected_kind = response["kind"]
            expected_count = 2 if expected_kind == "fill" and response["slotIds"] and len(response["slotIds"]) == 2 else 1
            if len(qslots) != expected_count or [s["slotIndex"] for s in qslots] != list(range(expected_count)):
                _fail("INVALID_LAYOUT_SHAPE")
            if any(slot["kind"] != expected_kind for slot in qslots):
                _fail("INVALID_LAYOUT_SHAPE")

        for response in group["responses"]:
            expected = [slot["slotId"] for qid in response["questionIds"]
                        for slot in sorted(slot_by_q[qid], key=lambda item: item["slotIndex"])]
            if any(slot_id not in slots_by_id for slot_id in response["slotIds"]):
                _fail("INVALID_REFERENCE")
            if response["slotIds"] != expected:
                _fail("INVALID_LAYOUT_SHAPE")
    return slots_by_id, response_by_question


def _walk_blocks(blocks: list[dict[str, Any]]):
    for block in blocks:
        yield block
        kind = block["type"]
        if kind in {"paragraph", "listItem", "formRow"}:
            yield from _walk_blocks(block["children"])
        elif kind == "table":
            for row in block["rows"]:
                for cell in row["cells"]:
                    yield from _walk_blocks(cell["children"])
        elif kind == "flow":
            for node in block["nodes"]:
                yield from _walk_blocks(node["children"])


def _display_leaves(blocks: list[dict[str, Any]]):
    """Yield response and connector leaves in the declared display traversal order."""
    for block in blocks:
        kind = block["type"]
        if kind == "text":
            if block.get("role") == "answerConnector":
                yield "connector", block
        elif kind == "slot":
            yield "slot", block["slotId"]
        elif kind == "choiceList":
            for slot_id in block["slotIds"]:
                yield "choiceList", slot_id
        elif kind in {"paragraph", "listItem", "formRow"}:
            yield from _display_leaves(block["children"])
        elif kind == "table":
            for row in block["rows"]:
                for cell in row["cells"]:
                    yield from _display_leaves(cell["children"])
        elif kind == "flow":
            for node in block["nodes"]:
                yield from _display_leaves(node["children"])
        elif kind == "image":
            for overlay in block["overlays"]:
                yield "overlay", overlay["slotId"]


def _rectangles_intersect(left: dict[str, int], right: dict[str, int]) -> bool:
    return (left["x"] < right["x"] + right["width"] and right["x"] < left["x"] + left["width"]
            and left["y"] < right["y"] + right["height"] and right["y"] < left["y"] + left["height"])


def _check_answer_connectors(groups: list[dict[str, Any]], part: dict[str, Any]) -> None:
    question_by_id = {question["id"]: question for question in part["questions"]}
    for group in groups:
        group_qids = set(group["questionIds"])
        connectors: dict[str, list[tuple[int, dict[str, Any]]]] = {}
        slot_locations: dict[str, list[tuple[int, str]]] = {}
        response_by_question = {
            qid: response for response in group["responses"] for qid in response["questionIds"]
        }
        slots_by_id = {slot["slotId"]: slot for slot in group["slots"]}
        image_slots: dict[str, list[tuple[str, dict[str, Any]]]] = {}
        image_connectors: dict[str, list[tuple[str, dict[str, Any]]]] = {}
        for position, (kind, value) in enumerate(_display_leaves(group["blocks"])):
            if kind == "connector":
                question_id = value["questionId"]
                if question_id not in group_qids:
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                connectors.setdefault(question_id, []).append((position, value))
            elif kind in {"slot", "overlay", "choiceList"}:
                slot_locations.setdefault(value, []).append((position, kind))

        for block in _walk_blocks(group["blocks"]):
            if block["type"] != "image":
                continue
            for overlay in block["overlays"]:
                slot = slots_by_id.get(overlay["slotId"])
                if slot is not None:
                    image_slots.setdefault(slot["questionId"], []).append((block["asset"], overlay["rectPx"]))
            for connector in block["printedConnectors"]:
                image_connectors.setdefault(connector["questionId"], []).append((block["asset"], connector))

        for question_id in group["questionIds"]:
            question = question_by_id[question_id]
            response = response_by_question[question_id]
            question_connectors = connectors.get(question_id, [])
            printed = image_connectors.get(question_id, [])
            if response["kind"] != "fill" or len(response["slotIds"]) == 1:
                if question_connectors or printed:
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                continue
            joiner = response.get("joiner")
            if joiner not in {"and", "or", "to"}:
                _fail("ANSWER_CONNECTOR_MISMATCH")

            question_slots = sorted(
                (slot for slot in group["slots"] if slot["questionId"] == question_id),
                key=lambda slot: slot["slotIndex"],
            )
            if len(question_slots) != 2 or [slot["slotIndex"] for slot in question_slots] != [0, 1]:
                _fail("SLOT_PLACEMENT_MISMATCH")
            question_image_slots = image_slots.get(question_id, [])
            if question_image_slots:
                if (len(question_image_slots) != 2 or question_image_slots[0][0] != question_image_slots[1][0]
                        or len(printed) != 1 or printed[0][0] != question_image_slots[0][0]
                        or question_connectors):
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                connector = printed[0][1]
                if connector["text"] != joiner:
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                first_rect, second_rect = question_image_slots[0][1], question_image_slots[1][1]
                connector_rect = connector["rectPx"]
                if (_rectangles_intersect(first_rect, second_rect)
                        or _rectangles_intersect(connector_rect, first_rect)
                        or _rectangles_intersect(connector_rect, second_rect)):
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                order = sorted(
                    [(first_rect["y"], first_rect["x"], 0),
                     (connector_rect["y"], connector_rect["x"], 1),
                     (second_rect["y"], second_rect["x"], 2)]
                )
                if [item[2] for item in order] != [0, 1, 2]:
                    _fail("SLOT_PLACEMENT_MISMATCH")
                if question.get("answerSeparator") is not None and question["answerSeparator"] != joiner:
                    _fail("ANSWER_CONNECTOR_MISMATCH")
            else:
                if printed or len(question_connectors) != 1 or question_connectors[0][1]["text"] != joiner:
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                if question.get("answerSeparator") != joiner:
                    _fail("ANSWER_CONNECTOR_MISMATCH")
                locations = [slot_locations.get(slot["slotId"], []) for slot in question_slots]
                if (any(len(items) != 1 or items[0][1] != "slot" for items in locations)
                        or not locations[0][0][0] < question_connectors[0][0] < locations[1][0][0]):
                    _fail("SLOT_PLACEMENT_MISMATCH")

        if any(question_id not in group_qids for question_id in image_connectors):
            _fail("ANSWER_CONNECTOR_MISMATCH")


def _table_is_rectangular(table: dict[str, Any]) -> bool:
    rows = table["rows"]
    height = len(rows)
    occupied: list[list[tuple[int, int]]] = [[] for _ in range(height)]
    for row_index, row in enumerate(rows):
        column = 0
        for cell in row["cells"]:
            for start, end in sorted(occupied[row_index]):
                if start <= column < end:
                    column = end
                elif start > column:
                    break
            row_span, col_span = cell["rowSpan"], cell["colSpan"]
            if row_index + row_span > height:
                return False
            end_column = column + col_span
            for r in range(row_index, row_index + row_span):
                if any(column < end and start < end_column for start, end in occupied[r]):
                    return False
                occupied[r].append((column, end_column))
            column = end_column
    widths = [max((end for _, end in cells), default=0) for cells in occupied]
    if not widths or any(width != widths[0] for width in widths):
        return False
    for intervals in occupied:
        cursor = 0
        for start, end in sorted(intervals):
            if start != cursor:
                return False
            cursor = end
        if cursor != widths[0]:
            return False
    return True


def _check_blocks(layout: dict[str, Any], part: dict[str, Any], image_metadata: dict[str, dict[str, Any]],
                  slots_by_id: dict[str, dict[str, Any]]) -> tuple[
                      list[tuple[dict[str, Any], str]],
                      list[tuple[dict[str, Any], str, str]],
                      list[tuple[dict[str, Any], str, str]],
                  ]:
    block_ids: set[str] = set()
    text_ids: set[str] = set()
    placements: list[tuple[dict[str, Any], str]] = []
    overlays: list[tuple[dict[str, Any], str, str]] = []
    connectors: list[tuple[dict[str, Any], str, str]] = []
    question_by_id = {q["id"]: q for q in part["questions"]}

    for group in layout["groups"]:
        group_id = group["groupId"]
        group_qids = group["questionIds"]
        group_slots = {slot["slotId"]: slot for slot in group["slots"]}
        for instruction in group["instructions"]:
            if instruction["id"] in block_ids:
                _fail("INVALID_REFERENCE")
            block_ids.add(instruction["id"])
            if instruction["textId"] in text_ids:
                _fail("INVALID_REFERENCE")
            text_ids.add(instruction["textId"])
        for block in _walk_blocks(group["blocks"]):
            if block["id"] in block_ids:
                _fail("INVALID_REFERENCE")
            block_ids.add(block["id"])
            if block["type"] == "text":
                if block["textId"] in text_ids:
                    _fail("INVALID_REFERENCE")
                text_ids.add(block["textId"])
            elif block["type"] == "slot":
                if block["slotId"] not in group_slots:
                    _fail("INVALID_REFERENCE")
                placements.append((block, group_id))
            elif block["type"] == "choiceList":
                if any(slot_id not in group_slots for slot_id in block["slotIds"]):
                    _fail("INVALID_REFERENCE")
                placements.append((block, group_id))
            elif block["type"] == "table":
                row_ids: set[str] = set()
                for row in block["rows"]:
                    if row["id"] in row_ids:
                        _fail("TABLE_GRID_INVALID")
                    row_ids.add(row["id"])
                    for cell in row["cells"]:
                        if cell["id"] in block_ids:
                            _fail("INVALID_REFERENCE")
                        block_ids.add(cell["id"])
                if not _table_is_rectangular(block):
                    _fail("TABLE_GRID_INVALID")
            elif block["type"] == "flow":
                node_ids = [node["id"] for node in block["nodes"]]
                if len(node_ids) != len(set(node_ids)):
                    _fail("FLOW_EDGE_INVALID")
                for node_id in node_ids:
                    if node_id in block_ids:
                        _fail("INVALID_REFERENCE")
                    block_ids.add(node_id)
                seen_edges: set[tuple[str, str]] = set()
                for edge in block["edges"]:
                    pair = (edge["from"], edge["to"])
                    if (pair[0] not in node_ids or pair[1] not in node_ids or pair[0] == pair[1]
                            or pair in seen_edges):
                        _fail("FLOW_EDGE_INVALID")
                    seen_edges.add(pair)
            elif block["type"] == "image":
                metadata = image_metadata.get(block["asset"])
                if not isinstance(metadata, dict):
                    _fail("UNKNOWN_IMAGE")
                if set(metadata) != {"sha256", "widthPx", "heightPx"}:
                    _fail("INVALID_LAYOUT_SHAPE")
                if (not _is_int(metadata.get("widthPx")) or not _is_int(metadata.get("heightPx"))
                        or metadata["widthPx"] < 1 or metadata["heightPx"] < 1
                        or not isinstance(metadata.get("sha256"), str)
                        or SHA256_RE.fullmatch(metadata.get("sha256", "")) is None):
                    _fail("INVALID_LAYOUT_SHAPE")
                if block["widthPx"] != metadata["widthPx"] or block["heightPx"] != metadata["heightPx"]:
                    _fail("IMAGE_DIMENSION_MISMATCH")
                declared = any(block["asset"] in question_by_id[qid].get("images", []) for qid in group_qids)
                if not declared:
                    _fail("INVALID_REFERENCE")
                for overlay in block["overlays"]:
                    if "evidence" not in overlay:
                        _fail("IMAGE_EVIDENCE_MISSING")
                    slot_id = overlay["slotId"]
                    if slot_id not in group_slots:
                        _fail("INVALID_REFERENCE")
                    rect = overlay["rectPx"]
                    if rect["x"] + rect["width"] > block["widthPx"] or rect["y"] + rect["height"] > block["heightPx"]:
                        _fail("IMAGE_RECT_OUT_OF_BOUNDS")
                    if group_slots[slot_id]["kind"] != "fill":
                        _fail("INVALID_REFERENCE")
                    if overlay["evidence"]["imageSha256"] != metadata["sha256"]:
                        _fail("REVIEW_RECORD_MISSING")
                    placements.append((overlay, group_id))
                    overlays.append((overlay, group_id, block["asset"]))
                for connector in block["printedConnectors"]:
                    if "evidence" not in connector:
                        _fail("IMAGE_EVIDENCE_MISSING")
                    if connector["questionId"] not in group_qids:
                        _fail("INVALID_REFERENCE")
                    rect = connector["rectPx"]
                    if rect["x"] + rect["width"] > block["widthPx"] or rect["y"] + rect["height"] > block["heightPx"]:
                        _fail("IMAGE_RECT_OUT_OF_BOUNDS")
                    if connector["evidence"]["imageSha256"] != metadata["sha256"]:
                        _fail("REVIEW_RECORD_MISSING")
                    connectors.append((connector, group_id, block["asset"]))

    return placements, overlays, connectors


def _check_slot_placements(groups: list[dict[str, Any]], placements: list[tuple[dict[str, Any], str]],
                           slots_by_id: dict[str, dict[str, Any]],
                           response_by_question: dict[str, dict[str, Any]]) -> None:
    counts: dict[str, list[tuple[str, str]]] = {slot_id: [] for slot_id in slots_by_id}
    for item, group_id in placements:
        if item.get("type") == "slot":
            counts[item["slotId"]].append(("slot", group_id))
        elif item.get("type") == "choiceList":
            for slot_id in item["slotIds"]:
                counts[slot_id].append(("choiceList", group_id))
        else:
            counts[item["slotId"]].append(("overlay", group_id))

    for slot_id, slot in slots_by_id.items():
        placements_for_slot = counts[slot_id]
        if len(placements_for_slot) != 1:
            _fail("SLOT_PLACEMENT_MISMATCH")
        placement_kind = placements_for_slot[0][0]
        if slot["kind"] == "fill":
            if placement_kind not in {"slot", "overlay"}:
                _fail("SLOT_PLACEMENT_MISMATCH")
        elif placement_kind != "choiceList":
            _fail("SLOT_PLACEMENT_MISMATCH")

    for group in groups:
        choice_lists = [block for block in _walk_blocks(group["blocks"]) if block["type"] == "choiceList"]
        for block in choice_lists:
            rules = {id(response_by_question[slots_by_id[slot_id]["questionId"]]): response_by_question[
                slots_by_id[slot_id]["questionId"]] for slot_id in block["slotIds"]}
            if len(rules) != 1:
                _fail("SLOT_PLACEMENT_MISMATCH")
            response = next(iter(rules.values()))
            if any(slots_by_id[slot_id]["kind"] not in {"singleChoice", "multipleChoice", "matching"}
                   for slot_id in block["slotIds"]):
                _fail("SLOT_PLACEMENT_MISMATCH")
            if response["kind"] == "multipleChoice":
                if block["slotIds"] != response["slotIds"]:
                    _fail("SLOT_PLACEMENT_MISMATCH")
                all_for_rule = [candidate for candidate in choice_lists
                                if any(response_by_question[slots_by_id[sid]["questionId"]] is response
                                       for sid in candidate["slotIds"])]
                if len(all_for_rule) != 1:
                    _fail("SLOT_PLACEMENT_MISMATCH")
            elif len(block["slotIds"]) != 1:
                _fail("SLOT_PLACEMENT_MISMATCH")


def _check_option_pools(groups: list[dict[str, Any]], by_id: dict[str, dict[str, Any]],
                        response_by_question: dict[str, dict[str, Any]]) -> None:
    for group in groups:
        pools: dict[str, dict[str, Any]] = {}
        for pool in group["optionPools"]:
            if pool["poolId"] in pools:
                _fail("INVALID_REFERENCE")
            if (len(pool["questionIds"]) != len(set(pool["questionIds"]))
                    or any(qid not in group["questionIds"] for qid in pool["questionIds"])):
                _fail("INVALID_REFERENCE")
            if pool["questionIds"] != sorted(pool["questionIds"], key=group["questionIds"].index):
                _fail("INVALID_REFERENCE")
            pools[pool["poolId"]] = pool
            expected_options = None
            for qid in pool["questionIds"]:
                question = by_id[qid]
                actual_ids = [item["id"] for item in question["options"]]
                if actual_ids != pool["optionIds"]:
                    _fail("OPTION_POOL_MISMATCH")
                texts = [item["text"] for item in question["options"]]
                if expected_options is None:
                    expected_options = texts
                elif texts != expected_options:
                    _fail("OPTION_POOL_MISMATCH")

        for response in group["responses"]:
            if response["kind"] not in {"singleChoice", "matching", "multipleChoice"}:
                continue
            pool = pools.get(response["optionPoolId"])
            if pool is None or any(qid not in pool["questionIds"] for qid in response["questionIds"]):
                _fail("INVALID_REFERENCE")
        for block in _walk_blocks(group["blocks"]):
            if block["type"] != "choiceList":
                continue
            pool = pools.get(block["optionPoolId"])
            if pool is None:
                _fail("INVALID_REFERENCE")
            qids = [next(slot["questionId"] for slot in group["slots"] if slot["slotId"] == slot_id)
                    for slot_id in block["slotIds"]]
            rules = [response_by_question[qid] for qid in qids]
            if any(rule["optionPoolId"] != block["optionPoolId"] for rule in rules):
                _fail("INVALID_REFERENCE")
            if any(qid not in pool["questionIds"] for qid in qids):
                _fail("INVALID_REFERENCE")


class _TextContentParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.ignored = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() in {"script", "style"}:
            self.ignored += 1

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() in {"script", "style"} and self.ignored:
            self.ignored -= 1

    def handle_data(self, data: str) -> None:
        if not self.ignored:
            self.parts.append(data)


def _normalized_resource(resource: str, raw: bytes) -> str:
    try:
        decoded = raw.decode("utf-8", errors="strict")
        if resource == "content.txt":
            return decoded.replace("\r\n", "\n").replace("\r", "\n")
        parser = _TextContentParser()
        parser.feed(decoded)
        parser.close()
        value = "".join(parser.parts).replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ")
        return re.sub(r"[ \t\n\f\v]+", " ", value).strip(" ")
    except (UnicodeDecodeError, ValueError):
        _fail("INVALID_REFERENCE")


def _check_text_sources(groups: list[dict[str, Any]], part: dict[str, Any], by_id: dict[str, dict[str, Any]],
                        source_bytes: dict[str, bytes]) -> None:
    for group in groups:
        group_qids = set(group["questionIds"])
        for text in list(group["instructions"]) + [block for block in _walk_blocks(group["blocks"])
                                                     if block["type"] == "text"]:
            source = text["source"]
            if source["kind"] == "partField":
                field = source["field"]
                if field == "part.instructions":
                    value = part["instructions"]
                else:
                    qid = source["questionId"]
                    if qid not in by_id or qid not in group_qids:
                        _fail("INVALID_REFERENCE")
                    key = field.split(".", 1)[1]
                    value = by_id[qid].get(key, "")
                start, end = source["startUtf16"], source["endUtf16"]
                segment = _utf16_slice(value, start, end)
                if segment is None:
                    _fail("INVALID_UTF16_RANGE")
                if segment != text["text"]:
                    _fail("SOURCE_TEXT_MISMATCH")
            else:
                resource = source["resource"]
                raw = source_bytes.get(resource)
                if not isinstance(raw, bytes):
                    _fail("SOURCE_RESOURCE_MISSING")
                if sha256(raw) != source["sha256"]:
                    _fail("SOURCE_HASH_MISMATCH")
                normalized = _normalized_resource(resource, raw)
                segment = _utf16_slice(normalized, source["startUtf16"], source["endUtf16"])
                if segment is None:
                    _fail("INVALID_UTF16_RANGE")
                if segment != text["text"]:
                    _fail("SOURCE_TEXT_MISMATCH")


def _valid_review_record(record: object) -> bool:
    if not isinstance(record, dict):
        return False
    common = {
        "reviewId", "partId", "groupId", "imageSha256", "rectPx", "method",
        "sourceResource", "sourceSha256", "sourceLocator", "explanation",
    }
    connector_record = "questionId" in record or "text" in record
    identity = {"questionId", "text"} if connector_record else {"slotId"}
    required = common | identity
    archive_fields = {"sourceArchive", "sourceArchiveSha256"}
    record_keys = frozenset(record)
    if record_keys not in {frozenset(required), frozenset(required | archive_fields)}:
        return False
    if any(not isinstance(record[field], str) or not record[field] for field in
           (("partId", "sourceResource", "sourceLocator", "explanation", "questionId", "text")
            if connector_record else ("partId", "sourceResource", "sourceLocator", "explanation"))):
        return False
    if (not isinstance(record["reviewId"], str) or not isinstance(record["groupId"], str)
            or (not connector_record and not isinstance(record["slotId"], str))
            or not isinstance(record["method"], str)
            or not isinstance(record["imageSha256"], str) or not isinstance(record["sourceSha256"], str)
            or ID_RE.fullmatch(record["reviewId"]) is None or ID_RE.fullmatch(record["groupId"]) is None
            or (not connector_record and ID_RE.fullmatch(record["slotId"]) is None)
            or (connector_record and ID_RE.fullmatch(record["questionId"]) is None)
            or SHA256_RE.fullmatch(record["imageSha256"]) is None
            or SHA256_RE.fullmatch(record["sourceSha256"]) is None
            or record["method"] not in {"sourceMarkup", "manualOverlayReview", "manualConnectorReview",
                                        "syntheticExplicitCoordinate"}
            or len(record["sourceResource"]) > 512 or len(record["sourceLocator"]) > 512
            or len(record["explanation"]) > 2_000 or not _safe_archive_path(record["sourceResource"])
            ):
        return False
    if connector_record != (record["method"] == "manualConnectorReview"):
        return False
    if archive_fields <= record.keys():
        if (not isinstance(record["sourceArchive"], str) or not record["sourceArchive"]
                or not isinstance(record["sourceArchiveSha256"], str)
                or SHA256_RE.fullmatch(record["sourceArchiveSha256"]) is None):
            return False
    try:
        rect = _shape_rect(record["rectPx"])
    except LayoutV3ValidationError:
        return False
    return bool(rect)


def _safe_archive_path(value: str) -> bool:
    return (not value.startswith("/") and "\\" not in value and "\x00" not in value
            and re.match(r"^[A-Za-z]:", value) is None
            and all(component not in {"", ".", ".."} for component in value.split("/")))


def _check_reviews(overlays: list[tuple[dict[str, Any], str, str]],
                   connectors: list[tuple[dict[str, Any], str, str]],
                   layout: dict[str, Any], part: dict[str, Any], source_bytes: dict[str, bytes],
                   image_metadata: dict[str, dict[str, Any]], review_records: list[dict[str, Any]],
                   source_archive_hashes: dict[str, str]) -> None:
    records_by_id: dict[str, list[dict[str, Any]]] = {}
    for record in review_records:
        if isinstance(record, dict) and isinstance(record.get("reviewId"), str):
            records_by_id.setdefault(record["reviewId"], []).append(record)

    for item, group_id, asset in [*overlays, *connectors]:
        evidence = item["evidence"]
        records = records_by_id.get(evidence["reviewId"], [])
        if len(records) != 1 or not _valid_review_record(records[0]):
            _fail("REVIEW_RECORD_MISSING")
        record = records[0]
        is_connector = "questionId" in item
        same_identity = (
            record.get("questionId") == item["questionId"] and record.get("text") == item["text"]
            if is_connector else record.get("slotId") == item["slotId"]
        )
        if (record["partId"] != part["id"] or record["groupId"] != group_id
                or not same_identity or record["rectPx"] != item["rectPx"]
                or record["imageSha256"] != evidence["imageSha256"]
                or record["imageSha256"] != image_metadata[asset]["sha256"]):
            _fail("REVIEW_RECORD_MISSING")
        raw = source_bytes.get(record["sourceResource"])
        if not isinstance(raw, bytes):
            _fail("SOURCE_RESOURCE_MISSING")
        if sha256(raw) != record["sourceSha256"]:
            _fail("REVIEW_RECORD_MISSING")
        if "sourceArchive" in record:
            if source_archive_hashes.get(record["sourceArchive"]) != record["sourceArchiveSha256"]:
                _fail("REVIEW_RECORD_MISSING")
        if record["method"] == "manualConnectorReview" and "sourceArchive" not in record:
            _fail("REVIEW_RECORD_MISSING")
        if record["method"] == "syntheticExplicitCoordinate":
            if (part.get("examType") != "synthetic"
                    or record["sourceSha256"] != image_metadata[asset]["sha256"]):
                _fail("REVIEW_RECORD_MISSING")


def validate_layout_v3(layout_bytes: bytes, part_json_bytes: bytes, *, source_bytes: dict[str, bytes],
                       image_metadata: dict[str, dict], review_records: list[dict],
                       source_archive_hashes: dict[str, str] | None = None) -> dict:
    """Validate exact layout and Part bytes, returning the decoded validated layout.

    Error precedence follows layout-schema-v3-contract.md. Exceptions expose only a stable reason code.
    """
    if not isinstance(layout_bytes, bytes) or len(layout_bytes) > MAX_DOCUMENT_BYTES:
        _fail("INVALID_LAYOUT_SHAPE")
    layout = _strict_json(layout_bytes, "INVALID_LAYOUT_JSON")
    if not isinstance(layout, dict):
        _fail("INVALID_LAYOUT_SHAPE")
    version = layout.get("schemaVersion")
    if not _is_int(version):
        _fail("INVALID_LAYOUT_SHAPE")
    if version != 3:
        _fail("UNSUPPORTED_LAYOUT_SCHEMA")

    layout = _shape_layout(layout)
    if not isinstance(part_json_bytes, bytes):
        _fail("INVALID_BASE_PART_JSON")
    if sha256(part_json_bytes) != layout["basePartSha256"]:
        _fail("BASE_PART_HASH_MISMATCH")
    part = _strict_json(part_json_bytes, "INVALID_BASE_PART_JSON")
    try:
        valid_part = _part_valid(part)
    except (KeyError, TypeError, ValueError, IndexError, UnicodeError, RecursionError):
        valid_part = False
    if not valid_part:
        _fail("INVALID_BASE_PART_JSON")

    if not isinstance(source_bytes, dict) or any(not isinstance(key, str) or not isinstance(value, bytes)
                                                  for key, value in source_bytes.items()):
        _fail("INVALID_LAYOUT_SHAPE")
    if not isinstance(image_metadata, dict):
        _fail("INVALID_LAYOUT_SHAPE")
    if not isinstance(review_records, list):
        _fail("INVALID_LAYOUT_SHAPE")
    if source_archive_hashes is None:
        source_archive_hashes = {}
    if (not isinstance(source_archive_hashes, dict)
            or any(not isinstance(path, str) or not isinstance(digest, str)
                   or SHA256_RE.fullmatch(digest) is None for path, digest in source_archive_hashes.items())):
        _fail("INVALID_LAYOUT_SHAPE")

    by_id, order = _check_group_ownership(layout, part)
    _check_response_rules(layout["groups"], by_id, order)
    slots_by_id, response_by_question = _check_slots(layout["groups"])
    placements, overlays, connectors = _check_blocks(layout, part, image_metadata, slots_by_id)
    _check_answer_connectors(layout["groups"], part)
    _check_slot_placements(layout["groups"], placements, slots_by_id, response_by_question)
    _check_option_pools(layout["groups"], by_id, response_by_question)
    _check_text_sources(layout["groups"], part, by_id, source_bytes)
    _check_reviews(overlays, connectors, layout, part, source_bytes, image_metadata, review_records,
                   source_archive_hashes)
    return layout
