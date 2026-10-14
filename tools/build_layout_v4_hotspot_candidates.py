"""Create V4 candidates by mechanically migrating V3 and placing 43 explicit hotspots.

This is a controlled data migration, not a general layout recognizer. All target
Groups, Slot IDs, rectangles, and visual locators are declared below.
"""
from __future__ import annotations

import copy
import difflib
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile
from collections import Counter, defaultdict
from typing import Any

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / "private-data"
V3 = PRIVATE / "layout-v3-unified-candidates"
PACKS = PRIVATE / "layout-batch-ab-v2"
AUDIT = PRIVATE / "layout-v3-candidates" / "group-review-records.json"
OUT = PRIVATE / "layout-v4-unified-candidates"
sys.path.insert(0, str(ROOT / "tools"))
from layout_v3_validator import _normalized_resource, sha256 as v3_sha256  # noqa: E402
from layout_v4_validator import validate_layout_v4  # noqa: E402


# Rectangles are measured against the native-resolution original ZIP image.
# Values are {x, y, width, height} in source-image pixels.
TARGETS: dict[str, dict[str, Any]] = {
    "cambridge-5-test-1-part-2": {
        "groupId": "cambridge-5-test-1-part-2-layout-table-q11-q19",
        "slots": {
            11: ({"x": 205, "y": 96, "width": 105, "height": 14}, "Question 11, first data row, Good Points cell."),
            12: ({"x": 384, "y": 96, "width": 103, "height": 14}, "Question 12, first data row, first Problems blank."),
            13: ({"x": 384, "y": 178, "width": 109, "height": 14}, "Question 13, first data row, second Problems blank."),
            14: ({"x": 539, "y": 76, "width": 108, "height": 14}, "Question 14, first data row, Verdict cell."),
            15: ({"x": 201, "y": 260, "width": 112, "height": 14}, "Question 15, second data row, Good Points cell."),
            16: ({"x": 384, "y": 334, "width": 106, "height": 14}, "Question 16, second data row, second Problems blank."),
            17: ({"x": 539, "y": 230, "width": 108, "height": 14}, "Question 17, second data row, Verdict cell."),
            18: ({"x": 384, "y": 386, "width": 106, "height": 14}, "Question 18, third data row, first Problems blank."),
            19: ({"x": 539, "y": 371, "width": 108, "height": 14}, "Question 19, third data row, Verdict cell."),
        },
    },
    "cambridge-16-test-2-part-3": {
        "groupId": "cambridge-16-t2-p3-sleep-dreams-assignment-flow-q25-q30",
        "slots": {
            25: ({"x": 320, "y": 246, "width": 184, "height": 14}, "Question 25, sample stage, blank after the printed number."),
            26: ({"x": 306, "y": 471, "width": 192, "height": 14}, "Question 26, procedure stage, blank after the printed number."),
            27: ({"x": 423, "y": 545, "width": 126, "height": 14}, "Question 27, first blank in the ethics stage."),
            28: ({"x": 360, "y": 595, "width": 176, "height": 14}, "Question 28, second blank in the ethics stage."),
            29: ({"x": 430, "y": 730, "width": 173, "height": 14}, "Question 29, analysis stage, blank after the printed number."),
            30: ({"x": 201, "y": 827, "width": 199, "height": 14}, "Question 30, final stage, blank before the closing phrase."),
        },
    },
    "cambridge-12-test-2-part-3": {
        "groupId": "cambridge-12-test-2-part-3-vikings-paper-flow-q26-q30",
        "slots": {
            26: ({"x": 278, "y": 83, "width": 79, "height": 14}, "Question 26, first flow stage, blank after the printed number."),
            27: ({"x": 339, "y": 169, "width": 75, "height": 14}, "Question 27, second flow stage, blank after the printed number."),
            28: ({"x": 327, "y": 258, "width": 70, "height": 14}, "Question 28, third flow stage, blank after the printed number."),
            29: ({"x": 487, "y": 347, "width": 78, "height": 14}, "Question 29, fourth flow stage, blank after the printed number."),
            30: ({"x": 428, "y": 438, "width": 74, "height": 14}, "Question 30, fifth flow stage, blank after the printed number."),
        },
    },
    "cambridge-12-test-3-part-3": {
        "groupId": "cambridge-12-test-3-part-3-tourism-case-study-flow-q21-q26",
        "slots": {
            21: ({"x": 1080, "y": 454, "width": 230, "height": 18}, "Question 21, Research stage, final blank on the first line."),
            22: ({"x": 804, "y": 557, "width": 233, "height": 18}, "Question 22, Research stage, blank after the printed number."),
            23: ({"x": 627, "y": 597, "width": 215, "height": 18}, "Question 23, Research stage, blank after the printed number on the next line."),
            24: ({"x": 495, "y": 728, "width": 220, "height": 18}, "Question 24, Research stage, blank before the phrase about interviewees."),
            25: ({"x": 920, "y": 937, "width": 242, "height": 18}, "Question 25, Analysis stage, blank after the printed number."),
            26: ({"x": 756, "y": 1272, "width": 198, "height": 18}, "Question 26, Writing the Case Study stage, final blank."),
        },
    },
    "cambridge-13-test-1-part-3": {
        "groupId": "cambridge-13-test-1-part-3-seed-experiment-flow-q26-q30",
        "slots": {
            26: ({"x": 382, "y": 87, "width": 76, "height": 14}, "Question 26, first experiment stage, blank after the printed number."),
            27: ({"x": 335, "y": 173, "width": 76, "height": 14}, "Question 27, second experiment stage, blank after the printed number."),
            28: ({"x": 332, "y": 265, "width": 68, "height": 14}, "Question 28, third experiment stage, blank after the printed number."),
            29: ({"x": 314, "y": 352, "width": 42, "height": 14}, "Question 29, fourth experiment stage, blank after the printed number."),
            30: ({"x": 482, "y": 437, "width": 42, "height": 14}, "Question 30, fifth experiment stage, blank after the printed number."),
        },
    },
    "cambridge-19-test-3-part-3": {
        "groupId": "cambridge-19-t3-p3-mice-experiment-flowchart-q26-q30",
        "slots": {
            26: ({"x": 350, "y": 44, "width": 123, "height": 14}, "Question 26, first experiment stage, blank after the printed number."),
            27: ({"x": 452, "y": 123, "width": 131, "height": 14}, "Question 27, second experiment stage, blank after the printed number."),
            28: ({"x": 455, "y": 263, "width": 132, "height": 14}, "Question 28, feeding stage, blank after the printed number."),
            29: ({"x": 408, "y": 369, "width": 126, "height": 14}, "Question 29, weighing-chamber stage, blank after the printed number."),
            30: ({"x": 228, "y": 458, "width": 138, "height": 14}, "Question 30, final experiment stage, blank after the printed number."),
        },
    },
    "cambridge-21-test-2-part-3": {
        "groupId": "cambridge-21-t2-p3-new-food-product-flow-q27-q30",
        "slots": {
            27: ({"x": 294, "y": 102, "width": 97, "height": 15}, "Question 27, Initial aim box, printed blank under its stage label."),
            28: ({"x": 289, "y": 214, "width": 91, "height": 15}, "Question 28, Literature review box, printed blank under its stage label."),
            29: ({"x": 290, "y": 319, "width": 91, "height": 15}, "Question 29, Product development box, printed blank under its stage label."),
            30: ({"x": 292, "y": 426, "width": 89, "height": 15}, "Question 30, Product production box, printed blank under its stage label."),
        },
    },
    "cambridge-9-test-4-part-2": {
        "groupId": "cambridge-9-test-4-part-2-water-heater-diagram-matching-q11-q13",
        "slots": {
            11: ({"x": 101, "y": 294, "width": 65, "height": 14}, "Question 11, printed answer line immediately before its pointer line."),
            12: ({"x": 113, "y": 434, "width": 54, "height": 14}, "Question 12, printed answer line immediately before its pointer line."),
            13: ({"x": 534, "y": 413, "width": 85, "height": 14}, "Question 13, printed answer line at the right of the heater diagram."),
        },
    },
}

