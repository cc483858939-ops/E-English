"""Synthetic tests for the v3 extensions; no real Cambridge data is used."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import unittest

from layout_v3_validator import (
    LayoutV3ValidationError,
    _grader_within_limit,
    compose_fill_answer,
    validate_layout_v3,
)


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


class LayoutV3ValidatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.part_bytes = (FIXTURE / "part.json").read_bytes()
        cls.part = json.loads(cls.part_bytes.decode("utf-8"))
        cls.layout = json.loads((FIXTURE / "layout.json").read_text(encoding="utf-8"))
        cls.layout["schemaVersion"] = 3
        cls.context = json.loads((FIXTURE / "context.json").read_text(encoding="utf-8"))
        cls.source_bytes = {
            "content.html": (FIXTURE / "content.html").read_bytes(),
            "map.png": (FIXTURE / "map.png").read_bytes(),
        }
        for group in cls.layout["groups"]:
            for block in walk(group["blocks"]):
                if block["type"] == "image":
                    block["printedConnectors"] = []
            for response in group["responses"]:
                if response["kind"] == "fill" and len(response["slotIds"]) == 2:
                    qid = response["questionIds"][0]
                    question = next(q for q in cls.part["questions"] if q["id"] == qid)
                    response["joiner"] = question["answerSeparator"]

    def validate(self, layout=None, *, part=None, reviews=None, archive_hashes=None):
        document = self.layout if layout is None else layout
        part_bytes = self.part_bytes if part is None else part
        raw_layout = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return validate_layout_v3(
            raw_layout,
            part_bytes,
            source_bytes=self.source_bytes,
            image_metadata=self.context["images"],
            review_records=self.context["reviews"] if reviews is None else reviews,
            source_archive_hashes={} if archive_hashes is None else archive_hashes,
        )

    @staticmethod
    def encoded_part(part):
        return (json.dumps(part, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    @staticmethod
    def group(layout, group_id):
        return next(group for group in layout["groups"] if group["groupId"] == group_id)

    @classmethod
    def block(cls, layout, group_id, block_id):
        group = cls.group(layout, group_id)
        return next(block for block in walk(group["blocks"]) if block["id"] == block_id)

    def assert_reason(self, expected, layout, *, part=None, reviews=None, archive_hashes=None):
        with self.assertRaises(LayoutV3ValidationError) as raised:
            self.validate(layout, part=part, reviews=reviews, archive_hashes=archive_hashes)
        self.assertEqual(expected, raised.exception.reason_code)
        self.assertEqual(expected, str(raised.exception))

    def make_reviewed_image_pair(self):
        layout = copy.deepcopy(self.layout)
        part = copy.deepcopy(self.part)
        group = self.group(layout, "example-image-fill")
        response = group["responses"][0]
        qid = response["questionIds"][0]
        question = next(q for q in part["questions"] if q["id"] == qid)
        question["correctAnswer"] = "alpha and beta"
        question["acceptedAnswers"] = ["alpha and beta"]
        question["wordLimit"] = {"maxWords": 3, "maxNumbers": 0, "numberOnly": False, "wordsOrNumber": False}
        question.pop("answerSeparator", None)

        first_slot = response["slotIds"][0]
        second_slot = "v3-pair-slot-1"
        group["slots"].append({"slotId": second_slot, "questionId": qid, "slotIndex": 1, "kind": "fill"})
        response["slotIds"] = [first_slot, second_slot]
        response["joiner"] = "and"

        image = next(block for block in walk(group["blocks"]) if block["type"] == "image")
        image_sha = self.context["images"][image["asset"]]["sha256"]
        first_rect = {"x": 10, "y": 15, "width": 22, "height": 14}
        connector_rect = {"x": 38, "y": 15, "width": 15, "height": 14}
        second_rect = {"x": 57, "y": 15, "width": 24, "height": 14}
        image["overlays"] = [{
            "slotId": first_slot,
            "rectPx": first_rect,
            "evidence": {"kind": "reviewedMeasurement", "reviewId": "v3-review-pair-slot0", "imageSha256": image_sha},
        }, {
            "slotId": second_slot,
            "rectPx": second_rect,
            "evidence": {"kind": "reviewedMeasurement", "reviewId": "v3-review-pair-slot1", "imageSha256": image_sha},
        }]
        image["printedConnectors"] = [{
            "questionId": qid,
            "text": "and",
            "rectPx": connector_rect,
            "evidence": {"kind": "reviewedMeasurement", "reviewId": "v3-review-pair-connector", "imageSha256": image_sha},
        }]

        source_sha = hashlib.sha256(self.source_bytes[image["asset"]]).hexdigest()
        archive_path = "synthetic/original.zip"
        archive_sha = hashlib.sha256(b"synthetic archive evidence").hexdigest()
        base = copy.deepcopy(next(record for record in self.context["reviews"] if record["slotId"] == first_slot))
        base.update({
            "reviewId": "v3-review-pair-slot0",
            "partId": part["id"],
            "groupId": group["groupId"],
            "slotId": first_slot,
            "imageSha256": image_sha,
            "rectPx": first_rect,
            "sourceResource": image["asset"],
            "sourceSha256": source_sha,
            "sourceLocator": "Synthetic map, first measured answer rectangle.",
            "explanation": "Measured from the synthetic image at native resolution.",
            "sourceArchive": archive_path,
            "sourceArchiveSha256": archive_sha,
        })
        second = copy.deepcopy(base)
        second.update({
            "reviewId": "v3-review-pair-slot1",
            "slotId": second_slot,
            "rectPx": second_rect,
            "sourceLocator": "Synthetic map, second measured answer rectangle.",
        })
        connector = {
            "reviewId": "v3-review-pair-connector",
            "partId": part["id"],
            "groupId": group["groupId"],
            "questionId": qid,
            "text": "and",
            "imageSha256": image_sha,
            "rectPx": connector_rect,
            "method": "manualConnectorReview",
            "sourceResource": image["asset"],
            "sourceSha256": source_sha,
            "sourceLocator": "Synthetic map, printed connector between the two measured fields.",
            "explanation": "Measured the printed word at the synthetic image's native resolution.",
            "sourceArchive": archive_path,
            "sourceArchiveSha256": archive_sha,
        }
        part_bytes = self.encoded_part(part)
        layout["basePartSha256"] = hashlib.sha256(part_bytes).hexdigest()
        reviews = [record for record in copy.deepcopy(self.context["reviews"]) if record["slotId"] != first_slot]
        reviews.extend([base, second, connector])
        return layout, part_bytes, reviews, {archive_path: archive_sha}, qid

    def test_inherited_single_and_text_double_fill(self):
        validated = self.validate()
        self.assertEqual(3, validated["schemaVersion"])
        double = self.group(validated, "example-double")["responses"][0]
        self.assertEqual("and", double["joiner"])
        self.assertEqual("legacyPerQuestion", double["scoring"])
        self.assertEqual("sun and moon", compose_fill_answer([" sun ", " moon "], "and"))
        self.assertEqual("blue", compose_fill_answer([" blue "]))
        self.assertIsNone(compose_fill_answer(["", "moon"], "and"))
        self.assertIsNone(compose_fill_answer(["sun", "   "], "and"))

    def test_reviewed_image_double_fill_without_legacy_separator(self):
        layout, part_bytes, reviews, archive_hashes, qid = self.make_reviewed_image_pair()
        validated = self.validate(layout, part=part_bytes, reviews=reviews, archive_hashes=archive_hashes)
        group = next(group for group in validated["groups"] if qid in group["questionIds"])
        response = next(rule for rule in group["responses"] if qid in rule["questionIds"])
        question = next(q for q in json.loads(part_bytes)["questions"] if q["id"] == qid)
        self.assertNotIn("answerSeparator", question)
        self.assertEqual([qid], response["questionIds"])
        self.assertEqual(2, len(response["slotIds"]))
        self.assertEqual("legacyPerQuestion", response["scoring"])
        self.assertTrue(_grader_within_limit(question, "alpha and beta"))
        self.assertEqual("alpha and beta", compose_fill_answer([" alpha ", " beta "], response["joiner"]))

    def test_connector_joiner_mismatch_is_rejected(self):
        layout, part_bytes, reviews, archive_hashes, _ = self.make_reviewed_image_pair()
        image = next(block for group in layout["groups"] for block in walk(group["blocks"])
                     if block["type"] == "image" and block["printedConnectors"])
        image["printedConnectors"][0]["text"] = "or"
        self.assert_reason("ANSWER_CONNECTOR_MISMATCH", layout, part=part_bytes, reviews=reviews,
                           archive_hashes=archive_hashes)

    def test_missing_measurement_or_review_record_is_rejected(self):
        layout, part_bytes, reviews, archive_hashes, _ = self.make_reviewed_image_pair()
        image = next(block for group in layout["groups"] for block in walk(group["blocks"])
                     if block["type"] == "image" and block["printedConnectors"])
        del image["printedConnectors"][0]["evidence"]
        self.assert_reason("IMAGE_EVIDENCE_MISSING", layout, part=part_bytes, reviews=reviews,
                           archive_hashes=archive_hashes)

        layout, part_bytes, reviews, archive_hashes, _ = self.make_reviewed_image_pair()
        reviews = [record for record in reviews if record["method"] != "manualConnectorReview"]
        self.assert_reason("REVIEW_RECORD_MISSING", layout, part=part_bytes, reviews=reviews,
                           archive_hashes=archive_hashes)

    def test_missing_image_input_overlay_is_rejected(self):
        layout, part_bytes, reviews, archive_hashes, _ = self.make_reviewed_image_pair()
        image = next(block for group in layout["groups"] for block in walk(group["blocks"])
                     if block["type"] == "image" and block["printedConnectors"])
        image["overlays"] = [overlay for overlay in image["overlays"]
                             if overlay["slotId"] != "v3-pair-slot-1"]
        self.assert_reason("ANSWER_CONNECTOR_MISMATCH", layout, part=part_bytes, reviews=reviews,
                           archive_hashes=archive_hashes)

    def test_legacy_separator_and_response_joiner_must_match(self):
        layout = copy.deepcopy(self.layout)
        self.group(layout, "example-double")["responses"][0]["joiner"] = "or"
        self.assert_reason("ANSWER_CONNECTOR_MISMATCH", layout)

    def test_no_separator_joiner_must_preserve_accepted_answer_and_limit(self):
        layout, part_bytes, reviews, archive_hashes, qid = self.make_reviewed_image_pair()
        part = json.loads(part_bytes)
        question = next(q for q in part["questions"] if q["id"] == qid)
        question["acceptedAnswers"] = ["alpha or gamma"]
        part_bytes = self.encoded_part(part)
        layout["basePartSha256"] = hashlib.sha256(part_bytes).hexdigest()
        self.assert_reason("ANSWER_JOINER_INCOMPATIBLE", layout, part=part_bytes, reviews=reviews,
                           archive_hashes=archive_hashes)

    def test_schema_is_v3_closed_and_references_resolve(self):
        schema = json.loads((ROOT / "docs" / "schemas" / "layout-v3.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(3, schema["properties"]["schemaVersion"]["const"])
        image = schema["$defs"]["imageBlock"]
        self.assertIn("printedConnectors", image["required"])
        self.assertIn("joiner", schema["$defs"]["fillResponse"]["properties"])
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
