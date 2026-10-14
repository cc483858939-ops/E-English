"""Synthetic v2 acceptance and stable-error tests; never uses real question data."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import unittest

from layout_v2_validator import (
    LayoutV2ValidationError,
    _normalized_resource,
    _table_is_rectangular,
    _valid_review_record,
    validate_layout_v2,
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


class LayoutV2ValidatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.layout_bytes = (FIXTURE / "layout.json").read_bytes()
        cls.part_bytes = (FIXTURE / "part.json").read_bytes()
        cls.layout = json.loads(cls.layout_bytes.decode("utf-8"))
        cls.context = json.loads((FIXTURE / "context.json").read_text(encoding="utf-8"))
        cls.source_bytes = {
            "content.html": (FIXTURE / "content.html").read_bytes(),
            "map.png": (FIXTURE / "map.png").read_bytes(),
        }

    def validate(self, layout=None, *, part_bytes=None, source_bytes=None, image_metadata=None, review_records=None):
        raw = self.layout_bytes if layout is None else json.dumps(
            layout, ensure_ascii=False, separators=(",", ":")
        ).encode("utf-8")
        return validate_layout_v2(
            raw,
            self.part_bytes if part_bytes is None else part_bytes,
            source_bytes=self.source_bytes if source_bytes is None else source_bytes,
            image_metadata=self.context["images"] if image_metadata is None else image_metadata,
            review_records=self.context["reviews"] if review_records is None else review_records,
        )

    @staticmethod
    def serialized_part(part):
        return (json.dumps(part, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    def update_part_hash(self, layout, part):
        part_bytes = self.serialized_part(part)
        layout["basePartSha256"] = hashlib.sha256(part_bytes).hexdigest()
        return part_bytes

    def assert_reason(self, expected, layout):
        with self.assertRaises(LayoutV2ValidationError) as raised:
            self.validate(layout)
        self.assertEqual(expected, raised.exception.reason_code)
        self.assertEqual(expected, str(raised.exception))

    def assert_reason_with_part(self, expected, layout, part_bytes):
        raw = json.dumps(layout, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        with self.assertRaises(LayoutV2ValidationError) as raised:
            validate_layout_v2(
                raw,
                part_bytes,
                source_bytes=self.source_bytes,
                image_metadata=self.context["images"],
                review_records=self.context["reviews"],
            )
        self.assertEqual(expected, raised.exception.reason_code)
        self.assertEqual(expected, str(raised.exception))

    def group(self, group_id):
        return next(group for group in self.layout["groups"] if group["groupId"] == group_id)

    def block(self, group_id, block_id):
        return next(block for block in walk(self.group(group_id)["blocks"]) if block["id"] == block_id)

    def test_complete_synthetic_fixture_covers_all_eight_patterns(self):
        result = self.validate()
        self.assertEqual(8, len(result["groups"]))
        self.assertEqual(10, sum(len(group["questionIds"]) for group in result["groups"]))

    def test_failure_mutations_return_contract_reason_codes(self):
        cases = []

        layout = copy.deepcopy(self.layout)
        layout["groups"].pop()
        cases.append(("QUESTION_COVERAGE_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        duplicate = copy.deepcopy(next(group for group in layout["groups"] if group["groupId"] == "example-fill"))
        duplicate["groupId"] = "example-fill-copy"
        layout["groups"].append(duplicate)
        cases.append(("QUESTION_IN_MULTIPLE_GROUPS", layout))

        layout = copy.deepcopy(self.layout)
        self.group_from(layout, "example-fill")["responses"].clear()
        cases.append(("RESPONSE_COVERAGE_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        group = self.group_from(layout, "example-fill")
        group["slots"].append(copy.deepcopy(group["slots"][0]))
        cases.append(("DUPLICATE_SLOT", layout))

        layout = copy.deepcopy(self.layout)
        group = self.group_from(layout, "example-fill")
        paragraph = next(block for block in group["blocks"] if block["type"] == "paragraph")
        paragraph["children"] = [child for child in paragraph["children"] if child.get("id") != "place-6-0"]
        cases.append(("SLOT_PLACEMENT_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        self.group_from(layout, "example-single")["optionPools"][0]["optionIds"][0] = "Z"
        cases.append(("OPTION_POOL_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        self.block_from(layout, "example-single", "q1-image")["asset"] = "missing.png"
        cases.append(("UNKNOWN_IMAGE", layout))

        layout = copy.deepcopy(self.layout)
        self.block_from(layout, "example-single", "q1-image")["widthPx"] = 101
        cases.append(("IMAGE_DIMENSION_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        self.block_from(layout, "example-image-fill", "map-image")["overlays"][0]["rectPx"]["x"] = 90
        cases.append(("IMAGE_RECT_OUT_OF_BOUNDS", layout))

        layout = copy.deepcopy(self.layout)
        del self.block_from(layout, "example-image-fill", "map-image")["overlays"][0]["evidence"]
        cases.append(("IMAGE_EVIDENCE_MISSING", layout))

        layout = copy.deepcopy(self.layout)
        table = self.block_from(layout, "example-table", "table")
        table["rows"][1]["cells"] = [cell for cell in table["rows"][1]["cells"] if cell["id"] != "cell-answer"]
        cases.append(("TABLE_GRID_INVALID", layout))

        layout = copy.deepcopy(self.layout)
        self.block_from(layout, "example-flow", "flow")["edges"][0]["to"] = "missing-node"
        cases.append(("FLOW_EDGE_INVALID", layout))

        layout = copy.deepcopy(self.layout)
        self.block_from(layout, "example-fill", "source-label")["text"] = "Different label"
        cases.append(("SOURCE_TEXT_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        self.block_from(layout, "example-fill", "source-label")["source"]["sha256"] = "0" * 64
        cases.append(("SOURCE_HASH_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        self.block_from(layout, "example-fill", "source-label")["source"]["endUtf16"] = 1000
        cases.append(("INVALID_UTF16_RANGE", layout))

        layout = copy.deepcopy(self.layout)
        layout["unexpected"] = True
        cases.append(("UNKNOWN_FIELD", layout))

        layout = copy.deepcopy(self.layout)
        layout["basePartSha256"] = "0" * 64
        cases.append(("BASE_PART_HASH_MISMATCH", layout))

        layout = copy.deepcopy(self.layout)
        layout["schemaVersion"] = 3
        cases.append(("UNSUPPORTED_LAYOUT_SCHEMA", layout))

        for reason, mutated in cases:
            with self.subTest(reason=reason):
                self.assert_reason(reason, mutated)

    def test_repair_acceptance_mutations_and_alternatives(self):
        layout = copy.deepcopy(self.layout)
        group = self.group_from(layout, "example-multiple")
        group["responses"] = [
            {
                "kind": "singleChoice",
                "questionIds": [qid],
                "slotIds": [slot_id],
                "scoring": "legacyPerQuestion",
                "optionPoolId": "pool-colors",
            }
            for qid, slot_id in (("q2", "s2-0"), ("q3", "s3-0"))
        ]
        for slot in group["slots"]:
            slot["kind"] = "singleChoice"
        choice_index = next(i for i, block in enumerate(group["blocks"])
                            if block["id"] == "multi-choices")
        group["blocks"][choice_index:choice_index + 1] = [
            {"id": "multi-choices-q2", "type": "choiceList", "optionPoolId": "pool-colors", "slotIds": ["s2-0"]},
            {"id": "multi-choices-q3", "type": "choiceList", "optionPoolId": "pool-colors", "slotIds": ["s3-0"]},
        ]
        self.assert_reason("LEGACY_INTERACTION_MISMATCH", layout)

        layout = copy.deepcopy(self.layout)
        first_choice = self.block_from(layout, "example-matching", "match-first-choice")
        first_choice["slotIds"].append("s5-0")
        second = self.block_from(layout, "example-matching", "match-second")
        second["children"] = [child for child in second["children"]
                              if child.get("id") != "match-second-choice"]
        self.assert_reason("SLOT_PLACEMENT_MISMATCH", layout)

        layout = copy.deepcopy(self.layout)
        double = self.group_from(layout, "example-double")
        double["blocks"][1]["children"] = [child for child in double["blocks"][1]["children"]
                                           if child.get("id") != "double-connector"]
        self.assert_reason("ANSWER_CONNECTOR_MISMATCH", layout)

        layout = copy.deepcopy(self.layout)
        part = json.loads(self.part_bytes.decode("utf-8"))
        question = next(item for item in part["questions"] if item["id"] == "q7")
        question["instructions"] = "or"
        connector = self.block_from(layout, "example-double", "double-connector")
        connector["text"] = "or"
        connector["source"]["endUtf16"] = 2
        self.assertEqual("and", question["answerSeparator"])
        part_bytes = self.update_part_hash(layout, part)
        self.assert_reason_with_part("ANSWER_CONNECTOR_MISMATCH", layout, part_bytes)

        layout = copy.deepcopy(self.layout)
        double = self.group_from(layout, "example-double")
        children = double["blocks"][1]["children"]
        children[0], children[2] = children[2], children[0]
        self.assert_reason("SLOT_PLACEMENT_MISMATCH", layout)

        layout = copy.deepcopy(self.layout)
        part = json.loads(self.part_bytes.decode("utf-8"))
        question = next(item for item in part["questions"] if item["id"] == "q5")
        question["correctAnswer"] = "X"
        part_bytes = self.update_part_hash(layout, part)
        self.assert_reason_with_part("MATCHING_REUSE_CONFLICT", layout, part_bytes)

        layout = copy.deepcopy(self.layout)
        part = json.loads(self.part_bytes.decode("utf-8"))
        question = next(item for item in part["questions"] if item["id"] == "q5")
        question["acceptedAnswers"] = ["X", "Y"]
        part_bytes = self.update_part_hash(layout, part)
        self.validate(layout, part_bytes=part_bytes)

        review = copy.deepcopy(self.context["reviews"][0])
        review["sourceResource"] = "archive/path/map.png"
        source_bytes = dict(self.source_bytes)
        source_bytes["archive/path/map.png"] = self.source_bytes["map.png"]
        self.validate(source_bytes=source_bytes, review_records=[review])

        review = copy.deepcopy(self.context["reviews"][0])
        review["sourceResource"] = "../map.png"
        source_bytes = dict(self.source_bytes)
        source_bytes["../map.png"] = self.source_bytes["map.png"]
        with self.assertRaises(LayoutV2ValidationError) as raised:
            self.validate(source_bytes=source_bytes, review_records=[review])
        self.assertEqual("REVIEW_RECORD_MISSING", raised.exception.reason_code)

    def test_review_record_paths_follow_safe_posix_archive_rules(self):
        record = copy.deepcopy(self.context["reviews"][0])
        record["sourceResource"] = "archive/path/map.png"
        self.assertTrue(_valid_review_record(record))
        for path in (
            "/archive/map.png",
            "archive\\map.png",
            "archive//map.png",
            "archive/./map.png",
            "archive/../map.png",
            "C:/archive/map.png",
            "C:archive/map.png",
            "archive/\x00map.png",
        ):
            with self.subTest(path=path):
                record["sourceResource"] = path
                self.assertFalse(_valid_review_record(record))

    def test_absent_required_source_and_review_use_stable_codes(self):
        with self.assertRaises(LayoutV2ValidationError) as source_error:
            self.validate(source_bytes={})
        self.assertEqual("SOURCE_RESOURCE_MISSING", source_error.exception.reason_code)

        with self.assertRaises(LayoutV2ValidationError) as image_source_error:
            self.validate(source_bytes={"content.html": self.source_bytes["content.html"]})
        self.assertEqual("SOURCE_RESOURCE_MISSING", image_source_error.exception.reason_code)

        with self.assertRaises(LayoutV2ValidationError) as review_error:
            self.validate(review_records=[])
        self.assertEqual("REVIEW_RECORD_MISSING", review_error.exception.reason_code)

    def test_source_normalization_and_span_expansion(self):
        self.assertEqual("A BC", _normalized_resource(
            "content.html", b"<!--x--><p>A&nbsp;\n B</p><script>hidden</script><style>hidden</style><p>C</p>"
        ))
        self.assertEqual("A\nB\nC", _normalized_resource("content.txt", b"A\r\nB\rC"))

        valid = {"rows": [
            {"cells": [{"rowSpan": 2, "colSpan": 1}, {"rowSpan": 1, "colSpan": 1}]},
            {"cells": [{"rowSpan": 1, "colSpan": 1}]},
        ]}
        self.assertTrue(_table_is_rectangular(valid))
        valid["rows"][1]["cells"][0]["colSpan"] = 2
        self.assertFalse(_table_is_rectangular(valid))

    def test_schema_is_draft_2020_12_and_all_internal_refs_resolve(self):
        schema = json.loads((ROOT / "docs" / "schemas" / "layout-v2.schema.json").read_text(encoding="utf-8"))
        self.assertEqual("https://json-schema.org/draft/2020-12/schema", schema["$schema"])
        self.assertIs(schema["additionalProperties"], False)
        self.assertEqual(2_048, schema["x-maxTotalBlocks"])
        self.assertEqual("answerConnector", schema["$defs"]["textBlock"]["properties"]["role"]["const"])
        self.assertEqual(
            "#/$defs/ordinaryTextBlock",
            schema["$defs"]["group"]["properties"]["instructions"]["items"]["$ref"],
        )
        self.assertEqual("multipleChoice", schema["$defs"]["choiceListBlock"]["x-multipleSlotsRequireResponseKind"])

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

    @staticmethod
    def group_from(layout, group_id):
        return next(group for group in layout["groups"] if group["groupId"] == group_id)

    @classmethod
    def block_from(cls, layout, group_id, block_id):
        group = cls.group_from(layout, group_id)
        return next(block for block in walk(group["blocks"]) if block["id"] == block_id)


if __name__ == "__main__":
    unittest.main()
