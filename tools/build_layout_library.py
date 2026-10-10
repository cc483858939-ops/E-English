"""Generate private Cambridge 5-21 Schema v1 layout sidecars and index-v2 packs.

The source ZIPs and existing verified packs are read-only. Output is restricted
to private-data/ and is published only after all 17 packages pass byte checks.
"""
from __future__ import annotations

import argparse
import copy
import csv
from contextlib import ExitStack
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

from import_library import IDENTITY
from layout_generator import LayoutGenerationIssue, encode_layout, generate_layout
from layout_validator import LayoutValidationError, validate_layout


BOOKS = tuple(range(5, 22))
EXPECTED_GROUPS = 160
EXPECTED_QUESTIONS = 1225
SOURCE_KIND_TO_LAYOUT_KIND = {
    "FORM_COMPLETION_LINEAR_TEXT": "formCompletion",
    "GENERIC_COMPLETION_LINEAR_TEXT": "linearTextCompletion",
    "NOTES_COMPLETION_LINEAR_TEXT": "notesCompletion",
    "SENTENCE_COMPLETION_LINEAR_TEXT": "sentenceCompletion",
    "SHORT_ANSWER_LINEAR_TEXT": "shortAnswerInline",
    "SUMMARY_COMPLETION_LINEAR_TEXT": "summaryCompletion",
}
QUESTION_RANGE = re.compile(r"([1-9]\d{0,2})(?:-([1-9]\d{0,2}))?")
CHUNK_SIZE = 1024 * 1024


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _read_candidates(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    required = {
        "book", "partId", "questionRange", "questionCount", "sourceLayoutKind",
        "classification", "sourceHasExplicitBlankMarkers", "imageDependent",
    }
    if not rows or not required.issubset(rows[0]):
        raise ValueError("LAYOUT_INVENTORY_SCHEMA_INVALID")
    candidates = [row for row in rows if row["classification"] in {"A", "B"}]
    question_count = sum(int(row["questionCount"]) for row in candidates)
    if len(candidates) != EXPECTED_GROUPS or question_count != EXPECTED_QUESTIONS:
        raise ValueError("LAYOUT_INVENTORY_SCOPE_MISMATCH")
    return candidates


def _parse_range(row: dict[str, str]) -> tuple[int, int] | None:
    match = QUESTION_RANGE.fullmatch(row.get("questionRange", ""))
    if not match:
        return None
    first = int(match.group(1))
    last = int(match.group(2) or match.group(1))
    return (first, last) if last >= first else None


def _index_sources(paths: list[Path], stack: ExitStack):
    archives = []
    sources: dict[str, list[tuple[Path, zipfile.ZipFile, str]]] = defaultdict(list)
    for path in paths:
        archive = stack.enter_context(zipfile.ZipFile(path))
        archives.append((path, archive))
        for name in archive.namelist():
            match = IDENTITY.search(name)
            if not match:
                continue
            book, test, part = map(int, match.groups())
            part_id = f"cambridge-{book}-test-{test}-part-{part}"
            sources[part_id].append((path, archive, name[:-len("part.json")]))
    return archives, sources


def _source_hashes_match_audit(paths: list[Path], audit_path: Path) -> dict[str, str]:
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    records = audit.get("archives")
    if not isinstance(records, list):
        raise ValueError("SOURCE_ARCHIVE_AUDIT_MISSING")
    expected = {Path(record["path"]).name.lower(): record["sha256"] for record in records}
    result = {}
    for path in paths:
        digest = file_sha256(path)
        expected_digest = expected.get(path.name.lower())
        if expected_digest is None or digest != expected_digest:
            raise ValueError("SOURCE_ARCHIVE_HASH_MISMATCH")
        result[path.name] = digest
    if len(paths) != 3 or len(result) != 3:
        raise ValueError("SOURCE_ARCHIVE_SET_MISMATCH")
    return result


def _expected_part_entries(index: dict) -> set[str]:
    names = {"index.json"}
    for summary in index["parts"]:
        prefix = f"listening/{summary['id']}/"
        names.add(prefix + "part.json")
        names.add(prefix + "audio.mp3")
        names.update(prefix + image for image in summary["imageHashes"])
    return names


def _load_base_packs(packs_dir: Path) -> dict[int, dict]:
    expected_files = {f"cambridge-{book}.eelpack" for book in BOOKS}
    actual_files = {path.name for path in packs_dir.glob("cambridge-*.eelpack")}
    if actual_files != expected_files:
        raise ValueError("BASE_PACK_SET_MISMATCH")

    result = {}
    total_parts = 0
    for book in BOOKS:
        path = packs_dir / f"cambridge-{book}.eelpack"
        with zipfile.ZipFile(path) as archive:
            names = [entry.filename for entry in archive.infolist()]
            if len(names) != len(set(names)) or set(names) != _expected_part_entries(
                    json.loads(archive.read("index.json"))):
                raise ValueError("BASE_PACK_ENTRY_SET_MISMATCH")
            index_bytes = archive.read("index.json")
            index = json.loads(index_bytes.decode("utf-8"))
            if index.get("schemaVersion") != 1 or index.get("book") != book:
                raise ValueError("BASE_PACK_INDEX_MISMATCH")
            summaries = {item["id"]: item for item in index["parts"]}
            if len(summaries) != 16 or len(index["parts"]) != 16:
                raise ValueError("BASE_BOOK_PART_COUNT_MISMATCH")
            expected_parts = {f"cambridge-{book}-test-{test}-part-{part}"
                              for test in range(1, 5) for part in range(1, 5)}
            if set(summaries) != expected_parts:
                raise ValueError("BASE_BOOK_PART_SET_MISMATCH")
            part_bytes = {}
            parts = {}
            for part_id, summary in summaries.items():
                if summary.get("layoutSha256") is not None:
                    raise ValueError("BASE_PACK_ALREADY_HAS_LAYOUTS")
                raw = archive.read(f"listening/{part_id}/part.json")
                if sha256(raw) != summary["partSha256"]:
                    raise ValueError("BASE_PART_HASH_MISMATCH")
                part = json.loads(raw.decode("utf-8"))
                if part.get("id") != part_id or part.get("book") != book:
                    raise ValueError("BASE_PART_ID_MISMATCH")
                part_bytes[part_id] = raw
                parts[part_id] = part
            result[book] = {
                "path": path,
                "index": index,
                "indexBytes": index_bytes,
                "summaries": summaries,
                "partBytes": part_bytes,
                "parts": parts,
                "entryNames": set(names),
            }
            total_parts += len(summaries)
    if total_parts != 272:
        raise ValueError("BASE_LIBRARY_PART_COUNT_MISMATCH")
    return result


def _walk_blocks(blocks):
    for block in blocks:
        yield block
        yield from _walk_blocks(block.get("children", []))


def _group_order(group: dict) -> int:
    numbers = [block["questionNumber"] for block in _walk_blocks(group["blocks"])
               if block.get("type") == "blank"]
    return min(numbers) if numbers else 10_000


def _skip_record(row, question_ids, reason_code, **extra):
    first_last = _parse_range(row)
    return {
        "book": int(row["book"]),
        "partId": row["partId"],
        "questionRange": list(first_last) if first_last else row.get("questionRange"),
        "questionIds": question_ids,
        "questionCount": int(row["questionCount"]),
        "classification": row["classification"],
        "sourceLayoutKind": row["sourceLayoutKind"],
        "status": "skipped",
        "reasonCode": reason_code,
        **extra,
    }


def _candidate_claims(candidates: list[dict[str, str]]) -> set[int]:
    claims: dict[tuple[str, int], list[int]] = defaultdict(list)
    for row_index, row in enumerate(candidates):
        selected = _parse_range(row)
        if selected is None:
            continue
        for number in range(selected[0], selected[1] + 1):
            claims[(row["partId"], number)].append(row_index)
    return {row_index for group in claims.values() if len(group) > 1 for row_index in group}


def _convert_candidates(candidates, books, source_records):
    layouts_by_part: dict[str, list[dict]] = defaultdict(list)
    candidate_records = []
    source_html_by_part: dict[str, bytes] = {}
    source_problem_by_part: dict[str, str] = {}
    overlapping = _candidate_claims(candidates)

    for row_index, row in enumerate(candidates):
        part_id = row["partId"]
        book = int(row["book"])
        question_range = _parse_range(row)
        base = books.get(book)
        part = base["parts"].get(part_id) if base else None
        if part is None:
            candidate_records.append(_skip_record(row, [], "PART_NOT_IN_BASE_PACK"))
            continue

        if question_range is None:
            candidate_records.append(_skip_record(row, [], "INVALID_AUDIT_QUESTION_RANGE"))
            continue
        first, last = question_range
        questions = sorted(
            (item for item in part["questions"] if first <= item["number"] <= last),
            key=lambda item: item["number"])
        question_ids = [item["id"] for item in questions]
        expected_numbers = list(range(first, last + 1))
        if ([item["number"] for item in questions] != expected_numbers or
                len(questions) != int(row["questionCount"])):
            candidate_records.append(_skip_record(row, question_ids, "PART_QUESTION_RANGE_MISMATCH"))
            continue
        if row_index in overlapping:
            candidate_records.append(_skip_record(row, question_ids, "AUDIT_GROUP_QUESTION_OVERLAP"))
            continue
        if row["sourceHasExplicitBlankMarkers"].upper() != "TRUE":
            candidate_records.append(_skip_record(row, question_ids, "SOURCE_HAS_NO_EXPLICIT_BLANK_MARKERS"))
            continue
        if row["imageDependent"].upper() != "FALSE":
            candidate_records.append(_skip_record(row, question_ids, "IMAGE_DEPENDENT_GROUP_OUT_OF_SCOPE"))
            continue
        expected_layout_kind = SOURCE_KIND_TO_LAYOUT_KIND.get(row["sourceLayoutKind"])
        if expected_layout_kind is None:
            candidate_records.append(_skip_record(row, question_ids, "UNSUPPORTED_AUDIT_LAYOUT_KIND"))
            continue

        if part_id not in source_html_by_part and part_id not in source_problem_by_part:
            copies = source_records.get(part_id, [])
            if not copies:
                source_problem_by_part[part_id] = "SOURCE_PART_NOT_FOUND"
            else:
                try:
                    html_copies = [(path, archive.read(prefix + "content.html"))
                                   for path, archive, prefix in copies]
                    digests = {sha256(content) for _, content in html_copies}
                    if len(digests) != 1:
                        source_problem_by_part[part_id] = "DUPLICATE_SOURCE_HTML_MISMATCH"
                    else:
                        source_html_by_part[part_id] = html_copies[0][1]
                except (KeyError, OSError, zipfile.BadZipFile):
                    source_problem_by_part[part_id] = "SOURCE_HTML_MISSING_OR_DAMAGED"
        if part_id in source_problem_by_part:
            candidate_records.append(_skip_record(row, question_ids, source_problem_by_part[part_id]))
            continue

        part_bytes = base["partBytes"][part_id]
        source_html = source_html_by_part[part_id]
        try:
            document, statuses = generate_layout(
                part_bytes, source_html, question_range=question_range)
        except LayoutGenerationIssue as issue:
            candidate_records.append(_skip_record(row, question_ids, issue.reason_code,
                                                  generatorStatus=issue.status))
            continue
        except Exception as error:
            candidate_records.append(_skip_record(
                row, question_ids, "GENERATOR_EXCEPTION",
                exceptionType=type(error).__name__))
            continue

        exact_groups = [group for group in document["groups"]
                        if group.get("questionIds") == question_ids]
        if len(exact_groups) != 1:
            candidate_records.append(_skip_record(row, question_ids, "NO_EXACT_SOURCE_LAYOUT_GROUP",
                                                  observedGroupCount=len(exact_groups)))
            continue
        group = exact_groups[0]
        if group.get("layoutKind") != expected_layout_kind:
            candidate_records.append(_skip_record(row, question_ids, "LAYOUT_KIND_MISMATCH",
                                                  generatedLayoutKind=group.get("layoutKind")))
            continue
        if not any(status.get("status") == "converted" and
                   status.get("groupId") == group.get("groupId") and
                   status.get("sourceProjectionMatches") is True
                   for status in statuses):
            candidate_records.append(_skip_record(row, question_ids, "SOURCE_PROJECTION_NOT_VERIFIED"))
            continue

        blank_counts = Counter(
            block["questionId"] for block in _walk_blocks(group["blocks"])
            if block.get("type") == "blank")
        expected_blank_counts = {
            question["id"]: (2 if question.get("answerSeparator") else 1)
            for question in questions
        }
        if blank_counts != Counter(expected_blank_counts):
            candidate_records.append(_skip_record(row, question_ids, "QUESTION_SLOT_COUNT_MISMATCH"))
            continue

        document["groups"] = [group]
        try:
            validate_layout(document, part, part_json_bytes=part_bytes,
                            source_html_bytes=source_html)
        except LayoutValidationError as issue:
            candidate_records.append(_skip_record(row, question_ids, issue.reason_code))
            continue

        layouts_by_part[part_id].append(group)
        candidate_records.append({
            "book": book,
            "partId": part_id,
            "questionRange": [first, last],
            "questionIds": question_ids,
            "questionCount": len(question_ids),
            "inputSlotCount": sum(blank_counts.values()),
            "classification": row["classification"],
            "sourceLayoutKind": row["sourceLayoutKind"],
            "layoutKind": group["layoutKind"],
            "groupId": group["groupId"],
            "sourceProjectionMatches": True,
            "status": "converted",
        })

    # Validate all selected groups together, including Part-scoped uniqueness rules.
    failed_parts = set()
    for part_id, groups in layouts_by_part.items():
        book = int(part_id.split("-")[1])
        part_bytes = books[book]["partBytes"][part_id]
        document = {
            "schemaVersion": 1,
            "partId": part_id,
            "basePartSha256": sha256(part_bytes),
            "groups": sorted(groups, key=_group_order),
        }
        try:
            validate_layout(document, books[book]["parts"][part_id],
                            part_json_bytes=part_bytes,
                            source_html_bytes=source_html_by_part[part_id])
        except LayoutValidationError as issue:
            failed_parts.add(part_id)
            for record in candidate_records:
                if record["partId"] == part_id and record["status"] == "converted":
                    record["status"] = "skipped"
                    record["reasonCode"] = "COMBINED_PART_LAYOUT_INVALID"
                    record["validationReasonCode"] = issue.reason_code
                    record.pop("groupId", None)
                    record.pop("layoutKind", None)
                    record.pop("sourceProjectionMatches", None)
                    record.pop("inputSlotCount", None)

    for part_id in failed_parts:
        layouts_by_part.pop(part_id, None)
    return layouts_by_part, candidate_records, source_html_by_part


def patched_index(old_index: dict, layout_hashes: dict[str, str]) -> dict:
    """Return index v2 while preserving all prior summary fields."""
    if old_index.get("schemaVersion") != 1:
        raise ValueError("BASE_INDEX_MUST_BE_V1")
    updated = copy.deepcopy(old_index)
    updated["schemaVersion"] = 2
    for summary in updated["parts"]:
        layout_hash = layout_hashes.get(summary["id"])
        if layout_hash is None:
            summary.pop("layoutSha256", None)
        else:
            summary["layoutSha256"] = layout_hash
    return updated


def build_book_pack(source_path: Path, output_path: Path,
                    layout_bytes_by_part: dict[str, bytes]) -> dict:
    with zipfile.ZipFile(source_path) as source:
        old_index = json.loads(source.read("index.json").decode("utf-8"))
        old_names = [entry.filename for entry in source.infolist()]
        if len(old_names) != len(set(old_names)):
            raise ValueError("BASE_PACK_DUPLICATE_ENTRY")
        if set(old_names) != _expected_part_entries(old_index):
            raise ValueError("BASE_PACK_ENTRY_SET_MISMATCH")
        part_ids = {summary["id"] for summary in old_index["parts"]}
        if not set(layout_bytes_by_part).issubset(part_ids):
            raise ValueError("LAYOUT_PART_NOT_IN_BOOK")
        layout_hashes = {part_id: sha256(data)
                         for part_id, data in layout_bytes_by_part.items()}
        new_index = patched_index(old_index, layout_hashes)
        index_bytes = _json_bytes(new_index)

        output_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output_path, "w", allowZip64=True,
                             compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
            old_index_info = source.getinfo("index.json")
            index_info = zipfile.ZipInfo("index.json", date_time=old_index_info.date_time)
            index_info.compress_type = zipfile.ZIP_DEFLATED
            index_info.create_system = old_index_info.create_system
            index_info.external_attr = old_index_info.external_attr
            output.writestr(index_info, index_bytes)

            for info in source.infolist():
                if info.filename == "index.json":
                    continue
                with source.open(info, "r") as src, output.open(info, "w", force_zip64=True) as dst:
                    shutil.copyfileobj(src, dst, length=CHUNK_SIZE)

            for part_id, data in sorted(layout_bytes_by_part.items()):
                path = f"listening/{part_id}/layout.json"
                info = zipfile.ZipInfo(path, date_time=(2020, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 0
                output.writestr(info, data)
    return new_index


def _streams_equal_and_hash(source, output) -> tuple[bool, int, str]:
    digest = hashlib.sha256()
    size = 0
    while True:
        left = source.read(CHUNK_SIZE)
        right = output.read(CHUNK_SIZE)
        if left != right:
            return False, size, digest.hexdigest()
        if not left:
            return True, size, digest.hexdigest()
        size += len(left)
        digest.update(left)


def verify_book_pack(source_path: Path, output_path: Path, expected_layouts: dict[str, bytes],
                     source_html_by_part: dict[str, bytes], part_models: dict[str, dict],
                     part_bytes: dict[str, bytes]) -> dict:
    with zipfile.ZipFile(source_path) as source, zipfile.ZipFile(output_path) as output:
        original_names = [entry.filename for entry in source.infolist()]
        new_names = [entry.filename for entry in output.infolist()]
        if len(new_names) != len(set(new_names)):
            raise ValueError("OUTPUT_PACK_DUPLICATE_ENTRY")
        expected_names = set(original_names) | {
            f"listening/{part_id}/layout.json" for part_id in expected_layouts
        }
        if set(new_names) != expected_names:
            raise ValueError("OUTPUT_PACK_ENTRY_SET_MISMATCH")

        old_index = json.loads(source.read("index.json").decode("utf-8"))
        new_index = json.loads(output.read("index.json").decode("utf-8"))
        if new_index.get("schemaVersion") != 2 or new_index.get("book") != old_index.get("book"):
            raise ValueError("OUTPUT_INDEX_VERSION_OR_BOOK_MISMATCH")
        old_summaries = {item["id"]: item for item in old_index["parts"]}
        new_summaries = {item["id"]: item for item in new_index["parts"]}
        if set(new_summaries) != set(old_summaries):
            raise ValueError("OUTPUT_PART_SET_CHANGED")
        for part_id, old_summary in old_summaries.items():
            stripped = copy.deepcopy(new_summaries[part_id])
            new_layout_hash = stripped.pop("layoutSha256", None)
            if stripped != old_summary:
                raise ValueError("OUTPUT_NON_LAYOUT_SUMMARY_CHANGED")
            expected_bytes = expected_layouts.get(part_id)
            if expected_bytes is None:
                if new_layout_hash is not None:
                    raise ValueError("UNDECLARED_LAYOUT_HASH")
            elif new_layout_hash != sha256(expected_bytes):
                raise ValueError("OUTPUT_LAYOUT_HASH_MISMATCH")

        preserved_resources = 0
        for name in original_names:
            if name == "index.json":
                continue
            with source.open(name, "r") as src, output.open(name, "r") as dst:
                equal, _, digest = _streams_equal_and_hash(src, dst)
            if not equal:
                raise ValueError("ORIGINAL_RESOURCE_BYTES_CHANGED")
            preserved_resources += 1
            match = re.fullmatch(r"listening/([^/]+)/(part\.json|audio\.mp3|[^/]+)", name)
            if not match:
                raise ValueError("UNEXPECTED_BASE_ENTRY_PATH")
            part_id, file_name = match.groups()
            summary = new_summaries[part_id]
            expected_hash = None
            if file_name == "part.json":
                expected_hash = summary["partSha256"]
            elif file_name == "audio.mp3":
                expected_hash = summary["audioSha256"]
            else:
                expected_hash = summary["imageHashes"].get(file_name)
            if expected_hash is None or digest != expected_hash:
                raise ValueError("OUTPUT_RESOURCE_HASH_MISMATCH")

        for part_id, raw_layout in expected_layouts.items():
            raw_part = part_bytes[part_id]
            actual_layout = output.read(f"listening/{part_id}/layout.json")
            if actual_layout != raw_layout:
                raise ValueError("OUTPUT_LAYOUT_BYTES_CHANGED")
            layout = json.loads(actual_layout.decode("utf-8"))
            if sha256(raw_layout) != new_summaries[part_id]["layoutSha256"]:
                raise ValueError("OUTPUT_LAYOUT_BYTES_MISMATCH")
            validate_layout(layout, part_models[part_id], part_json_bytes=raw_part,
                            source_html_bytes=source_html_by_part[part_id])
        # Reading every entry above verifies ZIP CRCs; also read all sidecars here.
        return {
            "book": new_index["book"],
            "parts": len(new_index["parts"]),
            "layoutParts": len(expected_layouts),
            "layoutGroups": sum(len(json.loads(data.decode("utf-8"))["groups"])
                                for data in expected_layouts.values()),
            "entryCount": len(new_names),
            "preservedOriginalResources": preserved_resources,
            "bytes": output_path.stat().st_size,
            "sha256": file_sha256(output_path),
        }


def build_library(source_paths: list[Path], packs_dir: Path, inventory_path: Path,
                  source_audit_path: Path, output_dir: Path) -> dict:
    private_root = (Path.cwd() / "private-data").resolve()
    output_dir = output_dir.resolve()
    try:
        output_dir.relative_to(private_root)
    except ValueError as error:
        raise ValueError("OUTPUT_MUST_REMAIN_UNDER_PRIVATE_DATA") from error
    if output_dir.exists():
        raise FileExistsError("OUTPUT_DIRECTORY_ALREADY_EXISTS")
    candidates = _read_candidates(inventory_path)
    source_hashes = _source_hashes_match_audit(source_paths, source_audit_path)
    books = _load_base_packs(packs_dir)

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}-stage-", dir=output_dir.parent))
    try:
        with ExitStack() as stack:
            _, source_records = _index_sources(source_paths, stack)
            layouts_by_part, candidate_records, source_html_by_part = _convert_candidates(
                candidates, books, source_records)
            layout_bytes_by_part = {
                part_id: encode_layout({
                    "schemaVersion": 1,
                    "partId": part_id,
                    "basePartSha256": sha256(books[int(part_id.split("-")[1])]["partBytes"][part_id]),
                    "groups": sorted(groups, key=_group_order),
                })
                for part_id, groups in layouts_by_part.items()
            }

            packages = []
            for book in BOOKS:
                base = books[book]
                layouts = {part_id: data for part_id, data in layout_bytes_by_part.items()
                           if int(part_id.split("-")[1]) == book}
                output_path = staging / f"cambridge-{book}.eelpack"
                build_book_pack(base["path"], output_path, layouts)
                checked = verify_book_pack(
                    base["path"], output_path, layouts, source_html_by_part,
                    base["parts"], base["partBytes"])
                checked["fileName"] = output_path.name
                packages.append(checked)

        converted = [record for record in candidate_records if record["status"] == "converted"]
        skipped = [record for record in candidate_records if record["status"] == "skipped"]
        by_book = []
        for book in BOOKS:
            rows = [record for record in candidate_records if record["book"] == book]
            successful = [record for record in rows if record["status"] == "converted"]
            failed = [record for record in rows if record["status"] == "skipped"]
            by_book.append({
                "book": book,
                "candidateGroups": len(rows),
                "convertedGroups": len(successful),
                "convertedQuestions": sum(record["questionCount"] for record in successful),
                "inputSlots": sum(record.get("inputSlotCount", 0) for record in successful),
                "skippedGroups": len(failed),
                "skippedQuestions": sum(record["questionCount"] for record in failed),
            })
        report = {
            "schemaVersion": 1,
            "scope": {
                "books": list(BOOKS),
                "candidateGroups": len(candidates),
                "candidateQuestions": sum(int(row["questionCount"]) for row in candidates),
                "classifications": {"A": sum(row["classification"] == "A" for row in candidates),
                                    "B": sum(row["classification"] == "B" for row in candidates)},
                "cClassProcessed": False,
            },
            "conversion": {
                "convertedGroups": len(converted),
                "convertedQuestions": sum(record["questionCount"] for record in converted),
                "inputSlots": sum(record.get("inputSlotCount", 0) for record in converted),
                "skippedGroups": len(skipped),
                "skippedQuestions": sum(record["questionCount"] for record in skipped),
                "byBook": by_book,
                "results": candidate_records,
            },
            "sources": [{"fileName": name, "sha256": digest}
                        for name, digest in sorted(source_hashes.items())],
            "packages": packages,
            "policy": "private local output; original part.json, answer, audio and image bytes preserved",
        }
        (staging / "verification-report.json").write_bytes(_json_bytes(report))
        staging.rename(output_dir)
        return report
    except Exception as error:
        (staging / "build-failure.json").write_bytes(_json_bytes({
            "errorType": type(error).__name__,
            "reasonCode": str(error) if re.fullmatch(r"[A-Z0-9_]+", str(error)) else "BUILD_FAILED",
        }))
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", nargs=3, required=True,
                        help="Original private Cambridge 5-13, 13-17, and 18-21 ZIPs")
    parser.add_argument("--packs-dir", default="private-data/library/pending-six/updated-library")
    parser.add_argument("--inventory", default="private-data/audits/gap-layout-inventory.csv")
    parser.add_argument("--source-audit", default="private-data/library/recheck-audit.json")
    parser.add_argument("--output", default="private-data/layout-batch-ab-v2")
    args = parser.parse_args()
    report = build_library(
        [Path(value) for value in args.sources],
        Path(args.packs_dir),
        Path(args.inventory),
        Path(args.source_audit),
        Path(args.output),
    )
    print(json.dumps({
        "output": str(Path(args.output).resolve()),
        "candidateGroups": report["scope"]["candidateGroups"],
        "candidateQuestions": report["scope"]["candidateQuestions"],
        "convertedGroups": report["conversion"]["convertedGroups"],
        "convertedQuestions": report["conversion"]["convertedQuestions"],
        "skippedGroups": report["conversion"]["skippedGroups"],
        "skippedQuestions": report["conversion"]["skippedQuestions"],
        "packages": len(report["packages"]),
    }, ensure_ascii=True))


if __name__ == "__main__":
    main()