ARCHIVE_SHA_EXPECTED = {
    "D:\\IELTS-Listening-Cambridge-5-13_已修复.zip": "3443dade8ca6990fcafa623a6d6d15e329892c3e23742d7b0200d72893c5ef42",
    "D:\\IELTS-Listening-Cambridge-13-17.zip": "77fe1b45800a52722aa99e80b8103fff5cfef461df63432449adf9af96b21ebf",
    "D:\\IELTS-Listening-Cambridge-18-21.zip": "fa627f95ea6919d42f129e3a8713902e64a58293fe542391cc84af0561afcac1",
}

RETAINED_IMAGE_KINDS = {
    "cambridge-11-test-1-part-2": "map",
    "cambridge-11-test-2-part-2": "floorPlan",
    "cambridge-11-test-4-part-2": "floorPlan",
    "cambridge-12-test-4-part-2": "map",
    "cambridge-13-test-1-part-2": "map",
    "cambridge-14-test-2-part-2": "sitePlan",
    "cambridge-15-test-2-part-2": "map",
    "cambridge-15-test-4-part-2": "map",
    "cambridge-16-test-1-part-2": "floorPlan",
    "cambridge-16-test-4-part-2": "sitePlan",
    "cambridge-18-test-2-part-2": "map",
    "cambridge-19-test-1-part-2": "sitePlan",
    "cambridge-20-test-3-part-2": "sitePlan",
    "cambridge-21-test-2-part-2": "sitePlan",
    "cambridge-6-test-3-part-2": "sitePlan",
    "cambridge-6-test-3-part-4": "map",
    "cambridge-7-test-3-part-2": "optionReferenceGraphic",
    "cambridge-8-test-4-part-2": "sitePlan",
    "cambridge-9-test-2-part-2": "map",
}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def file_sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def walk_blocks(blocks: list[dict[str, Any]]):
    for block in blocks:
        yield block
        kind = block["type"]
        if kind in {"paragraph", "listItem", "formRow"}:
            yield from walk_blocks(block["children"])
        elif kind == "table":
            for row in block["rows"]:
                for cell in row["cells"]:
                    yield from walk_blocks(cell["children"])
        elif kind == "flow":
            for node in block["nodes"]:
                yield from walk_blocks(node["children"])


def all_text_sources(layout: dict[str, Any]):
    for group in layout["groups"]:
        yield from group["instructions"]
        yield from (block for block in walk_blocks(group["blocks"]) if block["type"] == "text")


