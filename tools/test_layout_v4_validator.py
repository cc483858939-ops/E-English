"""Synthetic v4 overlay-evidence tests; no Cambridge data is used."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import unittest

from layout_v4_validator import LayoutV4ValidationError, validate_layout_v4


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "docs" / "examples" / "layout-v2"


def walk(blocks):
    for block in blocks:
        yield block
        if block["type"] in {"paragraph", "listItem", "formRow"}:
            yield from walk(block["children"])
        elif block["type"] == "table":
            for row in block["rows"]:
                for cell in row["cells"]:
                    yield from walk(cell["children"])
        elif block["type"] == "flow":
            for node in block["nodes"]:
                yield from walk(node["children"])


class LayoutV4ValidatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.part_bytes = (FIXTURE / "part.json").read_bytes()
        cls.part = json.loads(cls.part_bytes.decode("utf-8"))
        cls.layout = json.loads((FIXTURE / "layout.json").read_text(encoding="utf-8"))
        cls.layout["schemaVersion"] = 4
        cls.context = json.loads((FIXTURE / "context.json").read_text(encoding="utf-8"))
        cls.source_bytes = {
            "content.html": (FIXTURE / "content.html").read_bytes(),
            "map.png": (FIXTURE / "map.png").read_bytes(),
        }
        for group in cls.layout["groups"]:
            for block in walk(group["blocks"]):
                if block["type"] == "image":
                    block.setdefault("printedConnectors", [])
            for response in group["responses"]:
                if response["kind"] == "fill" and len(response["slotIds"]) == 2:
                    qid = response["questionIds"][0]
                    question = next(q for q in cls.part["questions"] if q["id"] == qid)
                    response["joiner"] = question["answerSeparator"]
        cls.part_bytes = cls.encode_part(cls.part)
        cls.layout["basePartSha256"] = hashlib.sha256(cls.part_bytes).hexdigest()

    def validate(self, layout, part_bytes=None, *, reviews=None, measurements=None, archive_hashes=None):
        raw_layout = json.dumps(layout, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return validate_layout_v4(
            raw_layout,
            self.part_bytes if part_bytes is None else part_bytes,
            source_bytes=self.source_bytes,
            image_metadata=self.context["images"],
            review_records=self.context["reviews"] if reviews is None else reviews,
            model_measurement_records=[] if measurements is None else measurements,
            source_archive_hashes={} if archive_hashes is None else archive_hashes,
        )

    @staticmethod
    def encode_part(part):
        return (json.dumps(part, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    @staticmethod
    def group(layout, group_id):
        return next(group for group in layout["groups"] if group["groupId"] == group_id)

    def model_record(self, part, group, slot, rect, measurement_id):
        image = next(block for block in walk(group["blocks"]) if block["type"] == "image")
        metadata = self.context["images"][image["asset"]]
        image_sha = metadata["sha256"]
        archive = "synthetic/model-measurements.zip"
        archive_sha = hashlib.sha256(b"synthetic model-measurement source archive").hexdigest()
        return {
            "measurementId": measurement_id,
            "method": "modelMeasurement",
            "partId": part["id"],
            "groupId": group["groupId"],
            "questionId": slot["questionId"],
            "slotId": slot["slotId"],
            "slotIndex": slot["slotIndex"],
            "imageAsset": image["asset"],
            "imageSha256": image_sha,
            "imageWidthPx": metadata["widthPx"],
            "imageHeightPx": metadata["heightPx"],
            "rectPx": rect,
            "sourceArchive": archive,
            "sourceArchiveSha256": archive_sha,
            "sourceEntry": image["asset"],
            "sourceEntrySha256": image_sha,
            "visualLocator": f"Synthetic printed answer field for {slot['questionId']}.",
            "measurementBasis": "Model measurement against the original synthetic image at native pixel resolution.",
        }

    def attach_model_overlay(self, layout, part, group_id, slot_id, rect, measurement_id):
        group = self.group(layout, group_id)
        slot = next(item for item in group["slots"] if item["slotId"] == slot_id)
        image = next(block for block in walk(group["blocks"]) if block["type"] == "image")
        image_sha = self.context["images"][image["asset"]]["sha256"]
        image["overlays"].append({
            "slotId": slot_id,
            "rectPx": rect,
            "evidence": {
                "kind": "modelMeasurement", "measurementId": measurement_id, "imageSha256": image_sha,
            },
        })
        record = self.model_record(part, group, slot, rect, measurement_id)
        archive_hashes = {record["sourceArchive"]: record["sourceArchiveSha256"]}
        return record, archive_hashes

    def test_inherited_v3_layout_and_human_evidence_still_validate(self):
        checked = self.validate(copy.deepcopy(self.layout))
        self.assertEqual(4, checked["schemaVersion"])

    def test_model_measurements_support_fill_and_single_choice(self):
        for group_id, slot_id, expected_kind in (
            ("example-image-fill", "s10-0", "fill"),
            ("example-single", "s1-0", "singleChoice"),
        ):
            with self.subTest(kind=expected_kind):
                layout = copy.deepcopy(self.layout)
                part = copy.deepcopy(self.part)
                group = self.group(layout, group_id)
                if expected_kind == "singleChoice":
                    group["blocks"] = [b for b in group["blocks"]
                                       if not (b["type"] == "choiceList" and slot_id in b["slotIds"])]
                image = next(b for b in walk(group["blocks"]) if b["type"] == "image")
                image["overlays"] = []
                rect = {"x": 30, "y": 40, "width": 25, "height": 12}
                record, hashes = self.attach_model_overlay(
                    layout, part, group_id, slot_id, rect,
                    "model-single-choice-slot" if expected_kind == "singleChoice" else "model-fill-slot"
                )
                part_bytes = self.encode_part(part)
                layout["basePartSha256"] = hashlib.sha256(part_bytes).hexdigest()
                self.validate(layout, part_bytes, measurements=[record], archive_hashes=hashes)

    def test_model_measurements_support_matching_overlays(self):
        layout = copy.deepcopy(self.layout)
        part = copy.deepcopy(self.part)
        group_id = "example-matching"
        group = self.group(layout, group_id)
        qids = set(group["questionIds"])
        for question in part["questions"]:
            if question["id"] in qids:
                question["images"] = ["map.png"]

        def remove_choice_lists(blocks):
            result = []
            for block in blocks:
                if block["type"] == "choiceList":
                    continue
                if block["type"] in {"paragraph", "listItem", "formRow"}:
                    block["children"] = remove_choice_lists(block["children"])
                result.append(block)
            return result

        group["blocks"] = remove_choice_lists(group["blocks"])
        image_sha = self.context["images"]["map.png"]["sha256"]
        image_block = {
            "id": "model-matching-image", "type": "image", "asset": "map.png",
            "widthPx": 100, "heightPx": 100, "overlays": [], "printedConnectors": [],
        }
        group["blocks"].append(image_block)
        records = []
        archive_hashes = {}
        for index, slot in enumerate(group["slots"]):
            rect = {"x": 10 + index * 30, "y": 40, "width": 20, "height": 12}
            measurement_id = f"model-matching-slot-{index}"
            image_block["overlays"].append({
                "slotId": slot["slotId"], "rectPx": rect,
                "evidence": {"kind": "modelMeasurement", "measurementId": measurement_id,
                             "imageSha256": image_sha},
            })
            record = self.model_record(part, group, slot, rect, measurement_id)
            records.append(record)
            archive_hashes[record["sourceArchive"]] = record["sourceArchiveSha256"]

        part_bytes = self.encode_part(part)
        layout["basePartSha256"] = hashlib.sha256(part_bytes).hexdigest()
        self.validate(layout, part_bytes, measurements=records, archive_hashes=archive_hashes)

    def test_missing_or_false_measurement_evidence_is_rejected(self):
        layout = copy.deepcopy(self.layout)
        part = copy.deepcopy(self.part)
        group_id, slot_id = "example-single", "s1-0"
        group = self.group(layout, group_id)
        group["blocks"] = [b for b in group["blocks"]
                           if not (b["type"] == "choiceList" and slot_id in b["slotIds"])]
        record, hashes = self.attach_model_overlay(
            layout, part, group_id, slot_id, {"x": 30, "y": 40, "width": 20, "height": 12},
            "model-single-slot",
        )
        with self.assertRaises(LayoutV4ValidationError) as missing:
            self.validate(layout, measurements=[], archive_hashes=hashes)
        self.assertEqual("MODEL_MEASUREMENT_MISSING", missing.exception.reason_code)

        record["sourceEntrySha256"] = "0" * 64
        with self.assertRaises(LayoutV4ValidationError) as bad_hash:
            self.validate(layout, measurements=[record], archive_hashes=hashes)
        self.assertEqual("MODEL_MEASUREMENT_INVALID", bad_hash.exception.reason_code)

    def test_schema_is_v4_and_overlay_evidence_is_closed(self):
        schema = json.loads((ROOT / "docs" / "schemas" / "layout-v4.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(4, schema["properties"]["schemaVersion"]["const"])
        self.assertIn("modelMeasurementEvidence", schema["$defs"])
        self.assertEqual(
            "#/$defs/overlayEvidence", schema["$defs"]["overlay"]["properties"]["evidence"]["$ref"]
        )
        self.assertEqual(
            "#/$defs/imageEvidence", schema["$defs"]["printedConnector"]["properties"]["evidence"]["$ref"]
        )
        references = []

        def collect(value):
            if isinstance(value, dict):
                if isinstance(value.get("$ref"), str) and value["$ref"].startswith("#/$defs/"):
                    references.append(value["$ref"].split("/", 2)[2])
                for child in value.values():
                    collect(child)
            elif isinstance(value, list):
                for child in value:
                    collect(child)

        collect(schema)
        self.assertTrue(references)
        self.assertTrue(all(name in schema["$defs"] for name in references))


if __name__ == "__main__":
    unittest.main()
