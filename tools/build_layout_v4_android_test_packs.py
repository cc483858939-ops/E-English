"""Build private Android import-test packs from the verified V4 layout candidates.

The script does not inspect or infer layout structure. It binds the existing V4
documents to the exact baseline Part bytes, replays the offline V4 source/image
checks against the original ZIPs, then changes only index layout digests and
layout.json entries in new pack copies.
"""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Any

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / "private-data"
BASE = PRIVATE / "library" / "pending-six" / "updated-library"
CANDIDATES = PRIVATE / "layout-v4-unified-candidates"
OUTPUT = PRIVATE / "layout-v4-android-test-packs"
V3_AUDIT = PRIVATE / "layout-v3-unified-candidates" / "group-conversion-records.json"
SOURCE_ZIPS = [
    Path(r"D:\IELTS-Listening-Cambridge-5-13_已修复.zip"),
    Path(r"D:\IELTS-Listening-Cambridge-13-17.zip"),
    Path(r"D:\IELTS-Listening-Cambridge-18-21.zip"),
]
SOURCE_HASHES_EXPECTED = {
    str(SOURCE_ZIPS[0]): "3443dade8ca6990fcafa623a6d6d15e329892c3e23742d7b0200d72893c5ef42",
    str(SOURCE_ZIPS[1]): "77fe1b45800a52722aa99e80b8103fff5cfef461df63432449adf9af96b21ebf",
    str(SOURCE_ZIPS[2]): "fa627f95ea6919d42f129e3a8713902e64a58293fe542391cc84af0561afcac1",
}

sys.path.insert(0, str(ROOT / "tools"))
from layout_v4_validator import validate_layout_v4  # noqa: E402


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def read_json_bytes(raw: bytes) -> Any:
    return json.loads(raw.decode("utf-8"))


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


def referenced_resource_hashes(layout: dict[str, Any]) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for group in layout["groups"]:
        for block in [*group["instructions"], *walk_blocks(group["blocks"])]:
            if block["type"] != "text":
                continue
            source = block["source"]
            if source["kind"] == "resourceRange":
                result.setdefault(source["resource"], set()).add(source["sha256"])
    return result


def variants_by_part() -> dict[str, list[dict[str, Any]]]:
    audit = json.loads(V3_AUDIT.read_text(encoding="utf-8"))
    records = audit.get("groups", audit.get("records", []))
    result: dict[str, dict[tuple[str, str], dict[str, Any]]] = {}
    for record in records:
        part_id = record["partId"]
        for variant in record.get("sourceVariants", []):
            key = (variant["sourceArchive"], variant["sourcePrefix"])
            result.setdefault(part_id, {})[key] = variant
    return {part_id: list(values.values()) for part_id, values in result.items()}


def source_variant(layout: dict[str, Any], validation: dict[str, Any], variants: list[dict[str, Any]]) -> dict[str, Any]:
    refs = referenced_resource_hashes(layout)
    matches = []
    for variant in variants:
        if variant["archiveSha256"] != validation["sourceArchiveSha256"]:
            continue
        files = variant["sourceFiles"]
        good = True
        for resource, hashes in refs.items():
            name = "questions.txt" if resource == "content.txt" and "content.txt" not in files else resource
            if name not in files or hashes != {files[name]["sha256"]}:
                good = False
                break
        if good:
            matches.append(variant)
    if len(matches) != 1:
        raise ValueError(f"SOURCE_VARIANT_UNRESOLVED:{layout['partId']}:{len(matches)}")
    return matches[0]


def source_bundle(variant: dict[str, Any], archives: dict[str, zipfile.ZipFile]) -> tuple[dict[str, bytes], zipfile.ZipFile]:
    archive = archives[variant["sourceArchive"]]
    prefix = variant["sourcePrefix"]
    entries = {name: archive.read(name) for name in archive.namelist()
               if name.startswith(prefix) and not name.endswith("/")}
    sources: dict[str, bytes] = dict(entries)
    for name, raw in entries.items():
        relative = name[len(prefix):]
        if relative:
            sources.setdefault(relative, raw)
    if "questions.txt" in sources:
        sources["content.txt"] = sources["questions.txt"]
    return sources, archive