def variant_for_layout(part_record: dict[str, Any], layout: dict[str, Any]) -> dict[str, Any]:
    referenced: dict[str, set[str]] = defaultdict(set)
    for text in all_text_sources(layout):
        source = text["source"]
        if source["kind"] == "resourceRange":
            referenced[source["resource"]].add(source["sha256"])
    for variant in part_record["sourceVariants"]:
        files = variant["sourceFiles"]
        if all(resource in files and digests == {files[resource]["sha256"]}
               for resource, digests in referenced.items()):
            return variant
    raise ValueError(f"SOURCE_VARIANT_UNRESOLVED:{part_record['partId']}")


def part_bytes_from_pack(book: int, part_id: str) -> bytes:
    pack_path = PACKS / f"cambridge-{book}.eelpack"
    with zipfile.ZipFile(pack_path) as pack:
        return pack.read(f"listening/{part_id}/part.json")


def source_bundle(variant: dict[str, Any]) -> tuple[dict[str, bytes], zipfile.ZipFile, dict[str, bytes]]:
    archive = zipfile.ZipFile(variant["sourceArchive"])
    entries = {name: archive.read(name) for name in archive.namelist()
               if name.startswith(variant["sourcePrefix"]) and not name.endswith("/")}
    sources: dict[str, bytes] = dict(entries)
    for name, raw in entries.items():
        relative = name[len(variant["sourcePrefix"]):]
        if relative:
            sources.setdefault(relative, raw)
    if "questions.txt" in sources:
        sources["content.txt"] = sources["questions.txt"]
    return sources, archive, entries


def image_metadata_for(layout: dict[str, Any], variant: dict[str, Any], archive: zipfile.ZipFile,
                       source_bytes: dict[str, bytes]) -> tuple[dict[str, dict[str, Any]], dict[str, bytes]]:
    metadata: dict[str, dict[str, Any]] = {}
    originals: dict[str, bytes] = {}
    for group in layout["groups"]:
        for block in walk_blocks(group["blocks"]):
            if block["type"] != "image":
                continue
            entry = variant["sourcePrefix"] + block["asset"]
            raw = archive.read(entry)
            digest = hashlib.sha256(raw).hexdigest()
            with Image.open(io.BytesIO(raw)) as image:
                width, height = image.size
            metadata[block["asset"]] = {"sha256": digest, "widthPx": width, "heightPx": height}
            originals[entry] = raw
            source_bytes[entry] = raw
            source_bytes.setdefault(block["asset"], raw)
            if block["widthPx"] != width or block["heightPx"] != height:
                raise ValueError(f"IMAGE_DIMENSION_MISMATCH:{layout['partId']}:{block['asset']}")
    return metadata, originals


def _remove_placements(blocks: list[dict[str, Any]], slot_ids: set[str]) -> list[dict[str, Any]]:
    result = []
    for block in blocks:
        kind = block["type"]
        if kind == "slot" and block["slotId"] in slot_ids:
            continue
        if kind == "choiceList":
            retained = [slot_id for slot_id in block["slotIds"] if slot_id not in slot_ids]
            if not retained:
                continue
            block["slotIds"] = retained
        elif kind in {"paragraph", "listItem", "formRow"}:
            block["children"] = _remove_placements(block["children"], slot_ids)
            if not block["children"]:
                continue
        elif kind == "table":
            for row in block["rows"]:
                for cell in row["cells"]:
                    cell["children"] = _remove_placements(cell["children"], slot_ids)
                    if not cell["children"]:
                        raise ValueError(f"EMPTY_TABLE_CELL_AFTER_SLOT_MOVE:{block['id']}:{cell['id']}")
        elif kind == "flow":
            for node in block["nodes"]:
                node["children"] = _remove_placements(node["children"], slot_ids)
            if all(not node["children"] for node in block["nodes"]):
                # This Flow contained only external choice controls; its image is the visible Flow.
                continue
            if any(not node["children"] for node in block["nodes"]):
                raise ValueError(f"EMPTY_FLOW_NODE_AFTER_SLOT_MOVE:{block['id']}")
        result.append(block)
    return result


def recursively_without_target_placements(blocks: list[dict[str, Any]], slot_ids: set[str]) -> list[dict[str, Any]]:
    copied = copy.deepcopy(blocks)
    clean = _remove_placements(copied, slot_ids)
    for block in walk_blocks(clean):
        if block["type"] == "image":
            block["overlays"] = [overlay for overlay in block["overlays"]
                                  if overlay["slotId"] not in slot_ids]
    return clean


