"""Layout schema/generator tests use synthetic fixtures only."""
import copy
import json
import re
import unittest

from layout_generator import encode_layout, generate_layout
from layout_validator import LayoutValidationError, validate_layout


def synthetic_part(*, paired=False, prompt=None):
    prompt = prompt or "Questions 1-1\nComplete the notes below.\nWrite ONE WORD ONLY for each answer.\n😀 Café A\u030A 1 _____ tail"
    question = {
        "id": "cambridge-5-test-1-part-1-q1", "number": 1, "type": "NOTE_COMPLETION",
        "prompt": prompt, "options": [], "correctAnswer": "fixture", "acceptedAnswers": ["fixture"],
        "instructions": "ONE WORD ONLY", "context": "", "images": [],
        "wordLimit": {"maxWords": 1, "maxNumbers": 0, "numberOnly": False, "wordsOrNumber": False},
        "groupId": None, "groupNumbers": [], "answerSeparator": "and" if paired else None,
    }
    return {"schemaVersion": 2, "id": "cambridge-5-test-1-part-1", "questions": [question]}


def synthetic_html(*, paired=False, number=1):
    body = (f"Questions 1-1<br>Complete the notes below.<br>Write ONE WORD ONLY for each answer.<br>"
            f"😀 Café A\u030A <strong>{number}</strong> <span data-ielts-blank=\"1\">_____</span>")
    if paired:
        body += " and <span data-ielts-blank=\"1\">_____</span>"
    body += " tail"
    return ("<section class='questions'><div id='analys-wrap-container'><div><div class='style_gap-filing__fixture'>"
            f"<div>{body}</div></div></div></div></section>").encode("utf-8")


def document(paired=False, prompt=None):
    part = synthetic_part(paired=paired, prompt=prompt)
    part_bytes = json.dumps(part, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    layout, statuses = generate_layout(part_bytes, synthetic_html(paired=paired))
    return part, part_bytes, layout, statuses


def walk(blocks):
    for block in blocks:
        yield block
        yield from walk(block.get("children", []))


class LayoutGeneratorTests(unittest.TestCase):
    def test_synthetic_group_generates_repeatably_and_maps_utf16_offsets(self):
        part, part_bytes, layout, statuses = document()
        again, statuses_again = generate_layout(part_bytes, synthetic_html())
        self.assertEqual(["converted"], [item["status"] for item in statuses])
        self.assertTrue(statuses[0]["sourceProjectionMatches"])
        self.assertEqual(encode_layout(layout), encode_layout(again))
        self.assertEqual(statuses, statuses_again)
        validate_layout(layout, part, part_json_bytes=part_bytes, source_html_bytes=synthetic_html())
        prompt = part["questions"][0]["prompt"]
        tail = next(b for b in walk(layout["groups"][0]["blocks"])
                    if b["type"] == "text" and b["text"].strip() == "tail")
        expected = len(prompt[:prompt.index(tail["text"])].encode("utf-16-le")) // 2
        self.assertEqual(expected, tail["source"]["startUtf16"])
        self.assertIn("prompt", tail["source"]["kind"])

    def test_two_slots_remain_one_logical_question(self):
        part, part_bytes, layout, statuses = document(paired=True)
        self.assertEqual("converted", statuses[0]["status"])
        blanks = [block for block in walk(layout["groups"][0]["blocks"]) if block["type"] == "blank"]
        self.assertEqual([0, 1], [item["slotIndex"] for item in blanks])
        self.assertEqual([part["questions"][0]["id"]] * 2, [item["questionId"] for item in blanks])
        self.assertIn("fixedText", [block["type"] for block in walk(layout["groups"][0]["blocks"])])
        validate_layout(layout, part, part_json_bytes=part_bytes, source_html_bytes=synthetic_html(paired=True))

    def test_repeated_source_strings_are_marked_html_without_guessing_offsets(self):
        prompt = "Questions 1-1\nComplete the notes below.\nWrite ONE WORD ONLY for each answer.\nrepeat 1 _____ repeat repeat tail"
        part = synthetic_part(prompt=prompt)
        part_bytes = json.dumps(part, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        html = ("<section class='questions'><div id='analys-wrap-container'><div><div class='gap'>"
                "<div>Questions 1-1<br>Complete the notes below.<br>Write ONE WORD ONLY for each answer.<br>"
                "repeat <strong>1</strong> <span data-ielts-blank='1'>_____</span> repeat repeat tail"
                "</div></div></div></div></section>").encode()
        layout, statuses = generate_layout(part_bytes, html)
        self.assertEqual("converted", statuses[0]["status"])
        refs = [b["source"] for b in walk(layout["groups"][0]["blocks"])
                if b["type"] == "text" and b["text"].strip() == "repeat"]
        self.assertTrue(refs)
        self.assertTrue(all(ref["kind"] == "html" for ref in refs))

    def test_missing_blank_slot_and_invalid_offsets_fail_validation(self):
        part, part_bytes, layout, _ = document()
        missing = copy.deepcopy(layout)
        missing["groups"][0]["blocks"] = [b for b in missing["groups"][0]["blocks"] if b["type"] != "paragraph"]
        with self.assertRaisesRegex(LayoutValidationError, "BLANK_COUNT_MISMATCH"):
            validate_layout(missing, part, part_json_bytes=part_bytes)
        bad_offset = copy.deepcopy(layout)
        tail = next(b for b in walk(bad_offset["groups"][0]["blocks"])
                    if b["type"] == "text" and b["source"]["kind"] == "prompt")
        tail["source"]["startUtf16"] += 1
        with self.assertRaisesRegex(LayoutValidationError, "SOURCE_TEXT_MISMATCH"):
            validate_layout(bad_offset, part, part_json_bytes=part_bytes)

    def test_group_question_hash_part_hash_and_unknown_blocks_are_rejected(self):
        part, part_bytes, layout, _ = document()
        bad_hash = copy.deepcopy(layout)
        bad_hash["basePartSha256"] = "0" * 64
        with self.assertRaisesRegex(LayoutValidationError, "BASE_PART_HASH_MISMATCH"):
            validate_layout(bad_hash, part, part_json_bytes=part_bytes)
        bad_prompt = copy.deepcopy(layout)
        bad_prompt["groups"][0]["promptHashes"][part["questions"][0]["id"]] = "0" * 64
        with self.assertRaisesRegex(LayoutValidationError, "PROMPT_HASH_MISMATCH"):
            validate_layout(bad_prompt, part, part_json_bytes=part_bytes)
        bad_block = copy.deepcopy(layout)
        bad_block["groups"][0]["blocks"].append({"type": "image", "path": "fake.png"})
        with self.assertRaisesRegex(LayoutValidationError, "UNSUPPORTED_BLOCK_TYPE"):
            validate_layout(bad_block, part, part_json_bytes=part_bytes)


if __name__ == "__main__":
    unittest.main()
