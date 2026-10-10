"""Private Phase 2 verifier. Writes only under private-data/layout-prototype/."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import zipfile

from bs4 import BeautifulSoup

from import_library import IDENTITY, inventory, load_corrections, normalize, sha, text, visible, word_limit_instruction, _word_limit_phrase
from layout_generator import encode_layout, generate_layout
from layout_validator import validate_layout


SAMPLES = (
    ("cambridge-5-test-1-part-3", (26, 30)),
    ("cambridge-5-test-2-part-4", (31, 40)),
    ("cambridge-5-test-3-part-1", (1, 10)),
    ("cambridge-5-test-1-part-4", (31, 35)),
)
WORD_LIMIT_CASES = (
    "cambridge-14-test-1-part-4",
    "cambridge-14-test-2-part-4",
    "cambridge-20-test-4-part-4",
    "cambridge-21-test-2-part-4",
    "cambridge-9-test-1-part-1",
)


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _diff_paths(left, right, prefix=""):
    if type(left) is not type(right):
        return [prefix]
    if isinstance(left, dict):
        paths = []
        for key in sorted(set(left) | set(right)):
            path = f"{prefix}.{key}" if prefix else key
            if key not in left or key not in right:
                paths.append(path)
            else:
                paths.extend(_diff_paths(left[key], right[key], path))
        return paths
    if isinstance(left, list):
        if len(left) != len(right):
            return [f"{prefix}.length"]
        return [path for index, (a, b) in enumerate(zip(left, right))
                for path in _diff_paths(a, b, f"{prefix}[{index}]")]
    return [] if left == right else [prefix]


def _relative_diff(paths):
    return sorted({re.sub(r"questions\[\d+\]", "questions[*]", path) for path in paths})


def _source_part_record(archives: list[Path], part_id: str):
    match = re.fullmatch(r"cambridge-(\d+)-test-(\d+)-part-(\d+)", part_id)
    if not match:
        raise ValueError("INVALID_PART_ID")
    book, test, part = map(int, match.groups())
    for path in archives:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                identity = IDENTITY.search(name)
                if identity and tuple(map(int, identity.groups())) == (book, test, part):
                    return path, name[:-9]
    raise ValueError("SOURCE_PART_MISSING")


def _group_limit_evidence(html: bytes, questions_txt: bytes) -> dict:
    soup = BeautifulSoup(text(html), "html.parser")
    candidates = []
    for wrapper in soup.select("section.questions #analys-wrap-container > div"):
        outer = wrapper.find("div", recursive=False)
        title = outer.find("div", recursive=False) if outer else None
        if title is None or not title.select("[data-ielts-blank]"):
            continue
        context = visible(title)
        instruction = " ".join(visible(item) for item in title.find_all("i")
                               if any(token in visible(item) for token in
                                      ("Question", "Write", "Choose", "Complete", "Label", "Answer")))
        rule = word_limit_instruction(instruction, context) or _word_limit_phrase(instruction)
        if rule:
            candidates.append(rule)
    text_rules = []
    for line in text(questions_txt).splitlines():
        rule = _word_limit_phrase(line)
        if rule:
            text_rules.append(rule)
    return {"htmlRules": list(dict.fromkeys(candidates)),
            "questionsTxtRules": list(dict.fromkeys(text_rules))}


def verify_reproducibility(archives: list[Path], packs: Path, corrections_path: Path) -> dict:
    corrections = load_corrections(corrections_path)
    audit = inventory(archives, corrections)
    records = [record for record in audit["parts"] if record["status"] == "validated"]
    if len(records) != 272:
        raise ValueError("SOURCE_PART_VALIDATION_COUNT_MISMATCH")
    by_source = defaultdict(list)
    for record in records:
        by_source[record["source"]].append(record)
    package_models = {}
    package_indexes = {}
    for pack_path in packs.glob("cambridge-*.eelpack"):
        with zipfile.ZipFile(pack_path) as package:
            index = json.loads(package.read("index.json"))
            package_indexes[pack_path] = {entry["id"]: entry for entry in index["parts"]}
            package_models[pack_path] = {entry["id"]: json.loads(package.read(f"listening/{entry['id']}/part.json"))
                                         for entry in index["parts"]}

    exact_parts = 0
    media_exact_parts = 0
    changed_fields = defaultdict(set)
    unexpected_parts = []
    for source_name, source_records in by_source.items():
        with zipfile.ZipFile(source_name) as source:
            for record in source_records:
                part_id = record["id"]
                pack_path = packs / f"cambridge-{record['book']}.eelpack"
                if pack_path not in package_models or part_id not in package_models[pack_path]:
                    unexpected_parts.append({"partId": part_id, "fields": ["packMissing"]})
                    continue
                model, audio, images = normalize(
                    lambda name, prefix=record["prefix"]: source.read(prefix + name),
                    record["book"], record["test"], record["part"], corrections)
                packaged = package_models[pack_path][part_id]
                paths = _diff_paths(model, packaged)
                fields = _relative_diff(paths)
                if not paths:
                    exact_parts += 1
                elif fields == ["questions[*].wordLimit.maxNumbers"]:
                    changed_fields[part_id].update(fields)
                else:
                    unexpected_parts.append({"partId": part_id, "fields": fields})

                item = package_indexes[pack_path][part_id]
                prefix = f"listening/{part_id}/"
                with zipfile.ZipFile(pack_path) as package:
                    part_bytes = package.read(prefix + "part.json")
                    package_audio = package.read(prefix + "audio.mp3")
                    package_images = {path: package.read(prefix + path) for path in item["imageHashes"]}
                media_equal = audio == package_audio and images == package_images
                if media_equal:
                    media_exact_parts += 1
                else:
                    unexpected_parts.append({"partId": part_id, "fields": ["audioOrImages"]})
                if sha(part_bytes) != item["partSha256"]:
                    unexpected_parts.append({"partId": part_id, "fields": ["partSha256"]})

    changed_part_ids = sorted(changed_fields)
    expected_differences = {
        "cambridge-14-test-1-part-4", "cambridge-14-test-2-part-4",
        "cambridge-20-test-4-part-4", "cambridge-21-test-2-part-4",
    }
    if set(changed_part_ids) != expected_differences or unexpected_parts or media_exact_parts != 272:
        raise ValueError("UNEXPECTED_REPRODUCIBILITY_DIFFERENCE")

    evidence = []
    for part_id in (*WORD_LIMIT_CASES[:4], WORD_LIMIT_CASES[4]):
        source_path, prefix = _source_part_record(archives, part_id)
        with zipfile.ZipFile(source_path) as source:
            html = source.read(prefix + "content.html")
            questions_txt = source.read(prefix + "questions.txt")
            model, _, _ = normalize(lambda name: source.read(prefix + name),
                                    int(part_id.split("-")[1]), int(part_id.split("-test-")[1].split("-part-")[0]),
                                    int(part_id.split("-part-")[1]), corrections)
        pack_path = packs / f"cambridge-{part_id.split('-')[1]}.eelpack"
        with zipfile.ZipFile(pack_path) as package:
            packaged = json.loads(package.read(f"listening/{part_id}/part.json"))
        source_rules = _group_limit_evidence(html, questions_txt)
        evidence.append({
            "partId": part_id,
            "correctionRecordPresent": part_id in corrections,
            "sourceInstructionEvidence": source_rules,
            "packedWordLimits": sorted({json.dumps(q.get("wordLimit"), sort_keys=True)
                                         for q in packaged["questions"]}),
            "rerunWordLimits": sorted({json.dumps(q.get("wordLimit"), sort_keys=True)
                                        for q in model["questions"]}),
            "fieldDifference": "questions[*].wordLimit.maxNumbers" if part_id in changed_fields else None,
            "reasonCode": ("LEGACY_CONTEXT_NUMBER_TOKEN_CONTAMINATION" if part_id in changed_fields
                           else "SPLIT_WRITE_LIMIT_HTML_RECOVERED"),
        })
    return {
        "sourceAudit": {"archives": len(archives), "sourceParts": audit["summary"]["sourceParts"],
                        "duplicateCopies": audit["summary"]["duplicateParts"],
                        "uniquePartsValidated": audit["summary"]["validatedParts"],
                        "questions": audit["summary"]["questions"], "failedParts": audit["summary"]["failedOrConflictingParts"]},
        "packComparison": {"exactParts": exact_parts, "onlyWordLimitParts": len(changed_part_ids),
                           "onlyWordLimitPartIds": changed_part_ids, "mediaExactParts": media_exact_parts,
                           "unexpectedDifferences": unexpected_parts},
        "wordLimitCases": evidence,
    }


def build_sample_pack(archives: list[Path], packs: Path, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    archive_path = next(path for path in archives if "5-13" in path.name)
    source_pack = packs / "cambridge-5.eelpack"
    with zipfile.ZipFile(source_pack) as package:
        source_index = json.loads(package.read("index.json"))
        if source_index["schemaVersion"] != 1:
            raise ValueError("UNEXPECTED_SOURCE_INDEX_VERSION")
        source_entries = {entry["id"]: entry for entry in source_index["parts"]}
        resources: dict[str, bytes] = {}
        layout_records = []
        for part_id, question_range in SAMPLES:
            original_summary = source_entries[part_id]
            part_prefix = f"listening/{part_id}/"
            part_bytes = package.read(part_prefix + "part.json")
            media_names = ["audio.mp3", *sorted(original_summary["imageHashes"])]
            for name in ["part.json", *media_names]:
                resources[part_prefix + name] = package.read(part_prefix + name)
            source_path, source_prefix = _source_part_record(archives, part_id)
            with zipfile.ZipFile(source_path) as source:
                html_bytes = source.read(source_prefix + "content.html")
            layout, statuses = generate_layout(part_bytes, html_bytes, question_range=question_range)
            if len(layout["groups"]) != 1 or statuses[0]["status"] != "converted":
                raise ValueError("REAL_SAMPLE_LAYOUT_NOT_CONVERTED")
            actual_numbers = [block["questionNumber"] for group in layout["groups"]
                              for block in _walk_blocks(group["blocks"]) if block["type"] == "blank"]
            if actual_numbers != list(range(question_range[0], question_range[1] + 1)):
                raise ValueError("REAL_SAMPLE_QUESTION_NUMBER_MISMATCH")
            validate_layout(layout, json.loads(part_bytes), part_json_bytes=part_bytes,
                            source_html_bytes=html_bytes)
            layout_bytes = encode_layout(layout)
            resources[part_prefix + "layout.json"] = layout_bytes
            layout_path = output / "layouts" / part_id / "layout.json"
            layout_path.parent.mkdir(parents=True, exist_ok=True)
            layout_path.write_bytes(layout_bytes)
            summary = dict(original_summary)
            summary["layoutSha256"] = sha(layout_bytes)
            layout_records.append({"partId": part_id, "questionRange": list(question_range),
                                   "status": statuses[0]["status"], "questionCount": len(actual_numbers),
                                   "sourceProjectionMatches": statuses[0].get("sourceProjectionMatches", False),
                                   "blockTypeCounts": dict(Counter(block["type"] for group in layout["groups"]
                                                                     for block in _walk_blocks(group["blocks"]))),
                                   "layoutSha256": summary["layoutSha256"],
                                   "basePartSha256MatchesIndex": layout["basePartSha256"] == original_summary["partSha256"]})
            if not layout_records[-1]["basePartSha256MatchesIndex"]:
                raise ValueError("SAMPLE_BASE_PART_HASH_MISMATCH")
            layout_records[-1]["summary"] = summary

    index = {"schemaVersion": 2, "book": 5,
             "parts": [record.pop("summary") for record in layout_records]}
    index_bytes = _json_bytes(index)
    pack_path = output / "cambridge-5-layout-prototype.eelpack"
    resources["index.json"] = index_bytes
    with zipfile.ZipFile(pack_path, "w") as archive:
        for name in sorted(resources, key=lambda item: (item != "index.json", item)):
            compression = zipfile.ZIP_STORED if name.endswith(".mp3") else zipfile.ZIP_DEFLATED
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = compression
            info.create_system = 0
            archive.writestr(info, resources[name])

    with zipfile.ZipFile(pack_path) as archive:
        if archive.testzip() is not None:
            raise ValueError("PROTOTYPE_PACK_CRC_ERROR")
        actual_names = set(archive.namelist())
        expected_names = set(resources)
        if actual_names != expected_names:
            raise ValueError("PROTOTYPE_PACK_ENTRY_SET_MISMATCH")
        decoded_index = json.loads(archive.read("index.json"))
        if decoded_index["schemaVersion"] != 2 or len(decoded_index["parts"]) != 4:
            raise ValueError("PROTOTYPE_INDEX_INVALID")
        for summary in decoded_index["parts"]:
            prefix = f"listening/{summary['id']}/"
            raw_part = archive.read(prefix + "part.json")
            raw_layout = archive.read(prefix + "layout.json")
            if sha(raw_part) != summary["partSha256"] or sha(raw_layout) != summary["layoutSha256"]:
                raise ValueError("PROTOTYPE_DECLARED_HASH_MISMATCH")

    return {"samplePartCount": 4, "pack": str(pack_path), "packBytes": pack_path.stat().st_size,
            "zipEntryCount": len(resources), "entrySetExact": True, "layoutFiles": layout_records}


def _walk_blocks(blocks):
    for block in blocks:
        yield block
        yield from _walk_blocks(block.get("children", []))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", nargs=3, required=True, help="Three local original Cambridge source ZIPs")
    parser.add_argument("--packs", default="private-data/library/pending-six/updated-library")
    parser.add_argument("--corrections", default="private-data/library/pending-six/corrections-private.json")
    parser.add_argument("--output", default="private-data/layout-prototype")
    args = parser.parse_args()
    archives = [Path(path) for path in args.sources]
    output = Path(args.output)
    report = {
        "schemaVersion": 1,
        "converter": verify_reproducibility(archives, Path(args.packs), Path(args.corrections)),
        "realSamples": build_sample_pack(archives, Path(args.packs), output),
        "policy": "local-only prototype; no official book packs or source archives were modified",
    }
    (output / "verification-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"validatedParts": report["converter"]["sourceAudit"]["uniquePartsValidated"],
                      "exactParts": report["converter"]["packComparison"]["exactParts"],
                      "wordLimitDifferences": report["converter"]["packComparison"]["onlyWordLimitPartIds"],
                      "samplePartCount": report["realSamples"]["samplePartCount"],
                      "prototypePackBytes": report["realSamples"]["packBytes"]}, ensure_ascii=True))


if __name__ == "__main__":
    main()