def draw_preview(source: bytes, part_id: str, image_sha: str, dimensions: tuple[int, int],
                 overlays: list[dict[str, Any]], records_by_slot: dict[str, dict[str, Any]], path: Path) -> None:
    with Image.open(io.BytesIO(source)) as original:
        canvas = Image.new("RGB", (original.width + 330, max(original.height, 130)), "white")
        canvas.paste(original.convert("RGB"), (0, 0))
    draw = ImageDraw.Draw(canvas, "RGBA")
    colors = [(230, 35, 35), (30, 100, 220), (10, 145, 80), (190, 80, 200),
              (230, 130, 20), (0, 150, 160), (180, 40, 105), (90, 95, 30), (60, 60, 60)]
    title_font = ImageFont.load_default()
    for index, overlay in enumerate(overlays):
        rect = overlay["rectPx"]
        color = colors[index % len(colors)]
        x0, y0 = rect["x"], rect["y"]
        x1, y1 = x0 + rect["width"] - 1, y0 + rect["height"] - 1
        draw.rectangle((x0, y0, x1, y1), fill=(*color, 55), outline=(*color, 255), width=2)
    panel_x = dimensions[0] + 10
    draw.text((panel_x, 8), f"{part_id}\n{dimensions[0]}x{dimensions[1]} px", fill="black", font=title_font, spacing=4)
    draw.text((panel_x, 42), f"image SHA-256\n{image_sha}", fill="black", font=title_font, spacing=3)
    y = 94
    for index, overlay in enumerate(overlays):
        slot_id = overlay["slotId"]
        record = records_by_slot[slot_id]
        rect = overlay["rectPx"]
        color = colors[index % len(colors)]
        label = f"Q{record['questionId'].split('-q')[-1]}  {slot_id}\n({rect['x']},{rect['y']},{rect['width']},{rect['height']})"
        draw.rectangle((panel_x, y + 1, panel_x + 9, y + 10), fill=(*color, 255))
        draw.multiline_text((panel_x + 15, y), label, fill="black", font=title_font, spacing=2)
        y += 30
    path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(path)


def normalized_layout_for_comparison(layout: dict[str, Any], target_slot_ids: set[str]) -> dict[str, Any]:
    comparable = copy.deepcopy(layout)
    comparable.pop("schemaVersion", None)
    for group in comparable["groups"]:
        group["blocks"] = recursively_without_target_placements(group["blocks"], target_slot_ids)
    return comparable


def compare_c13_variants(part_record: dict[str, Any], chosen: dict[str, Any], layout: dict[str, Any],
                         archive_hashes: dict[str, str]) -> dict[str, Any]:
    variants = part_record["sourceVariants"]
    if len(variants) != 2:
        raise ValueError("C13_EXPECTED_TWO_SOURCE_VARIANTS")
    chosen_index = variants.index(chosen)
    other = variants[1 - chosen_index]
    reports = []
    for variant in variants:
        with zipfile.ZipFile(variant["sourceArchive"]) as archive:
            prefix = variant["sourcePrefix"]
            images = [b for group in layout["groups"] for b in walk_blocks(group["blocks"]) if b["type"] == "image"]
            asset = images[0]["asset"]
            image_bytes = archive.read(prefix + asset)
            html_bytes = archive.read(prefix + "content.html")
            question_bytes = archive.read(prefix + "questions.txt")
            part_bytes = archive.read(prefix + "part.json")
        reports.append({
            "sourceArchive": variant["sourceArchive"],
            "sourceArchiveSha256": archive_hashes[variant["sourceArchive"]],
            "sourcePrefix": prefix,
            "contentHtmlSha256": hashlib.sha256(html_bytes).hexdigest(),
            "questionsTxtSha256": hashlib.sha256(question_bytes).hexdigest(),
            "imageEntry": prefix + asset,
            "imageSha256": hashlib.sha256(image_bytes).hexdigest(),
            "partJsonSha256InSourceZip": hashlib.sha256(part_bytes).hexdigest(),
        })
    with zipfile.ZipFile(chosen["sourceArchive"]) as a, zipfile.ZipFile(other["sourceArchive"]) as b:
        text_a = _normalized_resource("content.html", a.read(chosen["sourcePrefix"] + "content.html"))
        text_b = _normalized_resource("content.html", b.read(other["sourcePrefix"] + "content.html"))
        opcodes = [op for op in difflib.SequenceMatcher(a=text_a, b=text_b, autojunk=False).get_opcodes()
                   if op[0] != "equal"]
        inserted = [text_b[j1:j2] for op, _i1, _i2, j1, j2 in opcodes if op == "insert"]
        replaced = [
            {"selectedText": text_a[i1:i2], "otherText": text_b[j1:j2]}
            for op, i1, i2, j1, j2 in opcodes if op != "insert"
        ]
    same_image = reports[0]["imageSha256"] == reports[1]["imageSha256"]
    same_questions = reports[0]["questionsTxtSha256"] == reports[1]["questionsTxtSha256"]
    chosen_layout_html = next(text["source"]["sha256"] for text in all_text_sources(layout)
                              if text["source"].get("resource") == "content.html")
    return {
        "partId": part_record["partId"],
        "selectedSourceArchive": chosen["sourceArchive"],
        "selectedBy": "The V3 layout's content.html source ranges use the selected variant's exact SHA-256.",
        "v3LayoutBoundContentHtmlSha256": chosen_layout_html,
        "variants": reports,
        "comparison": {
            "imageBytesIdentical": same_image,
            "questionsTxtBytesIdentical": same_questions,
            "normalizedHtmlEqual": text_a == text_b,
            "normalizedHtmlInsertions": inserted,
            "normalizedHtmlReplacements": replaced,
            "questionAndAnswerRegionMatches": not replaced and all(s in {" / Transcript"} for s in inserted),
            "note": "The differing normalized HTML token is page chrome (' / Transcript'); the question text file and source image bytes are identical. The V3 layout remains bound to the selected archive's HTML bytes.",
        },
    }


