"""Regenerate public, wholly synthetic v2 contract fixtures; no private inputs."""
from __future__ import annotations

import hashlib
import json
import struct
import zlib
from pathlib import Path


HERE = Path(__file__).parent


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def write_json(name: str, value: object) -> bytes:
    raw = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    (HERE / name).write_bytes(raw)
    return raw


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def make_png() -> bytes:
    width = height = 100
    pixels = b"".join(b"\0" + bytes((235, 242, 250)) * width for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(pixels)) + png_chunk(b"IEND", b""))


def question(number: int, kind: str, prompt: str, answer: str, *, options=None, **extra) -> dict:
    return {"id": f"q{number}", "number": number, "type": kind, "prompt": prompt,
            "options": options or [], "correctAnswer": answer, **extra}


def text(node_id: str, value: str, field: str, question_id: str | None = None,
         *, source_value: str | None = None, connector_for: str | None = None) -> dict:
    source_value = source_value if source_value is not None else value
    start = source_value.index(value)
    source = {"kind": "partField", "field": field, "startUtf16": len(source_value[:start].encode("utf-16-le")) // 2,
              "endUtf16": len(source_value[:start + len(value)].encode("utf-16-le")) // 2}
    if question_id is not None:
        source["questionId"] = question_id
    node = {"id": node_id, "type": "text", "textId": node_id, "text": value, "source": source}
    if connector_for is not None:
        node["role"] = "answerConnector"
        node["questionId"] = connector_for
    return node


def slot(number: int, index: int = 0, kind: str = "fill") -> dict:
    return {"slotId": f"s{number}-{index}", "questionId": f"q{number}", "slotIndex": index, "kind": kind}


def placed(number: int, index: int = 0) -> dict:
    return {"id": f"place-{number}-{index}", "type": "slot", "slotId": f"s{number}-{index}"}


def group(name: str, questions: list[dict], *, instructions=None, pools=None, slots=None,
          responses=None, blocks=None) -> dict:
    return {"groupId": name, "questionIds": [q["id"] for q in questions],
            "promptHashes": {q["id"]: sha(q["prompt"].encode("utf-8")) for q in questions},
            "instructions": instructions or [], "optionPools": pools or [], "slots": slots or [],
            "responses": responses or [], "blocks": blocks or []}


def choice_rule(kind: str, numbers: list[int], pool_id: str, scoring="legacyPerQuestion", **extra) -> dict:
    return {"kind": kind, "questionIds": [f"q{n}" for n in numbers],
            "slotIds": [f"s{n}-0" for n in numbers], "scoring": scoring, "optionPoolId": pool_id, **extra}


def main() -> None:
    image_bytes = make_png()
    (HERE / "map.png").write_bytes(image_bytes)
    image_hash = sha(image_bytes)
    html_bytes = b"<html><body><p>Supplemental label</p></body></html>\n"
    (HERE / "content.html").write_bytes(html_bytes)
    html_hash = sha(html_bytes)
    ab = [{"id": "A", "text": "Square"}, {"id": "B", "text": "Circle"}]
    abc = [{"id": "A", "text": "Red"}, {"id": "B", "text": "Blue"}, {"id": "C", "text": "Gold"}]
    xy = [{"id": "X", "text": "North"}, {"id": "Y", "text": "South"}]
    limit = {"maxWords": 2, "maxNumbers": 0, "numberOnly": False, "wordsOrNumber": False}
    qs = [
        question(1, "IMAGE_BASED", "Choose the shape.", "A", options=ab, images=["map.png"]),
        question(2, "MULTIPLE_CHOICE", "Select two colors.", "A", options=abc,
                 instructions="Choose TWO.", groupId="mc-2-3", groupNumbers=[2, 3]),
        question(3, "MULTIPLE_CHOICE", "Select two colors.", "C", options=abc,
                 instructions="Choose TWO.", groupId="mc-2-3", groupNumbers=[2, 3]),
        question(4, "MATCHING", "First direction.", "X", options=xy,
                 instructions="Match each item. Use each option once."),
        question(5, "MATCHING", "Second direction.", "Y", options=xy,
                 instructions="Match each item. Use each option once."),
        question(6, "TEXT_INPUT", "Name a color.", "blue", wordLimit=limit),
        question(7, "TEXT_INPUT", "Complete two terms.", "sun and moon", wordLimit=limit,
                 instructions="and", answerSeparator="and"),
        question(8, "TABLE_COMPLETION", "Object", "star", wordLimit=limit, context="Name Value"),
        question(9, "FLOW_COMPLETION", "Start", "finish", wordLimit=limit),
        question(10, "IMAGE_BASED", "Label the marker.", "point", wordLimit=limit, images=["map.png"]),
    ]
    part = {"schemaVersion": 2, "id": "example-part", "examType": "synthetic", "book": 5,
            "test": 1, "part": 1, "title": "Synthetic layout examples", "instructions": "Answer the examples.",
            "audioPath": "listening/example-part/audio.mp3", "audioSha256": "0" * 64,
            "transcript": {"english": "Example audio.", "chinese": "示例音频。",
                           "segments": [{"english": "Example audio.", "chinese": "示例音频。"}]},
            "questions": qs}
    part_hash = sha(write_json("part.json", part))
    groups = [
        group("example-single", qs[:1], pools=[{"poolId": "pool-shapes", "questionIds": ["q1"],
                                                  "optionIds": ["A", "B"]}],
              slots=[slot(1, kind="singleChoice")],
              responses=[choice_rule("singleChoice", [1], "pool-shapes")],
              blocks=[text("q1-label", qs[0]["prompt"], "question.prompt", "q1"),
                      {"id": "q1-image", "type": "image", "asset": "map.png", "widthPx": 100,
                       "heightPx": 100, "overlays": []},
                      {"id": "q1-choices", "type": "choiceList", "optionPoolId": "pool-shapes",
                       "slotIds": ["s1-0"]}]),
        group("example-multiple", qs[1:3],
              instructions=[text("multi-instruction", "Choose TWO.", "question.instructions", "q2")],
              pools=[{"poolId": "pool-colors", "questionIds": ["q2", "q3"],
                      "optionIds": ["A", "B", "C"]}],
              slots=[slot(2, kind="multipleChoice"), slot(3, kind="multipleChoice")],
              responses=[choice_rule("multipleChoice", [2, 3], "pool-colors",
                                     scoring="onePointPerCorrectOption", selectCount=2,
                                     order="unordered", storage="sameSortedSetOnEveryQuestion")],
              blocks=[text("multi-prompt", qs[1]["prompt"], "question.prompt", "q2"),
                      {"id": "multi-choices", "type": "choiceList", "optionPoolId": "pool-colors",
                       "slotIds": ["s2-0", "s3-0"]}]),
        group("example-matching", qs[3:5],
              instructions=[text("match-instruction", "Match each item. Use each option once.",
                                 "question.instructions", "q4")],
              pools=[{"poolId": "pool-directions", "questionIds": ["q4", "q5"],
                      "optionIds": ["X", "Y"]}],
              slots=[slot(4, kind="matching"), slot(5, kind="matching")],
              responses=[choice_rule("matching", [4, 5], "pool-directions", reuse="forbidden")],
              blocks=[{"id": "match-first", "type": "paragraph", "children": [
                          text("match-first-text", qs[3]["prompt"], "question.prompt", "q4"),
                          {"id": "match-first-choice", "type": "choiceList", "optionPoolId": "pool-directions",
                           "slotIds": ["s4-0"]}]},
                      {"id": "match-second", "type": "paragraph", "children": [
                          text("match-second-text", qs[4]["prompt"], "question.prompt", "q5"),
                          {"id": "match-second-choice", "type": "choiceList", "optionPoolId": "pool-directions",
                           "slotIds": ["s5-0"]}]}]),
        group("example-fill", qs[5:6], slots=[slot(6)],
              responses=[{"kind": "fill", "questionIds": ["q6"], "slotIds": ["s6-0"],
                          "scoring": "legacyPerQuestion"}],
              blocks=[{"id": "fill-line", "type": "paragraph", "children": [
                  {"id": "source-label", "type": "text", "textId": "source-label",
                   "text": "Supplemental label", "source": {"kind": "resourceRange", "resource": "content.html",
                   "sha256": html_hash, "normalization": "htmlTextV1", "startUtf16": 0, "endUtf16": 18}},
                  text("fill-prompt", qs[5]["prompt"], "question.prompt", "q6"), placed(6)]}]),
        group("example-double", qs[6:7], slots=[slot(7), slot(7, 1)],
              responses=[{"kind": "fill", "questionIds": ["q7"], "slotIds": ["s7-0", "s7-1"],
                          "scoring": "legacyPerQuestion"}],
              blocks=[text("double-prompt", qs[6]["prompt"], "question.prompt", "q7"),
                      {"id": "double-line", "type": "paragraph", "children": [
                          placed(7), text("double-connector", "and", "question.instructions", "q7",
                                          connector_for="q7"),
                          placed(7, 1)]}]),
        group("example-table", qs[7:8], slots=[slot(8)],
              responses=[{"kind": "fill", "questionIds": ["q8"], "slotIds": ["s8-0"],
                          "scoring": "legacyPerQuestion"}],
              blocks=[{"id": "table", "type": "table", "rows": [
                  {"id": "row-header", "cells": [
                      {"id": "cell-name", "rowSpan": 1, "colSpan": 1, "children": [
                          text("table-name", "Name", "question.context", "q8", source_value="Name Value")]},
                      {"id": "cell-value", "rowSpan": 1, "colSpan": 1, "children": [
                          text("table-value", "Value", "question.context", "q8", source_value="Name Value")]}]},
                  {"id": "row-object", "cells": [
                      {"id": "cell-object", "rowSpan": 1, "colSpan": 1, "children": [
                          text("table-object", "Object", "question.prompt", "q8")]},
                      {"id": "cell-answer", "rowSpan": 1, "colSpan": 1, "children": [placed(8)]}]}]}]),
        group("example-flow", qs[8:9], slots=[slot(9)],
              responses=[{"kind": "fill", "questionIds": ["q9"], "slotIds": ["s9-0"],
                          "scoring": "legacyPerQuestion"}],
              blocks=[{"id": "flow", "type": "flow", "nodes": [
                  {"id": "flow-start", "children": [text("flow-start-text", "Start", "question.prompt", "q9")]},
                  {"id": "flow-end", "children": [placed(9)]}],
                  "edges": [{"from": "flow-start", "to": "flow-end"}]}]),
        group("example-image-fill", qs[9:10], slots=[slot(10)],
              responses=[{"kind": "fill", "questionIds": ["q10"], "slotIds": ["s10-0"],
                          "scoring": "legacyPerQuestion"}],
              blocks=[text("image-prompt", qs[9]["prompt"], "question.prompt", "q10"),
                      {"id": "map-image", "type": "image", "asset": "map.png", "widthPx": 100,
                       "heightPx": 100, "overlays": [{"slotId": "s10-0",
                           "rectPx": {"x": 35, "y": 40, "width": 25, "height": 12},
                           "evidence": {"kind": "reviewedMeasurement", "reviewId": "review-map-slot",
                                        "imageSha256": image_hash}}]}]),
    ]
    layout = {"schemaVersion": 2, "partId": "example-part", "basePartSha256": part_hash,
              "groups": groups}
    write_json("layout.json", layout)
    write_json("context.json", {"images": {"map.png": {"sha256": image_hash, "widthPx": 100,
                                                       "heightPx": 100}},
                                "sources": {"content.html": {"sha256": html_hash}},
                                "reviews": [{"reviewId": "review-map-slot", "imageSha256": image_hash,
                                             "partId": "example-part", "groupId": "example-image-fill",
                                             "slotId": "s10-0",
                                             "rectPx": {"x": 35, "y": 40, "width": 25, "height": 12},
                                             "method": "syntheticExplicitCoordinate",
                                             "sourceResource": "map.png", "sourceSha256": image_hash,
                                             "sourceLocator": "whole-image",
                                             "explanation": "The fixture itself defines this rectangle; no real source is used."}]})


if __name__ == "__main__":
    main()