def v4_images(layout: dict[str, Any], variant: dict[str, Any], archive: zipfile.ZipFile,
              sources: dict[str, bytes]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for group in layout["groups"]:
        for block in walk_blocks(group["blocks"]):
            if block["type"] != "image":
                continue
            entry = variant["sourcePrefix"] + block["asset"]
            raw = archive.read(entry)
            with Image.open(io.BytesIO(raw)) as image:
                width, height = image.size
            digest = sha(raw)
            if (block["widthPx"], block["heightPx"]) != (width, height):
                raise ValueError(f"IMAGE_DIMENSION_MISMATCH:{layout['partId']}:{block['asset']}")
            result[block["asset"]] = {"sha256": digest, "widthPx": width, "heightPx": height}
            sources[entry] = raw
            sources.setdefault(block["asset"], raw)
    return result


def find_pack_part(pack: zipfile.ZipFile, part_id: str) -> bytes:
    return pack.read(f"listening/{part_id}/part.json")


def patch_index_only_layout_sha(index_bytes: bytes, layout_hashes: dict[str, str]) -> bytes:
    text = index_bytes.decode("utf-8")
    index = read_json_bytes(index_bytes)
    if index.get("schemaVersion") not in (1, 2):
        raise ValueError("UNSUPPORTED_BASELINE_INDEX_SCHEMA")
    if {entry["id"] for entry in index["parts"]} != set(layout_hashes):
        raise ValueError("PACK_PART_SET_MISMATCH")
    decoder = json.JSONDecoder()
    parts_key = re.search(r'"parts"\s*:\s*\[', text)
    if not parts_key:
        raise ValueError("PACK_INDEX_PARTS_MISSING")
    cursor = parts_key.end()
    spans = []
    for expected in index["parts"]:
        while cursor < len(text) and text[cursor].isspace():
            cursor += 1
        start = cursor
        value, end = decoder.raw_decode(text, cursor)
        if not isinstance(value, dict) or value.get("id") != expected["id"]:
            raise ValueError("PACK_INDEX_PART_ORDER_OR_SHAPE_MISMATCH")
        spans.append((start, end, value))
        cursor = end
        while cursor < len(text) and text[cursor].isspace():
            cursor += 1
        if len(spans) < len(index["parts"]):
            if cursor >= len(text) or text[cursor] != ",":
                raise ValueError("PACK_INDEX_PARTS_INVALID")
            cursor += 1

    pieces = []
    cursor = 0
    for start, end, entry in spans:
        segment = text[start:end]
        target_hash = json.dumps(layout_hashes[entry["id"]])
        property_match = re.search(r'("layoutSha256"\s*:\s*)(?:null|"[a-f0-9]{64}")', segment)
        if property_match:
            replacement = segment[:property_match.start()] + property_match.group(1) + target_hash + segment[property_match.end():]
        else:
            replacement = segment[:-1] + ',"layoutSha256":' + target_hash + "}"
        pieces.extend((text[cursor:start], replacement))
        cursor = end
    pieces.append(text[cursor:])
    patched_text = "".join(pieces)
    version_match = re.search(r'("schemaVersion"\s*:\s*)[12](?!\d)', patched_text)
    if not version_match:
        raise ValueError("PACK_INDEX_SCHEMA_VERSION_MISSING")
    patched_text = (patched_text[:version_match.start()] + version_match.group(1) + "2" +
                    patched_text[version_match.end():])
    patched = patched_text.encode("utf-8")
    before = read_json_bytes(index_bytes)
    after = read_json_bytes(patched)
    for old, new in zip(before["parts"], after["parts"], strict=True):
        if new != {**old, "layoutSha256": layout_hashes[old["id"]]}:
            raise ValueError(f"UNEXPECTED_INDEX_CHANGE:{old['id']}")
    before.pop("parts")
    after.pop("parts")
    before["schemaVersion"] = 2
    if before != after:
        raise ValueError("UNEXPECTED_INDEX_CHANGE")
    return patched


def build_pack(book: int, layouts: dict[str, bytes]) -> dict[str, Any]:
    baseline = BASE / f"cambridge-{book}.eelpack"
    output = OUTPUT / f"cambridge-{book}-v4-test.eelpack"
    with zipfile.ZipFile(baseline, "r") as source:
        if source.testzip() is not None:
            raise ValueError(f"BASELINE_CRC_FAILURE:{book}")
        infos = source.infolist()
        names = [item.filename for item in infos]
        if len(names) != len(set(names)):
            raise ValueError(f"BASELINE_DUPLICATE_ENTRY:{book}")
        baseline_entries = {info.filename: source.read(info.filename) for info in infos}
    index_before = read_json_bytes(baseline_entries["index.json"])
    ids = [entry["id"] for entry in index_before["parts"]]
    if {int(re.match(r"cambridge-(\d+)-", part_id).group(1)) for part_id in ids} != {book}:
        raise ValueError(f"BASELINE_BOOK_MISMATCH:{book}")
    relevant = {part_id: layouts[part_id] for part_id in ids}
    layout_hashes = {part_id: sha(raw) for part_id, raw in relevant.items()}
    index_after = patch_index_only_layout_sha(baseline_entries["index.json"], layout_hashes)

    expected_names = set(names)
    for part_id in ids:
        expected_names.add(f"listening/{part_id}/layout.json")
    if len(expected_names) > 256:
        raise ValueError(f"IMPORT_ENTRY_LIMIT:{book}:{len(expected_names)}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(baseline, "r") as source, zipfile.ZipFile(output, "w", allowZip64=True) as target:
        for info in source.infolist():
            if info.filename == "index.json":
                data = index_after
            elif info.filename.endswith("/layout.json"):
                part_id = info.filename.split("/")[1]
                if part_id not in relevant:
                    raise ValueError(f"UNEXPECTED_LAYOUT_ENTRY:{book}:{part_id}")
                data = relevant[part_id]
            else:
                data = baseline_entries[info.filename]
            target.writestr(info, data)
        existing_layouts = {name for name in names if name.endswith("/layout.json")}
        for part_id in ids:
            name = f"listening/{part_id}/layout.json"
            if name not in existing_layouts:
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, relevant[part_id])

    # Verify the new archive by entry content, not compressed-stream identity.
    with zipfile.ZipFile(output, "r") as packaged:
        if packaged.testzip() is not None:
            raise ValueError(f"OUTPUT_CRC_FAILURE:{book}")
        packaged_names = [item.filename for item in packaged.infolist()]
        if len(packaged_names) != len(set(packaged_names)) or set(packaged_names) != expected_names:
            raise ValueError(f"OUTPUT_ENTRY_SET_MISMATCH:{book}")
        new_index_raw = packaged.read("index.json")
        new_index = read_json_bytes(new_index_raw)
        if new_index["schemaVersion"] != 2:
            raise ValueError(f"PACK_INDEX_VERSION_CHANGED:{book}")
        for part_id in ids:
            prefix = f"listening/{part_id}/"
            new_layout = packaged.read(prefix + "layout.json")
            if new_layout != relevant[part_id] or sha(new_layout) != layout_hashes[part_id]:
                raise ValueError(f"PACKAGED_LAYOUT_MISMATCH:{part_id}")
            part_raw = packaged.read(prefix + "part.json")
            if sha(part_raw) != read_json_bytes(new_layout)["basePartSha256"]:
                raise ValueError(f"PACKAGED_PART_BINDING_MISMATCH:{part_id}")
            for name, before in baseline_entries.items():
                if not name.startswith(prefix) or name.endswith("/layout.json"):
                    continue
                if packaged.read(name) != before:
                    raise ValueError(f"PACK_RESOURCE_CHANGED:{part_id}:{name.rsplit('/', 1)[-1]}")
        for name, before in baseline_entries.items():
            if name == "index.json" or name.endswith("/layout.json"):
                continue
            if packaged.read(name) != before:
                raise ValueError(f"PACK_RESOURCE_CHANGED:{book}:{name}")
        uncompressed = sum(info.file_size for info in packaged.infolist())
        if uncompressed > 512_000_000:
            raise ValueError(f"IMPORT_UNCOMPRESSED_SIZE_LIMIT:{book}:{uncompressed}")
    return {
        "book": book,
        "path": output.relative_to(ROOT).as_posix(),
        "sha256": file_sha(output),
        "partCount": len(ids),
        "layoutCount": len(ids),
        "entryCount": len(expected_names),
        "uncompressedBytes": uncompressed,
        "baselineIndexSchemaVersion": index_before["schemaVersion"],
        "indexSchemaVersion": 2,
        "indexChanges": {
            "fields": ["schemaVersion", "parts[].layoutSha256"],
            "otherFieldsUnchanged": True,
        },
        "partAudioImageBytesUnchanged": True,
        "crc": "passed",
    }


def main() -> None:
    summary = json.loads((CANDIDATES / "summary.json").read_text(encoding="utf-8"))
    if summary.get("candidateLayoutFiles") != 272 or summary.get("layoutValidation", {}).get("failed") != 0:
        raise ValueError("V4_CANDIDATE_SET_NOT_COMPLETE")
    if len(SOURCE_HASHES_EXPECTED) != 3:
        raise ValueError("THREE_SOURCE_ARCHIVES_REQUIRED")
    archive_hashes = {str(path): file_sha(path) for path in SOURCE_ZIPS}
    if archive_hashes != SOURCE_HASHES_EXPECTED:
        raise ValueError("SOURCE_ARCHIVE_HASH_MISMATCH")
    archives = {str(path): zipfile.ZipFile(path, "r") for path in SOURCE_ZIPS}
    try:
        layouts_root = CANDIDATES / "listening"
        layout_validation = json.loads((CANDIDATES / "layout-validation.json").read_text(encoding="utf-8"))
        validation_by_id = {part["partId"]: part for part in layout_validation["parts"]}
        audit_variants = variants_by_part()
        measurements_raw = json.loads((CANDIDATES / "model-measurement-records.json").read_text(encoding="utf-8"))
        all_measurements = measurements_raw["records"]
        measurement_by_part: dict[str, list[dict[str, Any]]] = {}
        for record in all_measurements:
            measurement_by_part.setdefault(record["partId"], []).append(record)

        part_layouts: dict[str, bytes] = {}
        offline_results = []
        for path in sorted(layouts_root.glob("*/layout.json")):
            part_id = path.parent.name
            book = int(re.match(r"cambridge-(\d+)-", part_id).group(1))
            baseline = BASE / f"cambridge-{book}.eelpack"
            with zipfile.ZipFile(baseline, "r") as pack:
                part_raw = pack.read(f"listening/{part_id}/part.json")
            layout_raw = path.read_bytes()
            layout = json.loads(layout_raw.decode("utf-8"))
            if sha(part_raw) != layout["basePartSha256"]:
                raise ValueError(f"BASE_PART_BINDING_MISMATCH:{part_id}")
            validation = validation_by_id.get(part_id)
            if validation is None or validation.get("status") != "passed":
                raise ValueError(f"MISSING_PRIOR_V4_VALIDATION:{part_id}")
            variant = source_variant(layout, validation, audit_variants.get(part_id, []))
            sources, archive = source_bundle(variant, archives)
            images = v4_images(layout, variant, archive, sources)
            review_path = path.parent / "overlay-review-records.json"
            reviews = json.loads(review_path.read_text(encoding="utf-8")) if review_path.exists() else []
            if isinstance(reviews, dict):
                reviews = reviews.get("records", [])
            result = validate_layout_v4(layout_raw, part_raw, source_bytes=sources,
                image_metadata=images, review_records=reviews,
                model_measurement_records=measurement_by_part.get(part_id, []),
                source_archive_hashes=archive_hashes)
            part_layouts[part_id] = layout_raw
            offline_results.append({
                "partId": part_id,
                "status": "passed",
                "layoutSha256": sha(layout_raw),
                "basePartSha256": sha(part_raw),
                "sourceArchive": variant["sourceArchive"],
                "sourceArchiveSha256": variant["archiveSha256"],
                "sourcePrefix": variant["sourcePrefix"],
                "imageCount": len(images),
                "questionCount": sum(len(group["questionIds"]) for group in result["groups"]),
            })
        if len(part_layouts) != 272 or len(offline_results) != 272:
            raise ValueError(f"EXPECTED_272_LAYOUTS_GOT:{len(part_layouts)}")
        pack_results = [build_pack(book, part_layouts) for book in range(5, 22)]
        result = {
            "schemaVersion": 1,
            "dataset": "Android V4 import test packs",
            "layoutSchemaVersion": 4,
            "packIndexSchemaVersion": 2,
            "candidateCount": len(part_layouts),
            "offlineSourceValidation": {"passed": len(offline_results), "failed": 0, "parts": offline_results},
            "packValidation": {"passed": len(pack_results), "failed": 0, "packs": pack_results},
            "sourceArchiveSha256": archive_hashes,
            "androidImportLimits": {"maxEntries": 256, "maxJsonBytes": 4_000_000,
                "maxEntryBytes": 128_000_000, "maxUncompressedBytes": 512_000_000},
        }
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / "pack-validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"packs": len(pack_results), "layouts": len(part_layouts),
            "output": OUTPUT.relative_to(ROOT).as_posix(),
            "packsSha256": {item["book"]: item["sha256"] for item in pack_results}}, ensure_ascii=False, indent=2))
    finally:
        for archive in archives.values():
            archive.close()


if __name__ == "__main__":
    main()