def audit_no_overlay_images(layouts: dict[str, dict[str, Any]], part_records: dict[str, dict[str, Any]],
                            archive_hashes: dict[str, str]) -> tuple[list[dict[str, Any]], list[tuple[dict, bytes]]]:
    results: list[dict[str, Any]] = []
    image_bytes_for_preview = []
    for part_id, layout in sorted(layouts.items()):
        part_record = part_records[part_id]
        variant = variant_for_layout(part_record, layout)
        with zipfile.ZipFile(variant["sourceArchive"]) as archive:
            for group in layout["groups"]:
                images = [block for block in walk_blocks(group["blocks"]) if block["type"] == "image"]
                if not images or any(block["overlays"] for block in images):
                    continue
                if len(images) != 1:
                    raise ValueError(f"NO_OVERLAY_GROUP_IMAGE_COUNT:{part_id}:{group['groupId']}")
                block = images[0]
                entry = variant["sourcePrefix"] + block["asset"]
                raw = archive.read(entry)
                digest = hashlib.sha256(raw).hexdigest()
                with Image.open(io.BytesIO(raw)) as image:
                    width, height = image.size
                audit_images = [record for record in part_record["imageAssets"]
                                if record["asset"] == block["asset"]]
                if not audit_images or any(x["packSha256"] != digest for x in audit_images):
                    raise ValueError(f"IMAGE_SOURCE_HASH_MISMATCH:{part_id}:{entry}")
                target = part_id in TARGETS and group["groupId"] == TARGETS[part_id]["groupId"]
                if not target and part_id not in RETAINED_IMAGE_KINDS:
                    raise ValueError(f"UNCLASSIFIED_RETAINED_IMAGE:{part_id}:{group['groupId']}")
                results.append({
                    "partId": part_id,
                    "groupId": group["groupId"],
                    "questionIds": group["questionIds"],
                    "disposition": "addModelMeasuredHotspots" if target else "retainBesideImage",
                    "visualKind": "answerHotspots" if target else RETAINED_IMAGE_KINDS[part_id],
                    "sourceArchive": variant["sourceArchive"],
                    "sourceArchiveSha256": archive_hashes[variant["sourceArchive"]],
                    "sourceEntry": entry,
                    "sourceEntrySha256": digest,
                    "imageSha256": digest,
                    "widthPx": width,
                    "heightPx": height,
                    "imageAsset": block["asset"],
                })
                image_bytes_for_preview.append((results[-1], raw))
    if len(results) != 27:
        raise ValueError(f"EXPECTED_27_NO_OVERLAY_GROUPS_GOT_{len(results)}")
    if sum(item["disposition"] == "retainBesideImage" for item in results) != 19:
        raise ValueError("EXPECTED_19_REFERENCE_IMAGES_RETAINED")
    if sum(item["disposition"] == "addModelMeasuredHotspots" for item in results) != 8:
        raise ValueError("EXPECTED_8_HOTSPOT_GROUPS")
    return results, image_bytes_for_preview


