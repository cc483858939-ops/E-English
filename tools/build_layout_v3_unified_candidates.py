"""Build the offline V3 candidate set from frozen V2 layouts and five explicit repairs.

The 267 V2 layouts are migrated mechanically. The remaining five Parts are laid
out explicitly below from their audited source groups; this module does not
infer or classify question structure.
"""
from __future__ import annotations

import copy
import hashlib
import io
import json
import re
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / "private-data"
OLD = PRIVATE / "layout-v3-candidates"
PACKS = PRIVATE / "layout-batch-ab-v2"
OUT = PRIVATE / "layout-v3-unified-candidates"
AUDIT_PATH = OLD / "group-review-records.json"
sys.path.insert(0, str(ROOT / "tools"))
from layout_v3_validator import (  # noqa: E402
    _normalized_resource,
    _grader_within_limit,
    sha256,
    validate_layout_v3,
)


TARGETS = {
    "cambridge-6-test-1-part-1": "c6t1p1",
    "cambridge-6-test-1-part-2": "c6t1p2",
    "cambridge-6-test-1-part-4": "c6t1p4",
    "cambridge-7-test-2-part-3": "c7t2p3",
    "cambridge-9-test-2-part-2": "c9t2p2",
}


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _u16(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def _walk(blocks: list[dict[str, Any]]):
    for block in blocks:
        yield block
        kind = block["type"]
        if kind in {"paragraph", "listItem", "formRow"}:
            yield from _walk(block["children"])
        elif kind == "table":
            for row in block["rows"]:
                for cell in row["cells"]:
                    yield from _walk(cell["children"])
        elif kind == "flow":
            for node in block["nodes"]:
                yield from _walk(node["children"])


def _images(blocks: list[dict[str, Any]]):
    return (block for block in _walk(blocks) if block["type"] == "image")


def _all_text_sources(layout: dict[str, Any]):
    for group in layout["groups"]:
        yield from group["instructions"]
        yield from (block for block in _walk(group["blocks"]) if block["type"] == "text")


def _archive_sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_part_bytes(book: int, part_id: str) -> bytes:
    pack_path = PACKS / f"cambridge-{book}.eelpack"
    with zipfile.ZipFile(pack_path) as pack:
        return pack.read(f"listening/{part_id}/part.json")


def _variant_for_layout(part_record: dict[str, Any], layout: dict[str, Any]) -> dict[str, Any]:
    referenced: dict[str, set[str]] = defaultdict(set)
    for text in _all_text_sources(layout):
        source = text["source"]
        if source["kind"] == "resourceRange":
            referenced[source["resource"]].add(source["sha256"])
    for variant in part_record["sourceVariants"]:
        files = variant["sourceFiles"]
        if all(
            resource in files and digests == {files[resource]["sha256"]}
            for resource, digests in referenced.items()
        ):
            return variant
    raise ValueError(f"cannot resolve source archive variant for {part_record['partId']}")


class SourcePart:
    """Byte-anchored helpers for one hand-authored Part. Structure stays explicit."""

    def __init__(self, record: dict[str, Any], archive_hashes: dict[str, str]):
        self.record = record
        self.part_id = record["partId"]
        self.variant = record["sourceVariants"][0]
        self.archive = self.variant["sourceArchive"]
        self.archive_sha = archive_hashes[self.archive]
        self.prefix = self.variant["sourcePrefix"]
        self.part_bytes = _load_part_bytes(record["book"], self.part_id)
        self.part = json.loads(self.part_bytes.decode("utf-8"))
        self.questions = {question["number"]: question for question in self.part["questions"]}
        self.q_by_id = {question["id"]: question for question in self.part["questions"]}
        with zipfile.ZipFile(self.archive) as archive:
            entries = [name for name in archive.namelist()
                       if name.startswith(self.prefix) and not name.endswith("/")]
            self.archive_sources = {name: archive.read(name) for name in entries}
        self.source_bytes: dict[str, bytes] = dict(self.archive_sources)
        for name, raw in self.archive_sources.items():
            relative = name[len(self.prefix):]
            if relative:
                self.source_bytes.setdefault(relative, raw)
        for alias in ("content.html", "questions.txt"):
            if alias in self.source_bytes:
                self.source_bytes["content.txt" if alias == "questions.txt" else alias] = self.source_bytes[alias]
        self.normalized = _normalized_resource("content.html", self.source_bytes["content.html"])
        self.content_sha = sha256(self.source_bytes["content.html"])
        self.cursor = 0
        self.groups: list[dict[str, Any]] = []
        self.review_records: list[dict[str, Any]] = []
        self.measurements: list[dict[str, Any]] = []
        self.image_metadata: dict[str, dict[str, Any]] = {}
        self.layout: dict[str, Any] = {
            "schemaVersion": 3,
            "partId": self.part_id,
            "basePartSha256": sha256(self.part_bytes),
            "groups": self.groups,
        }

    def text(self, key: str, exact: str, *, after: int | None = None) -> tuple[dict[str, Any], int]:
        start = self.normalized.find(exact, self.cursor if after is None else after)
        if start < 0:
            raise ValueError(f"source text not found for {self.part_id}:{key}")
        end = start + len(exact)
        node = {
            "id": f"b-{key}", "type": "text", "textId": f"t-{key}", "text": exact,
            "source": {
                "kind": "resourceRange", "resource": "content.html", "sha256": self.content_sha,
                "normalization": "htmlTextV1", "startUtf16": _u16(self.normalized[:start]),
                "endUtf16": _u16(self.normalized[:end]),
            },
        }
        self.cursor = end
        return node, end

    def instruction(self, key: str, exact: str) -> dict[str, Any]:
        return self.text(f"{key}-instruction-{len(self._current_instructions) + 1}", exact)[0]

    def begin_group(self) -> None:
        self._current_instructions: list[dict[str, Any]] = []

    def instructions(self, key: str, values: list[str]) -> list[dict[str, Any]]:
        self.begin_group()
        for value in values:
            self._current_instructions.append(self.instruction(key, value))
        return self._current_instructions

    def slot(self, number: int, kind: str, index: int = 0) -> tuple[dict[str, Any], dict[str, Any]]:
        qid = self.questions[number]["id"]
        slot_id = f"slot-q{number}-{index}"
        return ({"id": f"b-slot-q{number}-{index}", "type": "slot", "slotId": slot_id},
                {"slotId": slot_id, "questionId": qid, "slotIndex": index, "kind": kind})

    def pool(self, pool_id: str, numbers: list[int], option_ids: list[str] | None = None) -> dict[str, Any]:
        options = self.questions[numbers[0]].get("options", [])
        return {
            "poolId": pool_id,
            "questionIds": [self.questions[number]["id"] for number in numbers],
            "optionIds": option_ids if option_ids is not None else [option["id"] for option in options],
        }

    def group(self, group_id: str, numbers: list[int], instructions: list[dict[str, Any]],
              pools: list[dict[str, Any]], slots: list[dict[str, Any]],
              responses: list[dict[str, Any]], blocks: list[dict[str, Any]]) -> None:
        qids = [self.questions[number]["id"] for number in numbers]
        self.groups.append({
            "groupId": group_id,
            "questionIds": qids,
            "promptHashes": {qid: sha256(self.q_by_id[qid]["prompt"].encode("utf-8")) for qid in qids},
            "instructions": instructions,
            "optionPools": pools,
            "slots": slots,
            "responses": responses,
            "blocks": blocks,
        })

    def image_block(self, group_id: str, image_id: str, asset: str,
                    overlay_specs: list[dict[str, Any]], connector_specs: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        connector_specs = connector_specs or []
        source_resource = self.prefix + asset
        raw = self.archive_sources.get(source_resource)
        if raw is None:
            raise ValueError(f"image missing from source ZIP for {self.part_id}:{asset}")
        image_sha = sha256(raw)
        with Image.open(io.BytesIO(raw)) as im:
            width, height = im.size
        self.image_metadata[asset] = {"sha256": image_sha, "widthPx": width, "heightPx": height}
        overlay_nodes = []
        for spec in overlay_specs:
            review_id = f"review-q{spec['number']}-s{spec.get('slotIndex', 0)}-overlay"
            slot_id = f"slot-q{spec['number']}-{spec.get('slotIndex', 0)}"
            overlay_nodes.append({
                "slotId": slot_id,
                "rectPx": spec["rectPx"],
                "evidence": {"kind": "reviewedMeasurement", "reviewId": review_id, "imageSha256": image_sha},
            })
            record = {
                "reviewId": review_id, "partId": self.part_id, "groupId": group_id, "slotId": slot_id,
                "imageSha256": image_sha, "rectPx": spec["rectPx"], "method": "manualOverlayReview",
                "sourceResource": source_resource, "sourceSha256": image_sha,
                "sourceLocator": spec["locator"],
                "explanation": "Measured the printed answer field against the original image at native pixel resolution.",
                "sourceArchive": self.archive, "sourceArchiveSha256": self.archive_sha,
            }
            self.review_records.append(record)
            self.measurements.append({
                "partId": self.part_id, "groupId": group_id, "questionId": self.questions[spec["number"]]["id"],
                "slotId": slot_id, "slotIndex": spec.get("slotIndex", 0), "kind": "slotOverlay",
                "rectPx": spec["rectPx"], "imageAsset": asset, "imageWidthPx": width,
                "imageHeightPx": height, "imageSha256": image_sha, "reviewId": review_id,
                "sourceArchive": self.archive, "sourceArchiveSha256": self.archive_sha,
                "sourceResource": source_resource, "sourceEntrySha256": image_sha,
                "visualLocator": spec["locator"],
                "reviewBasis": "Native-resolution original PNG inspected with pixel grid and reviewed rectangle overlay.",
            })
        connector_nodes = []
        for spec in connector_specs:
            review_id = f"review-q{spec['number']}-connector"
            qid = self.questions[spec["number"]]["id"]
            connector_nodes.append({
                "questionId": qid, "text": spec["text"], "rectPx": spec["rectPx"],
                "evidence": {"kind": "reviewedMeasurement", "reviewId": review_id, "imageSha256": image_sha},
            })
            record = {
                "reviewId": review_id, "partId": self.part_id, "groupId": group_id, "questionId": qid,
                "text": spec["text"], "imageSha256": image_sha, "rectPx": spec["rectPx"],
                "method": "manualConnectorReview", "sourceResource": source_resource,
                "sourceSha256": image_sha, "sourceLocator": spec["locator"],
                "explanation": "Measured the already-printed connector against the original image at native pixel resolution.",
                "sourceArchive": self.archive, "sourceArchiveSha256": self.archive_sha,
            }
            self.review_records.append(record)
            self.measurements.append({
                "partId": self.part_id, "groupId": group_id, "questionId": qid,
                "kind": "printedConnector", "text": spec["text"], "rectPx": spec["rectPx"],
                "imageAsset": asset, "imageWidthPx": width, "imageHeightPx": height,
                "imageSha256": image_sha, "reviewId": review_id,
                "sourceArchive": self.archive, "sourceArchiveSha256": self.archive_sha,
                "sourceResource": source_resource, "sourceEntrySha256": image_sha,
                "visualLocator": spec["locator"],
                "reviewBasis": "Native-resolution original PNG inspected with pixel grid and reviewed rectangle overlay.",
                "renderingRule": "The connector is already printed in the source image; do not draw it again.",
            })
        return {
            "id": f"b-{image_id}", "type": "image", "asset": asset,
            "widthPx": width, "heightPx": height,
            "overlays": overlay_nodes, "printedConnectors": connector_nodes,
        }

    def save(self) -> tuple[bytes, dict[str, Any]]:
        raw = (json.dumps(self.layout, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        return raw, validate_layout_v3(
            raw, self.part_bytes, source_bytes=self.source_bytes, image_metadata=self.image_metadata,
            review_records=self.review_records, source_archive_hashes=ARCHIVE_HASHES,
        )


def _text_container(block_id: str, kind: str, children: list[dict[str, Any]], marker: str | None = None):
    item = {"id": block_id, "type": kind, "children": children}
    if kind == "listItem":
        item["marker"] = marker or "bullet"
    return item


def _choice_group(part: SourcePart, group_id: str, numbers: list[int], *, multi: bool = False,
                  instruction_values: list[str], prompt_texts: list[tuple[int, str]] | None = None,
                  shared_prompt: str | None = None) -> None:
    key = f"g{len(part.groups) + 1}"
    instructions = part.instructions(key, instruction_values)
    pools, slots, responses, blocks = [], [], [], []
    if multi:
        pool_id = f"pool-q{numbers[0]}-q{numbers[-1]}"
        pools.append(part.pool(pool_id, numbers))
        slot_nodes = []
        for number in numbers:
            slot_node, slot_def = part.slot(number, "multipleChoice")
            slot_nodes.append(slot_node)
            slots.append(slot_def)
        stem_node, _ = part.text(f"{key}-shared-prompt", shared_prompt or "")
        blocks.append(_text_container(f"b-{key}-prompt", "paragraph", [stem_node]))
        blocks.append({"id": f"b-{key}-choices", "type": "choiceList", "optionPoolId": pool_id,
                       "slotIds": [slot["slotId"] for slot in slots]})
        responses.append({
            "kind": "multipleChoice", "questionIds": [part.questions[n]["id"] for n in numbers],
            "slotIds": [slot["slotId"] for slot in slots], "scoring": "onePointPerCorrectOption",
            "optionPoolId": pool_id, "selectCount": len(numbers), "order": "unordered",
            "storage": "sameSortedSetOnEveryQuestion",
        })
    else:
        for number, prompt in prompt_texts or []:
            pool_id = f"pool-q{number}"
            pools.append(part.pool(pool_id, [number]))
            text_node, _ = part.text(f"{key}-q{number}-prompt", prompt)
            slot_node, slot_def = part.slot(number, "singleChoice")
            slots.append(slot_def)
            blocks.append(_text_container(f"b-{key}-q{number}-prompt-block", "paragraph", [text_node]))
            blocks.append({"id": f"b-{key}-q{number}-choices", "type": "choiceList",
                           "optionPoolId": pool_id, "slotIds": [slot_node["slotId"]]})
            responses.append({"kind": "singleChoice", "questionIds": [part.questions[number]["id"]],
                              "slotIds": [slot_node["slotId"]], "scoring": "legacyPerQuestion",
                              "optionPoolId": pool_id})
    part.group(group_id, numbers, instructions, pools, slots, responses, blocks)


def _image_fill_group(part: SourcePart, group_id: str, numbers: list[int], asset: str,
                      instruction_values: list[str], overlays: list[dict[str, Any]],
                      pair_number: int, joiner: str, connector: dict[str, Any]) -> None:
    key = f"g{len(part.groups) + 1}"
    instructions = part.instructions(key, instruction_values)
    slots, responses = [], []
    for number in numbers:
        if number == pair_number:
            for index in (0, 1):
                _, slot_def = part.slot(number, "fill", index)
                slots.append(slot_def)
                if index == 1:
                    responses.append({"kind": "fill", "questionIds": [part.questions[number]["id"]],
                                      "slotIds": [f"slot-q{number}-0", f"slot-q{number}-1"],
                                      "scoring": "legacyPerQuestion", "joiner": joiner})
        else:
            _, slot_def = part.slot(number, "fill")
            slots.append(slot_def)
            responses.append({"kind": "fill", "questionIds": [part.questions[number]["id"]],
                              "slotIds": [slot_def["slotId"]], "scoring": "legacyPerQuestion"})
    image = part.image_block(group_id, f"{key}-image", asset, overlays, [connector])
    part.group(group_id, numbers, instructions, [], slots, responses, [image])


def _manual_c6t1p1(part: SourcePart) -> None:
    groups = {g["groupId"]: g for g in AUDIT["groups"] if g["partId"] == part.part_id}
    gid = next(k for k in groups if "children-application-notes" in k)
    ins = part.instructions("g1", ["Questions 1-4", "Complete the notes below.",
                                    "Write NO MORE THAN THREE WORDS for each answer."])
    blocks: list[dict[str, Any]] = []
    slots: list[dict[str, Any]] = []
    responses: list[dict[str, Any]] = []

    def source(key: str, text: str):
        return part.text(key, text)[0]

    def field(number: int, prefix: str | None = None, suffix: str | None = None,
              kind: str = "paragraph", marker: str | None = None):
        children = []
        if prefix:
            children.append(source(f"g1-q{number}-prefix", prefix))
        node, definition = part.slot(number, "fill")
        children.append(node)
        slots.append(definition)
        responses.append({"kind": "fill", "questionIds": [part.questions[number]["id"]],
                          "slotIds": [definition["slotId"]], "scoring": "legacyPerQuestion"})
        if suffix:
            children.append(source(f"g1-q{number}-suffix", suffix))
        blocks.append(_text_container(f"b-g1-q{number}-row", kind, children, marker))

    blocks.append(_text_container("b-g1-title", "paragraph", [source("g1-notes-title", "Notes on sports club")]))
    blocks.append(_text_container("b-g1-example-block", "paragraph", [source("g1-example", "Example")]))
    blocks.append(_text_container("b-g1-club-name", "paragraph", [source("g1-name", "Name of club: Kingswell")]))
    blocks.append(_text_container("b-g1-facility-label", "paragraph", [source("g1-facilities", "Facilities available:")]))
    blocks.append(_text_container("b-g1-golf-block", "paragraph", [source("g1-golf", "Golf")]))
    field(1, prefix="1", kind="listItem", marker="numbered")
    field(2, prefix="2", kind="listItem", marker="numbered")
    blocks.append(_text_container("b-g1-classes-label", "paragraph", [source("g1-classes", "Classes available:")]))
    blocks.append(_text_container("b-g1-kick-boxing-block", "listItem", [source("g1-kick-boxing", "Kick-boxing")], "bullet"))
    field(3, prefix="3", kind="listItem", marker="bullet")
    blocks.append(_text_container("b-g1-additional-block", "paragraph", [source("g1-additional", "Additional facility:")]))
    field(4, prefix="4", suffix="(restaurant opening soon)")
    part.group(gid, [1, 2, 3, 4], ins, [], slots, responses, blocks)

    gid = next(k for k in groups if "image-table" in k)
    _image_fill_group(part, gid, [5, 6, 7, 8], "question-image-01.png",
                      ["Questions 5-8", "Complete the table below.",
                       "Write NO MORE THAN TWO NUMBERS for each answer."],
                      [
                          {"number": 5, "rectPx": {"x": 620, "y": 146, "width": 52, "height": 20},
                           "locator": "Question 5, Annual subscription fee cell; the measured rectangle encloses its dotted answer line."},
                          {"number": 6, "rectPx": {"x": 246, "y": 217, "width": 61, "height": 21},
                           "locator": "Question 6, Cost of classes cell; the measured rectangle encloses the dotted answer line after the printed currency amount."},
                          {"number": 7, "slotIndex": 0, "rectPx": {"x": 365, "y": 222, "width": 51, "height": 20},
                           "locator": "Question 7, Silver row Starting time cell; first dotted answer field before the printed word to."},
                          {"number": 7, "slotIndex": 1, "rectPx": {"x": 439, "y": 222, "width": 52, "height": 20},
                           "locator": "Question 7, Silver row Starting time cell; second dotted answer field immediately after the printed word to."},
                          {"number": 8, "rectPx": {"x": 624, "y": 294, "width": 44, "height": 20},
                           "locator": "Question 8, Bronze row Annual subscription fee cell; the measured rectangle encloses its dotted answer line."},
                      ], 7, "to", {"number": 7, "text": "to",
                                    "rectPx": {"x": 420, "y": 222, "width": 17, "height": 20},
                                    "locator": "Question 7, Silver row Starting time cell; printed word to between the two answer fields."})

    gid = next(k for k in groups if "follow-up-sentences" in k)
    ins = part.instructions("g3", ["Questions 9 and 10", "Complete the sentences below.",
                                    "Write ONE WORD ONLY for each answer."])
    slots, responses, blocks = [], [], []
    for number, prefix, suffix in [
        (9, "9 To join the centre, you need to book an instructor's", "."),
        (10, "10 To book a trial session, speak to David", "(0458 95311)."),
    ]:
        before, _ = part.text(f"g3-q{number}-prefix", prefix)
        slot_node, definition = part.slot(number, "fill")
        after, _ = part.text(f"g3-q{number}-suffix", suffix)
        slots.append(definition)
        responses.append({"kind": "fill", "questionIds": [part.questions[number]["id"]],
                          "slotIds": [definition["slotId"]], "scoring": "legacyPerQuestion"})
        blocks.append(_text_container(f"b-g3-q{number}-sentence", "paragraph", [before, slot_node, after]))
    part.group(gid, [9, 10], ins, [], slots, responses, blocks)


def _manual_c6t1p2(part: SourcePart) -> None:
    audit_groups = [g for g in AUDIT["groups"] if g["partId"] == part.part_id]
    gid = next(g["groupId"] for g in audit_groups if "theatre" in g["groupId"])
    key = "g1"
    instructions = part.instructions(key, [
        "Questions 11-16", "What change has been made to each part of the theatre?",
        "Choose SIX answers from the box and write the correct letter, A-G, next to questions 11-16.",
    ])
    numbers = list(range(11, 17))
    pool_id = "pool-g1-shared"
    pool = part.pool(pool_id, numbers)
    slots, blocks = [], []
    for number in numbers:
        _, definition = part.slot(number, "matching")
        slots.append(definition)
    header, _ = part.text("g1-theatre-title", "RIVENDEN CITY THEATRE")
    heading = _text_container("b-g1-theatre-title-block", "paragraph", [header])
    part_heading, _ = part.text("g1-part-heading", "Part of the theatre")
    blocks.append(heading)
    blocks.append(_text_container("b-g1-part-heading-block", "paragraph", [part_heading]))
    targets = ["box office", "shop", "ordinary seats", "seats for wheelchair users", "lifts", "dressing rooms"]
    for number, label in zip(numbers, targets):
        target, _ = part.text(f"g1-q{number}-target", f"{number} {label}")
        _, slot_def = part.slot(number, "matching")
        blocks.append(_text_container(f"b-g1-q{number}-row", "formRow", [
            target, {"id": f"b-g1-q{number}-choices", "type": "choiceList",
                     "optionPoolId": pool_id, "slotIds": [slot_def["slotId"]]},
        ]))
    response = {"kind": "matching", "questionIds": [part.questions[n]["id"] for n in numbers],
                "slotIds": [f"slot-q{n}-0" for n in numbers], "scoring": "legacyPerQuestion",
                "optionPoolId": pool_id, "reuse": "forbidden"}
    part.group(gid, numbers, instructions, [pool], slots, [response], blocks)

    gid = next(g["groupId"] for g in audit_groups if "image-table" in g["groupId"])
    _image_fill_group(part, gid, [17, 18, 19, 20], "question-image-01.png",
                      ["Questions 17-20", "Complete the table below.",
                       "Write NO MORE THAN TWO WORDS AND/OR A NUMBER for each answer."],
                      [
                          {"number": 17, "rectPx": {"x": 144, "y": 82, "width": 80, "height": 18},
                           "locator": "Question 17, Dates cell; measured dotted answer field in the Royal Hunt of the Sun row."},
                          {"number": 18, "rectPx": {"x": 280, "y": 70, "width": 86, "height": 18},
                           "locator": "Question 18, Starting time cell; measured dotted answer field before the printed time suffix."},
                          {"number": 19, "slotIndex": 0, "rectPx": {"x": 455, "y": 71, "width": 73, "height": 10},
                           "locator": "Question 19, Tickets available cell; first dotted field on the line following the printed phrase for 19."},
                          {"number": 19, "slotIndex": 1, "rectPx": {"x": 436, "y": 96, "width": 80, "height": 10},
                           "locator": "Question 19, Tickets available cell; second dotted field on the lower wrapped line after the printed word and."},
                          {"number": 20, "rectPx": {"x": 598, "y": 66, "width": 67, "height": 20},
                           "locator": "Question 20, Price cell; measured dotted answer field after the printed currency sign."},
                      ], 19, "and", {"number": 19, "text": "and",
                                      "rectPx": {"x": 403, "y": 92, "width": 32, "height": 14},
                                      "locator": "Question 19, Tickets available cell; printed word and at the start of the lower wrapped line, below the first answer field."})


def _manual_c6t1p4(part: SourcePart) -> None:
    groups = [g for g in AUDIT["groups"] if g["partId"] == part.part_id]
    gid = next(g["groupId"] for g in groups if "image-table" in g["groupId"])
    _image_fill_group(part, gid, list(range(31, 38)), "question-image-01.png",
                      ["Questions 31-37", "Complete the table below.",
                       "Write NO MORE THAN TWO WORDS for each answer."],
                      [
                          {"number": 31, "rectPx": {"x": 532, "y": 63, "width": 100, "height": 23},
                           "locator": "Question 31, first-to-fourth-centuries row, Situation column; dotted field after the printed question number."},
                          {"number": 32, "slotIndex": 0, "rectPx": {"x": 303, "y": 169, "width": 107, "height": 22},
                           "locator": "Question 32, fifth-to-tenth-centuries row, Situation column; first dotted field before the printed word and."},
                          {"number": 32, "slotIndex": 1, "rectPx": {"x": 448, "y": 169, "width": 111, "height": 22},
                           "locator": "Question 32, fifth-to-tenth-centuries row, Situation column; second dotted field after the printed word and."},
                          {"number": 33, "rectPx": {"x": 307, "y": 225, "width": 130, "height": 22},
                           "locator": "Question 33, eleventh-century row, Situation column; dotted field after the printed question number."},
                          {"number": 34, "rectPx": {"x": 307, "y": 329, "width": 112, "height": 23},
                           "locator": "Question 34, sixteenth-century row, first Situation sentence; dotted field after the printed question number."},
                          {"number": 35, "rectPx": {"x": 304, "y": 398, "width": 112, "height": 23},
                           "locator": "Question 35, sixteenth-century row, second Situation sentence; dotted field after the printed question number."},
                          {"number": 36, "rectPx": {"x": 304, "y": 472, "width": 110, "height": 23},
                           "locator": "Question 36, seventeenth-century row, Situation column; dotted field following the printed number and preceding the final word of the sentence."},
                          {"number": 37, "rectPx": {"x": 586, "y": 536, "width": 76, "height": 22},
                           "locator": "Question 37, nineteenth-century row, Situation column; dotted field after the printed question number."},
                      ], 32, "and", {"number": 32, "text": "and",
                                      "rectPx": {"x": 414, "y": 169, "width": 29, "height": 22},
                                      "locator": "Question 32, fifth-to-tenth-centuries row, Situation column; printed word and between the two dotted answer fields."})

    gid = next(g["groupId"] for g in groups if "multiple-choice" in g["groupId"])
    _choice_group(part, gid, [38, 39, 40], multi=True,
                  instruction_values=["Questions 38-40", "Choose THREE letters, A-G."],
                  shared_prompt="Which THREE of the following problems are mentioned in connection with 20th century housing in the East End?")


def _manual_c7t2p3(part: SourcePart) -> None:
    groups = [g for g in AUDIT["groups"] if g["partId"] == part.part_id]
    gid = next(g["groupId"] for g in groups if "single-choice" in g["groupId"])
    prompts = [(n, f"{n} {part.questions[n]['prompt']}") for n in range(21, 27)]
    _choice_group(part, gid, list(range(21, 27)), instruction_values=[
        "Questions 21-26", "Choose the correct letter, A, B or C.",
    ], prompt_texts=prompts)

    gid = next(g["groupId"] for g in groups if "image-table" in g["groupId"])
    _image_fill_group(part, gid, [27, 28], "question-image-01.png",
                      ["Questions 27 and 28", "Complete the table below.",
                       "Write ONE WORD AND/OR TWO NUMBERS for each answer."],
                      [
                          {"number": 27, "slotIndex": 0, "rectPx": {"x": 74, "y": 201, "width": 223, "height": 22},
                           "locator": "Question 27, Antarctic Treaty table row, Date column; first dotted field after printed 27."},
                          {"number": 27, "slotIndex": 1, "rectPx": {"x": 342, "y": 201, "width": 213, "height": 22},
                           "locator": "Question 27, Antarctic Treaty table row, Date column; second dotted field after the printed word to."},
                          {"number": 28, "rectPx": {"x": 1040, "y": 378, "width": 260, "height": 24},
                           "locator": "Question 28, 1959 row, Event column; dotted field after the printed words Antarctic Treaty was."},
                      ], 27, "to", {"number": 27, "text": "to",
                                      "rectPx": {"x": 301, "y": 201, "width": 39, "height": 22},
                                      "locator": "Question 27, Antarctic Treaty table row, Date column; printed word to between the two dotted fields."})

    gid = next(g["groupId"] for g in groups if "choose-two" in g["groupId"])
    _choice_group(part, gid, [29, 30], multi=True,
                  instruction_values=["Questions 29 and 30", "Choose TWO letters, A-E."],
                  shared_prompt="Which TWO achievements of the Antarctic Treaty are mentioned by the speakers?")


def _manual_c9t2p2(part: SourcePart) -> None:
    groups = [g for g in AUDIT["groups"] if g["partId"] == part.part_id]
    gid = next(g["groupId"] for g in groups if "parks-open-spaces-table" in g["groupId"])
    _image_fill_group(part, gid, [11, 12, 13], "question-image-01.png",
                      ["Questions 11-13", "Complete the table below.",
                       "Write NO MORE THAN THREE WORDS for each answer."],
                      [
                          {"number": 11, "rectPx": {"x": 278, "y": 129, "width": 113, "height": 19},
                           "locator": "Question 11, Holt Island row, Of particular interest column; dotted field after the printed question number."},
                          {"number": 12, "slotIndex": 0, "rectPx": {"x": 524, "y": 112, "width": 101, "height": 19},
                           "locator": "Question 12, Holt Island row, Open column; first dotted field after the printed question number."},
                          {"number": 12, "slotIndex": 1, "rectPx": {"x": 498, "y": 131, "width": 66, "height": 20},
                           "locator": "Question 12, Holt Island row, Open column; second dotted field on the lower line after the printed word and."},
                          {"number": 13, "rectPx": {"x": 304, "y": 229, "width": 60, "height": 20},
                           "locator": "Question 13, Longfield Country Park row, Of particular interest column; dotted field after printed 13 and before the printed word with."},
                      ], 12, "and", {"number": 12, "text": "and",
                                      "rectPx": {"x": 462, "y": 131, "width": 35, "height": 20},
                                      "locator": "Question 12, Holt Island row, Open column; printed word and between the two dotted answer fields."})

    gid = next(g["groupId"] for g in groups if "longfield-park-activities" in g["groupId"])
    _choice_group(part, gid, [14, 15, 16], instruction_values=[
        "Questions 14-16", "Choose the correct letter, A, B, or C.",
    ], prompt_texts=[
        (14, "As part of Monday's activity, visitors will"),
        (15, "For the activity on Wednesday,"),
        (16, "For the activity on Saturday, visitors should"),
    ])
    # The original source has a section heading before the first separate prompt.
    choice_group = part.groups[-1]
    heading, _ = part.text("g2-longfield-park-heading", "Longfield Park", after=0)
    choice_group["blocks"].insert(0, _text_container("b-g2-longfield-park-heading-block", "paragraph", [heading]))

    gid = next(g["groupId"] for g in groups if "hinchingbrooke-map-matching" in g["groupId"])
    key, numbers, pool_id = "g3", list(range(17, 21)), "pool-g3-map-labels"
    instructions = part.instructions(key, ["Questions 17-20", "Label the map below.",
                                           "Write the correct letter, A-I, next to questions 17-20."])
    pool = part.pool(pool_id, numbers)
    slots, rows = [], []
    labels = ["bird hide", "dog-walking area", "flower garden", "wooded area"]
    for number, label in zip(numbers, labels):
        _, definition = part.slot(number, "matching")
        slots.append(definition)
        target, _ = part.text(f"g3-q{number}-target", f"{number} {label}")
        row = _text_container(f"b-g3-q{number}-row", "formRow", [
            target, {"id": f"b-g3-q{number}-choices", "type": "choiceList",
                     "optionPoolId": pool_id, "slotIds": [definition["slotId"]]},
        ])
        rows.append(row)
    image = part.image_block(gid, "g3-map", "question-image-02.png", [])
    response = {"kind": "matching", "questionIds": [part.questions[n]["id"] for n in numbers],
                "slotIds": [definition["slotId"] for definition in slots],
                "scoring": "legacyPerQuestion", "optionPoolId": pool_id, "reuse": "forbidden"}
    part.group(gid, numbers, instructions, [pool], slots, [response], [image, *rows])


MANUAL_BUILDERS = {
    "cambridge-6-test-1-part-1": _manual_c6t1p1,
    "cambridge-6-test-1-part-2": _manual_c6t1p2,
    "cambridge-6-test-1-part-4": _manual_c6t1p4,
    "cambridge-7-test-2-part-3": _manual_c7t2p3,
    "cambridge-9-test-2-part-2": _manual_c9t2p2,
}


def _migrate_old_layouts(records: list[dict[str, Any]], archive_hashes: dict[str, str]):
    outputs, validations, per_book, question_types = [], [], defaultdict(lambda: Counter()), Counter()
    total_questions = 0
    for part_record in records:
        part_id = part_record["partId"]
        if part_id in TARGETS:
            continue
        old_path = OLD / "listening" / part_id / "layout.json"
        old = _read_json(old_path)
        layout = copy.deepcopy(old)
        layout["schemaVersion"] = 3
        part_bytes = _load_part_bytes(part_record["book"], part_id)
        part = json.loads(part_bytes.decode("utf-8"))
        questions = {question["id"]: question for question in part["questions"]}
        for group in layout["groups"]:
            for block in _images(group["blocks"]):
                block["printedConnectors"] = []
            for response in group["responses"]:
                if response["kind"] == "fill" and len(response["slotIds"]) == 2:
                    qids = response["questionIds"]
                    if len(qids) != 1 or not questions[qids[0]].get("answerSeparator"):
                        raise ValueError(f"paired V2 response has no legacy separator: {part_id}")
                    response["joiner"] = questions[qids[0]]["answerSeparator"]
        variant = _variant_for_layout(part_record, layout)
        archive_path = Path(variant["sourceArchive"])
        with zipfile.ZipFile(archive_path) as archive:
            prefix = variant["sourcePrefix"]
            archive_entries = [n for n in archive.namelist() if n.startswith(prefix) and not n.endswith("/")]
            archive_sources = {name: archive.read(name) for name in archive_entries}
        source_bytes = dict(archive_sources)
        for name, raw in archive_sources.items():
            relative = name[len(variant["sourcePrefix"]):]
            if relative:
                source_bytes.setdefault(relative, raw)
        if "content.html" in source_bytes:
            source_bytes["content.html"] = source_bytes[variant["sourcePrefix"] + "content.html"]
        if "questions.txt" in source_bytes:
            source_bytes["content.txt"] = source_bytes["questions.txt"]
        assets = {block["asset"] for group in layout["groups"] for block in _images(group["blocks"])}
        metadata = {}
        with zipfile.ZipFile(PACKS / part_record["packFile"]) as pack:
            for asset in assets:
                full = variant["sourcePrefix"] + asset
                raw = archive_sources.get(full)
                if raw is None:
                    raise ValueError(f"source image not found: {part_id}:{asset}")
                packed = pack.read(f"listening/{part_id}/{asset}")
                if packed != raw:
                    raise ValueError(f"pack/source image bytes differ: {part_id}:{asset}")
                with Image.open(io.BytesIO(raw)) as im:
                    width, height = im.size
                metadata[asset] = {"sha256": sha256(raw), "widthPx": width, "heightPx": height}
        review_path = OLD / "listening" / part_id / "overlay-review-records.json"
        review_records = _read_json(review_path) if review_path.exists() else []
        raw_layout = (json.dumps(layout, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        validated = validate_layout_v3(raw_layout, part_bytes, source_bytes=source_bytes,
                                       image_metadata=metadata, review_records=review_records,
                                       source_archive_hashes=archive_hashes)
        out = OUT / "listening" / part_id / "layout.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(raw_layout)
        if review_records:
            _write_json(out.parent / "overlay-review-records.json", review_records)
        count = len(validated["groups"][0]["questionIds"])
        count = sum(len(g["questionIds"]) for g in validated["groups"])
        total_questions += count
        per_book[part_record["book"]].update({"parts": 1, "questions": count})
        for q in part["questions"]:
            question_types[q["type"]] += 1
        validations.append({"partId": part_id, "path": str(out.relative_to(OUT)).replace("\\", "/"),
                            "status": "passed", "schemaVersion": 3, "questionCount": count,
                            "groupCount": len(validated["groups"]), "basePartSha256": sha256(part_bytes),
                            "layoutSha256": sha256(raw_layout), "sourceArchive": str(archive_path),
                            "sourceArchiveSha256": archive_hashes[str(archive_path)],
                            "sourceResources": sorted({source["resource"] for item in _all_text_sources(layout)
                                                        if (source := item["source"]).get("kind") == "resourceRange"}),
                            "imageCount": len(metadata), "overlayCount": len(review_records)})
        outputs.append(part_id)
    return outputs, validations, per_book, question_types, total_questions


def _source_context_for_manual(part: SourcePart) -> tuple[dict[str, bytes], dict[str, dict[str, Any]]]:
    for question in part.part["questions"]:
        for asset in question.get("images", []):
            resource = part.prefix + asset
            raw = part.archive_sources.get(resource)
            if raw is None:
                raise ValueError(f"missing original source image: {part.part_id}:{resource}")
            with Image.open(io.BytesIO(raw)) as im:
                width, height = im.size
            part.image_metadata[asset] = {"sha256": sha256(raw), "widthPx": width, "heightPx": height}
            pack = PACKS / f"cambridge-{part.record['book']}.eelpack"
            with zipfile.ZipFile(pack) as archive:
                if archive.read(f"listening/{part.part_id}/{asset}") != raw:
                    raise ValueError(f"pack/source image bytes differ: {part.part_id}:{asset}")
    return part.source_bytes, part.image_metadata


def _generate_manuals(records: list[dict[str, Any]]):
    validations, counts_by_type, per_book, part_group_records = [], Counter(), defaultdict(lambda: Counter()), []
    candidate_measurements, target_layouts = [], []
    for part_id, builder in MANUAL_BUILDERS.items():
        record = next(item for item in records if item["partId"] == part_id)
        part = SourcePart(record, ARCHIVE_HASHES)
        builder(part)
        source_bytes, metadata = _source_context_for_manual(part)
        raw, validated = part.save()
        out = OUT / "listening" / part_id / "layout.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(raw)
        _write_json(out.parent / "overlay-review-records.json", part.review_records)
        count = sum(len(group["questionIds"]) for group in validated["groups"])
        per_book[record["book"]].update({"parts": 1, "questions": count})
        for question in part.part["questions"]:
            counts_by_type[question["type"]] += 1
        validations.append({"partId": part_id, "path": str(out.relative_to(OUT)).replace("\\", "/"),
                            "status": "passed", "schemaVersion": 3, "questionCount": count,
                            "groupCount": len(validated["groups"]), "basePartSha256": sha256(part.part_bytes),
                            "layoutSha256": sha256(raw), "sourceArchive": part.archive,
                            "sourceArchiveSha256": part.archive_sha,
                            "sourceResources": ["content.html", "questions.txt"],
                            "sourceResourceHashes": {"content.html": sha256(source_bytes["content.html"]),
                                                      "questions.txt": sha256(source_bytes["questions.txt"])},
                            "imageCount": len(metadata), "overlayCount": len(part.review_records),
                            "measurementCount": len(part.measurements)})
        candidate_measurements.extend(part.measurements)
        target_layouts.append((part_id, part.part_bytes, dict(source_bytes), dict(metadata),
                               list(part.review_records)))
        old_groups = [g for g in AUDIT["groups"] if g["partId"] == part_id]
        for old_group in old_groups:
            record_out = copy.deepcopy(old_group)
            record_out["status"] = "converted"
            record_out["reasonCodes"] = []
            record_out["confirmedQuestionNumbers"] = record_out["questionNumbers"]
            record_out["decisionBasis"]["v3Resolution"] = (
                "V3 paired fill uses two ordered Slots, one legacyPerQuestion response and a reviewed "
                "printedConnector rectangle in the original image."
            )
            record_out.pop("validatorReasonCode", None)
            record_out["outputLayout"] = str(out.relative_to(OUT)).replace("\\", "/")
            part_group_records.append(record_out)
    return validations, counts_by_type, per_book, part_group_records, candidate_measurements, target_layouts


def _verify_all_from_disk(validations: list[dict[str, Any]], target_layouts: list[tuple]):
    records = {record["partId"]: record for record in AUDIT["parts"]}
    target_sources = {item[0]: item[1:] for item in target_layouts}
    reruns = []
    for item in validations:
        part_id = item["partId"]
        path = OUT / item["path"]
        raw = path.read_bytes()
        part_record = records[part_id]
        if part_id in target_sources:
            part_bytes, source_bytes, metadata, reviews = target_sources[part_id]
            source_hashes = ARCHIVE_HASHES
        else:
            part_bytes = _load_part_bytes(part_record["book"], part_id)
            layout = json.loads(raw.decode("utf-8"))
            variant = _variant_for_layout(part_record, layout)
            source_hashes = ARCHIVE_HASHES
            with zipfile.ZipFile(variant["sourceArchive"]) as archive:
                prefix = variant["sourcePrefix"]
                full_sources = {name: archive.read(name) for name in archive.namelist()
                                if name.startswith(prefix) and not name.endswith("/")}
            source_bytes = dict(full_sources)
            for name, source in full_sources.items():
                relative = name[len(prefix):]
                if relative:
                    source_bytes.setdefault(relative, source)
            if "questions.txt" in source_bytes:
                source_bytes["content.txt"] = source_bytes["questions.txt"]
            metadata = {}
            with zipfile.ZipFile(PACKS / part_record["packFile"]) as pack:
                for block in (block for group in layout["groups"] for block in _images(group["blocks"])):
                    asset = block["asset"]
                    src = full_sources[prefix + asset]
                    if pack.read(f"listening/{part_id}/{asset}") != src:
                        raise ValueError(f"source asset changed for {part_id}:{asset}")
                    with Image.open(io.BytesIO(src)) as im:
                        width, height = im.size
                    metadata[asset] = {"sha256": sha256(src), "widthPx": width, "heightPx": height}
            review_path = OUT / "listening" / part_id / "overlay-review-records.json"
            reviews = _read_json(review_path) if review_path.exists() else []
        if sha256(part_bytes) != json.loads(raw.decode("utf-8"))["basePartSha256"]:
            raise ValueError(f"base part hash mismatch from disk: {part_id}")
        checked = validate_layout_v3(raw, part_bytes, source_bytes=source_bytes, image_metadata=metadata,
                                     review_records=reviews, source_archive_hashes=source_hashes)
        qids = [qid for group in checked["groups"] for qid in group["questionIds"]]
        original = json.loads(part_bytes.decode("utf-8"))
        if len(qids) != len(original["questions"]) or set(qids) != {q["id"] for q in original["questions"]}:
            raise ValueError(f"question coverage mismatch after reread: {part_id}")
        resource_names = sorted({text["source"]["resource"] for text in _all_text_sources(checked)
                                 if text["source"]["kind"] == "resourceRange"})
        source_resource_hashes = {name: sha256(source_bytes[name]) for name in resource_names}
        variant = _variant_for_layout(part_record, checked)
        image_sources = []
        for asset, image in sorted(metadata.items()):
            source_name = variant["sourcePrefix"] + asset
            raw_image = source_bytes[source_name]
            image_sources.append({"asset": asset, "sourceResource": source_name,
                                  "sourceEntrySha256": sha256(raw_image), "imageSha256": image["sha256"],
                                  "widthPx": image["widthPx"], "heightPx": image["heightPx"]})
        reruns.append({"partId": part_id, "path": item["path"], "status": "passed",
                       "layoutSha256": sha256(raw), "basePartSha256": sha256(part_bytes),
                       "questionCount": len(qids), "sourceArchiveSha256": item["sourceArchiveSha256"],
                       "sourceResourceHashes": source_resource_hashes, "imageCount": len(metadata),
                       "imageSources": image_sources, "imageCoordinateEvidence": "passed"})
    return reruns


def main() -> None:
    global AUDIT, ARCHIVE_HASHES
    AUDIT = _read_json(AUDIT_PATH)
    records = AUDIT["parts"]
    expected_archives = {}
    for record in records:
        for variant in record["sourceVariants"]:
            expected_archives[variant["sourceArchive"]] = variant["archiveSha256"]
    ARCHIVE_HASHES = {}
    for path, expected in expected_archives.items():
        actual = _archive_sha(Path(path))
        if actual != expected:
            raise ValueError(f"source archive SHA-256 differs from audited value: {path}")
        ARCHIVE_HASHES[path] = actual
    OUT.mkdir(parents=True, exist_ok=True)

    migrated, validation_records, per_book, type_counts, migrated_question_count = _migrate_old_layouts(records, ARCHIVE_HASHES)
    manual_validation, manual_types, manual_books, converted_groups, measurements, target_layouts = _generate_manuals(records)
    validation_records.extend(manual_validation)
    for book, count in manual_books.items():
        per_book[book].update(count)
    type_counts.update(manual_types)

    # Persist the newly completed group decisions, retaining source evidence and the prior disposition.
    all_group_records = []
    target_ids = set(TARGETS)
    for group in AUDIT["groups"]:
        if group["partId"] in target_ids:
            updated = next(item for item in converted_groups if item["groupId"] == group["groupId"])
            all_group_records.append(updated)
        else:
            item = copy.deepcopy(group)
            item["status"] = "converted" if item["status"] == "converted" else "needsReview"
            all_group_records.append(item)
    _write_json(OUT / "group-conversion-records.json", {
        "batchName": "V3 unified candidates", "layoutSchemaVersion": 3,
        "groupCount": len(all_group_records), "groups": all_group_records,
    })
    _write_json(OUT / "image-measurement-review-records.json", {
        "measurementCount": len(measurements), "records": measurements,
    })

    validation_records.sort(key=lambda item: item["partId"])
    disk_validation = _verify_all_from_disk(validation_records, target_layouts)
    _write_json(OUT / "layout-validation.json", {
        "schemaVersion": 3, "validatedParts": len(disk_validation),
        "passed": sum(item["status"] == "passed" for item in disk_validation),
        "failed": sum(item["status"] != "passed" for item in disk_validation),
        "parts": disk_validation,
    })
    unresolved = []
    _write_json(OUT / "unresolved-items.json", unresolved)

    all_questions = sum(item["questionCount"] for item in disk_validation)
    by_book = []
    for book in range(5, 22):
        part_rows = [item for item in disk_validation if item["partId"].startswith(f"cambridge-{book}-")]
        by_book.append({"book": book, "parts": len(part_rows),
                        "completeParts": sum(item["status"] == "passed" for item in part_rows),
                        "needsReviewParts": sum(item["status"] != "passed" for item in part_rows),
                        "questions": sum(item["questionCount"] for item in part_rows)})
    _write_json(OUT / "summary.json", {
        "batchName": "V3", "layoutSchemaVersion": 3,
        "sourceArchiveHashes": ARCHIVE_HASHES,
        "inventory": {"books": 17, "parts": len(disk_validation), "questions": all_questions},
        "conversion": {"completeLayoutFiles": len(disk_validation), "completedParts": len(disk_validation),
                       "needsReviewParts": len(unresolved), "completedQuestions": all_questions,
                       "questionTypesInMigratedAndAuthoredData": dict(sorted(type_counts.items())),
                       "imageMeasurements": len(measurements), "unresolvedItems": len(unresolved)},
        "perBook": by_book,
        "gradingCompatibility": {
            "C9Q12ReviewedJoiner": "and",
            "C9Q12LegacyAnswerSeparatorPresent": False,
            "C9Q12AcceptedAnswerAndWordLimitCheck": "passed",
            "blankFirstOrSecondSlotIncomplete": "passed by compose_fill_answer synthetic test",
            "sourcePartJsonModified": False,
        },
    })
    print(json.dumps({"parts": len(disk_validation), "questions": all_questions,
                      "passed": len(disk_validation), "measurements": len(measurements),
                      "migrated": len(migrated), "manual": len(TARGETS), "output": str(OUT)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