def build() -> None:
    if (OUT / "listening").exists():
        summary_path = OUT / "summary.json"
        if not summary_path.exists() or read_json(summary_path).get("batchName") != "V4 image hotspots":
            raise SystemExit("OUTPUT_ALREADY_EXISTS: refusing to overwrite an unrecognized candidate directory")
    audit = read_json(AUDIT)
    part_records = {record["partId"]: record for record in audit["parts"]}
    v3_layouts = {path.parent.name: read_json(path) for path in V3.glob("listening/*/layout.json")}
    if len(v3_layouts) != 272:
        raise ValueError(f"EXPECTED_272_V3_LAYOUTS_GOT_{len(v3_layouts)}")

    source_archive_paths = sorted({variant["sourceArchive"] for record in audit["parts"]
                                   for variant in record["sourceVariants"]})
    archive_hashes = {path: file_sha(Path(path)) for path in source_archive_paths}
    for path, expected in ARCHIVE_SHA_EXPECTED.items():
        if archive_hashes.get(path) != expected:
            raise ValueError(f"SOURCE_ARCHIVE_SHA256_MISMATCH:{path}")

    no_overlay_audit, _ = audit_no_overlay_images(v3_layouts, part_records, archive_hashes)
    write_json(OUT / "no-overlay-image-audit.json", {
        "source": "three original ZIP archives",
        "groupsChecked": len(no_overlay_audit),
        "retainedBesideImage": sum(x["disposition"] == "retainBesideImage" for x in no_overlay_audit),
        "hotspotTargetGroups": sum(x["disposition"] == "addModelMeasuredHotspots" for x in no_overlay_audit),
        "groups": no_overlay_audit,
    })

    all_model_records: list[dict[str, Any]] = []
    target_details: list[dict[str, Any]] = []
    v4_layouts: dict[str, dict[str, Any]] = {}
    for part_id, old_layout in sorted(v3_layouts.items()):
        layout = copy.deepcopy(old_layout)
        layout["schemaVersion"] = 4
        if part_id in TARGETS:
            target = TARGETS[part_id]
            group = next(g for g in layout["groups"] if g["groupId"] == target["groupId"])
            images = [b for b in walk_blocks(group["blocks"]) if b["type"] == "image"]
            if len(images) != 1 or images[0]["overlays"]:
                raise ValueError(f"TARGET_IMAGE_OR_OVERLAY_PRECONDITION_FAILED:{part_id}")
            image = images[0]
            slot_ids = {f"slot-q{number}-0" for number in target["slots"]}
            group_slots = {slot["slotId"]: slot for slot in group["slots"]}
            expected_group_slots = {
                slot_id for slot_id, slot in group_slots.items()
                if slot["questionId"].rsplit("-q", 1)[-1].isdigit()
                and int(slot["questionId"].rsplit("-q", 1)[-1]) in target["slots"]
            }
            if slot_ids != expected_group_slots:
                raise ValueError(f"TARGET_SLOT_SET_MISMATCH:{part_id}")
            variant = variant_for_layout(part_records[part_id], old_layout)
            entry = variant["sourcePrefix"] + image["asset"]
            with zipfile.ZipFile(variant["sourceArchive"]) as archive:
                image_raw = archive.read(entry)
            image_sha = hashlib.sha256(image_raw).hexdigest()
            with Image.open(io.BytesIO(image_raw)) as original_image:
                width, height = original_image.size
            if (width, height) != (image["widthPx"], image["heightPx"]):
                raise ValueError(f"TARGET_IMAGE_DIMENSION_MISMATCH:{part_id}")
            group["blocks"] = _remove_placements(group["blocks"], slot_ids)
            records_by_slot = {}
            for number, (rect, locator) in target["slots"].items():
                slot_id = f"slot-q{number}-0"
                slot = group_slots.get(slot_id)
                if slot is None or slot["kind"] not in {"fill", "singleChoice", "matching"}:
                    raise ValueError(f"TARGET_SLOT_KIND_UNSUPPORTED:{part_id}:{slot_id}")
                if rect["x"] + rect["width"] > width or rect["y"] + rect["height"] > height:
                    raise ValueError(f"TARGET_RECT_OUT_OF_BOUNDS:{part_id}:{slot_id}")
                measurement_id = f"model-{part_id.replace('cambridge-', 'c').replace('-test-', '-t').replace('-part-', '-p')}-q{number}-s0"
                overlay = {
                    "slotId": slot_id,
                    "rectPx": rect,
                    "evidence": {"kind": "modelMeasurement", "measurementId": measurement_id,
                                 "imageSha256": image_sha},
                }
                image["overlays"].append(overlay)
                qid = slot["questionId"]
                record = {
                    "measurementId": measurement_id,
                    "method": "modelMeasurement",
                    "partId": part_id,
                    "groupId": group["groupId"],
                    "questionId": qid,
                    "slotId": slot_id,
                    "slotIndex": slot["slotIndex"],
                    "imageAsset": image["asset"],
                    "imageSha256": image_sha,
                    "imageWidthPx": width,
                    "imageHeightPx": height,
                    "rectPx": rect,
                    "sourceArchive": variant["sourceArchive"],
                    "sourceArchiveSha256": archive_hashes[variant["sourceArchive"]],
                    "sourceEntry": entry,
                    "sourceEntrySha256": image_sha,
                    "visualLocator": locator,
                    "measurementBasis": "Model measurement against the original ZIP image at native pixel resolution; no human review is claimed.",
                }
                all_model_records.append(record)
                records_by_slot[slot_id] = record
                target_details.append({
                    "partId": part_id, "groupId": group["groupId"], "questionId": qid,
                    "questionNumber": number, "slotId": slot_id, "slotKind": slot["kind"],
                    "rectPx": rect, "imageSha256": image_sha, "imageWidthPx": width,
                    "imageHeightPx": height, "sourceArchive": variant["sourceArchive"],
                    "sourceArchiveSha256": archive_hashes[variant["sourceArchive"]],
                    "sourceEntry": entry, "sourceEntrySha256": image_sha,
                    "measurementId": measurement_id, "evidenceType": "modelMeasurement",
                    "visualLocator": locator,
                })
            OUT.mkdir(parents=True, exist_ok=True)
            draw_preview(image_raw, part_id, image_sha, (width, height), image["overlays"],
                         records_by_slot, OUT / "previews" / f"{part_id}.png")
        v4_layouts[part_id] = layout

    if len(all_model_records) != 43 or len(target_details) != 43:
        raise ValueError(f"EXPECTED_43_MODEL_MEASUREMENTS_GOT_{len(all_model_records)}")
    write_json(OUT / "model-measurement-records.json", {
        "measurementType": "modelMeasurement",
        "humanReviewClaimed": False,
        "measurementCount": len(all_model_records),
        "records": all_model_records,
    })
    write_json(OUT / "hotspot-coordinates-and-sources.json", {
        "hotspotCount": len(target_details), "targetGroupCount": len(TARGETS), "records": target_details,
    })

    # Retain the inherited V3 review evidence byte-for-byte.
    old_measurements = V3 / "image-measurement-review-records.json"
    if old_measurements.exists():
        (OUT / old_measurements.name).write_bytes(old_measurements.read_bytes())

    v3_group_audit = read_json(V3 / "group-conversion-records.json")
    v3_group_records = v3_group_audit.get("groups", v3_group_audit.get("records", []))
    group_audit_by_id = {(r.get("partId"), r.get("groupId")): r for r in v3_group_records}
    target_groups_audit = []
    for part_id, target in TARGETS.items():
        prior = group_audit_by_id.get((part_id, target["groupId"]), {})
        prior_basis = (prior.get("decisionBasis") or {}).get("basis", "")
        correction = None
        if part_id in {"cambridge-5-test-1-part-2", "cambridge-16-test-2-part-3"}:
            correction = {
                "priorAuditClaimCorrected": True,
                "correctionReason": "The bound V3 image block had an empty overlays list; these Slots were external answer controls.",
                "correctedFact": "V3 had no image overlays in this Group; the Slots were external controls. V4 adds the model-measured image overlays below.",
                "supersededPriorDecisionBasis": prior_basis,
            }
        target_groups_audit.append({
            "partId": part_id,
            "groupId": target["groupId"],
            "questionIds": v4_layouts[part_id]["groups"][next(i for i,g in enumerate(v4_layouts[part_id]["groups"])
                                                             if g["groupId"] == target["groupId"])]["questionIds"],
            "status": "converted",
            "newOverlaySlotIds": [record["slotId"] for record in all_model_records if record["partId"] == part_id],
            "removedExternalControlSlotIds": [record["slotId"] for record in all_model_records if record["partId"] == part_id],
            "preservedOptionPools": True,
            "preservedResponseRules": True,
            "decisionBasis": "Each rectangle encloses the printed blank for its question in the native-resolution original ZIP image. The existing Group, response and slot definitions remain unchanged.",
            "priorAuditCorrection": correction,
        })
    write_json(OUT / "group-hotspot-migration-records.json", {
        "schemaVersion": 4,
        "targetGroups": target_groups_audit,
        "targetCount": len(target_groups_audit),
    })

    # C13 duplicate ZIP source comparison, including adopted variant and relevant differences.
    c13_comparison = compare_c13_variants(part_records["cambridge-13-test-1-part-3"],
                                           variant_for_layout(part_records["cambridge-13-test-1-part-3"],
                                                              v3_layouts["cambridge-13-test-1-part-3"]),
                                           v3_layouts["cambridge-13-test-1-part-3"], archive_hashes)
    if not (c13_comparison["comparison"]["imageBytesIdentical"]
            and c13_comparison["comparison"]["questionsTxtBytesIdentical"]
            and c13_comparison["comparison"]["questionAndAnswerRegionMatches"]):
        raise ValueError("C13_SOURCE_VARIANT_DIFFERENCE_REQUIRES_REVIEW")
    write_json(OUT / "source-variant-comparisons.json", {"comparisons": [c13_comparison]})

    # Per-Part V4 validation reads every candidate back from disk and verifies its bound Part bytes.
    validation_parts = []
    diff_parts = []
    layout_root = OUT / "listening"
    for part_id, layout in sorted(v4_layouts.items()):
        part_record = part_records[part_id]
        part_bytes = part_bytes_from_pack(part_record["book"], part_id)
        if hashlib.sha256(part_bytes).hexdigest() != layout["basePartSha256"]:
            raise ValueError(f"BASE_PART_DOES_NOT_MATCH_V3:{part_id}")
        path = layout_root / part_id / "layout.json"
        write_json(path, layout)
        prior_reviews_path = V3 / "listening" / part_id / "overlay-review-records.json"
        reviews = read_json(prior_reviews_path) if prior_reviews_path.exists() else []
        if prior_reviews_path.exists():
            (path.parent / prior_reviews_path.name).write_bytes(prior_reviews_path.read_bytes())
        variant = variant_for_layout(part_record, layout)
        source_bytes, archive, _entries = source_bundle(variant)
        try:
            image_metadata, _image_bytes = image_metadata_for(layout, variant, archive, source_bytes)
        finally:
            archive.close()
        model_records = [record for record in all_model_records if record["partId"] == part_id]
        raw_from_disk = path.read_bytes()
        checked = validate_layout_v4(
            raw_from_disk, part_bytes,
            source_bytes=source_bytes,
            image_metadata=image_metadata,
            review_records=reviews,
            model_measurement_records=model_records,
            source_archive_hashes=archive_hashes,
        )
        q_count = len({qid for group in checked["groups"] for qid in group["questionIds"]})
        validation_parts.append({
            "partId": part_id,
            "path": path.relative_to(OUT).as_posix(),
            "status": "passed",
            "layoutSha256": hashlib.sha256(raw_from_disk).hexdigest(),
            "basePartSha256": hashlib.sha256(part_bytes).hexdigest(),
            "questionCount": q_count,
            "sourceArchive": variant["sourceArchive"],
            "sourceArchiveSha256": archive_hashes[variant["sourceArchive"]],
            "sourceResourceHashes": {k: hashlib.sha256(v).hexdigest() for k, v in source_bytes.items()
                                      if k in {"content.html", "content.txt"}},
            "imageCount": len(image_metadata),
            "newModelMeasurementCount": len(model_records),
        })

        target_slot_ids = ({record["slotId"] for record in all_model_records if record["partId"] == part_id}
                           if part_id in TARGETS else set())
        old_layout = v3_layouts[part_id]
        old_canonical = normalized_layout_for_comparison(old_layout, target_slot_ids)
        new_canonical = normalized_layout_for_comparison(checked, target_slot_ids)
        if old_canonical != new_canonical:
            raise ValueError(f"UNEXPECTED_V3_V4_LAYOUT_CHANGE:{part_id}")
        diff_parts.append({
            "partId": part_id,
            "classification": "targetHotspotChanges" if part_id in TARGETS else "mechanicalVersionOnly",
            "oldSchemaVersion": old_layout["schemaVersion"],
            "newSchemaVersion": checked["schemaVersion"],
            "canonicalNonPlacementContentEqual": True,
            "changedSlotIds": sorted(target_slot_ids),
            "newModelMeasurementCount": len(model_records),
            "removedControlBlockIds": _removed_control_block_ids(old_layout, checked, target_slot_ids),
        })

    write_json(OUT / "layout-validation.json", {
        "schemaVersion": 4, "validatedParts": len(validation_parts),
        "passed": sum(item["status"] == "passed" for item in validation_parts),
        "failed": sum(item["status"] != "passed" for item in validation_parts),
        "parts": validation_parts,
    })
    write_json(OUT / "v3-v4-diff.json", {
        "baseline": "private-data/layout-v3-unified-candidates",
        "candidate": "private-data/layout-v4-unified-candidates",
        "partCount": len(diff_parts),
        "mechanicalVersionOnlyCount": sum(x["classification"] == "mechanicalVersionOnly" for x in diff_parts),
        "targetHotspotChangePartCount": sum(x["classification"] == "targetHotspotChanges" for x in diff_parts),
        "canonicalNonPlacementContentEqualForAll": all(x["canonicalNonPlacementContentEqual"] for x in diff_parts),
        "targetGroupCount": len(TARGETS),
        "targetSlotCount": len(all_model_records),
        "parts": diff_parts,
    })
    write_json(OUT / "unresolved-items.json", {"items": [], "unresolvedCount": 0})

    # Recheck the 43 slot-to-question relations, single placement, bounds, and all no-overlay source hashes.
    _postbuild_checks(v4_layouts, all_model_records, no_overlay_audit)
    question_count = sum(item["questionCount"] for item in validation_parts)
    type_counts = Counter()
    for part_id, layout in v4_layouts.items():
        pack_bytes = part_bytes_from_pack(part_records[part_id]["book"], part_id)
        part = json.loads(pack_bytes.decode("utf-8"))
        type_counts.update(q["type"] for q in part["questions"])
    write_json(OUT / "summary.json", {
        "batchName": "V4 image hotspots",
        "schemaVersion": 4,
        "baselineV3CandidateParts": len(v3_layouts),
        "candidateLayoutFiles": len(validation_parts),
        "questionsCovered": question_count,
        "layoutValidation": {"passed": len(validation_parts), "failed": 0},
        "mechanicalOnlyParts": 264,
        "hotspotTargetParts": len(TARGETS),
        "hotspotTargetGroups": len(TARGETS),
        "newImageHotspots": len(all_model_records),
        "noOverlaySourceGroupsChecked": len(no_overlay_audit),
        "retainedBesideImageGroups": 19,
        "questionTypeCounts": dict(sorted(type_counts.items())),
        "unresolvedCount": 0,
        "sourceArchiveHashes": archive_hashes,
        "androidSupportClaim": False,
    })
    print(f"V4_BUILD_OK parts={len(validation_parts)} questions={question_count} modelMeasurements={len(all_model_records)}")


def _removed_control_block_ids(old_layout: dict[str, Any], new_layout: dict[str, Any],
                               target_slot_ids: set[str]) -> list[str]:
    if not target_slot_ids:
        return []
    old_ids = {block["id"] for group in old_layout["groups"] for block in walk_blocks(group["blocks"])}
    new_ids = {block["id"] for group in new_layout["groups"] for block in walk_blocks(group["blocks"])}
    removed = old_ids - new_ids
    return sorted(removed)


def _postbuild_checks(layouts: dict[str, dict[str, Any]], measurements: list[dict[str, Any]],
                      no_overlay_audit: list[dict[str, Any]]) -> None:
    by_part = defaultdict(list)
    for record in measurements:
        by_part[record["partId"]].append(record)
    if sum(len(items) for items in by_part.values()) != 43:
        raise ValueError("MODEL_MEASUREMENT_TOTAL_MISMATCH")
    for part_id, target in TARGETS.items():
        layout = layouts[part_id]
        group = next(g for g in layout["groups"] if g["groupId"] == target["groupId"])
        expected = {f"slot-q{number}-0" for number in target["slots"]}
        image = next(b for b in walk_blocks(group["blocks"]) if b["type"] == "image")
        actual = {item["slotId"] for item in image["overlays"]}
        if actual != expected or len(image["overlays"]) != len(expected):
            raise ValueError(f"TARGET_OVERLAY_SET_MISMATCH:{part_id}")
        for record in by_part[part_id]:
            if record["questionId"] != next(s["questionId"] for s in group["slots"]
                                               if s["slotId"] == record["slotId"]):
                raise ValueError(f"TARGET_QUESTION_SLOT_MISMATCH:{record['slotId']}")
            if record["rectPx"]["x"] + record["rectPx"]["width"] > record["imageWidthPx"]:
                raise ValueError(f"TARGET_X_BOUNDS:{record['slotId']}")
            if record["rectPx"]["y"] + record["rectPx"]["height"] > record["imageHeightPx"]:
                raise ValueError(f"TARGET_Y_BOUNDS:{record['slotId']}")
            placements = [block for block in walk_blocks(group["blocks"])
                          if block["type"] == "slot" and block["slotId"] == record["slotId"]]
            choices = [block for block in walk_blocks(group["blocks"])
                       if block["type"] == "choiceList" and record["slotId"] in block["slotIds"]]
            overlay_count = sum(1 for block in walk_blocks(group["blocks"]) if block["type"] == "image"
                                for overlay in block["overlays"] if overlay["slotId"] == record["slotId"])
            if placements or choices or overlay_count != 1:
                raise ValueError(f"TARGET_DUPLICATE_OR_MISSING_SLOT:{record['slotId']}")
    if len(no_overlay_audit) != 27:
        raise ValueError("NO_OVERLAY_AUDIT_COUNT_MISMATCH")


if __name__ == "__main__":
    build()
