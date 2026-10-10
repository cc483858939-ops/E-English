"""Pack-builder regression tests with synthetic data only."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from build_layout_library import _parse_range, build_book_pack, sha256, verify_book_pack
from layout_generator import generate_layout
from test_layout_generator import synthetic_html, synthetic_part


class LayoutPackBuilderTests(unittest.TestCase):
    def test_single_question_audit_range_is_exact(self):
        self.assertEqual((20, 20), _parse_range({"questionRange": "20"}))
        self.assertEqual((19, 20), _parse_range({"questionRange": "19-20"}))
        self.assertIsNone(_parse_range({"questionRange": "20-"}))

    def test_layout_only_pack_update_preserves_all_original_resources(self):
        part = synthetic_part()
        part_bytes = json.dumps(part, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        audio = b"synthetic audio bytes"
        image = b"synthetic image bytes"
        image_name = "diagram.png"
        part_id = part["id"]
        index = {
            "schemaVersion": 1,
            "book": 5,
            "parts": [{
                "id": part_id,
                "book": 5,
                "test": 1,
                "part": 1,
                "title": "Synthetic",
                "questionCount": 1,
                "firstQuestion": 1,
                "lastQuestion": 1,
                "audioDurationMs": 1000,
                "partSha256": sha256(part_bytes),
                "audioSha256": sha256(audio),
                "imageHashes": {image_name: sha256(image)},
            }],
        }
        layout, statuses = generate_layout(part_bytes, synthetic_html())
        self.assertTrue(statuses[0]["sourceProjectionMatches"])
        layout_bytes = (json.dumps(layout, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "base.eelpack"
            output_path = root / "updated.eelpack"
            prefix = f"listening/{part_id}/"
            with zipfile.ZipFile(source_path, "w") as archive:
                archive.writestr("index.json", json.dumps(index).encode("utf-8"))
                archive.writestr(prefix + "part.json", part_bytes)
                archive.writestr(prefix + "audio.mp3", audio)
                archive.writestr(prefix + image_name, image)

            updated_index = build_book_pack(source_path, output_path, {part_id: layout_bytes})
            checked = verify_book_pack(
                source_path,
                output_path,
                {part_id: layout_bytes},
                {part_id: synthetic_html()},
                {part_id: part},
                {part_id: part_bytes},
            )

            self.assertEqual(2, updated_index["schemaVersion"])
            self.assertEqual(sha256(layout_bytes), updated_index["parts"][0]["layoutSha256"])
            self.assertEqual(1, checked["layoutParts"])
            self.assertEqual(3, checked["preservedOriginalResources"])
            with zipfile.ZipFile(source_path) as before, zipfile.ZipFile(output_path) as after:
                for name in (prefix + "part.json", prefix + "audio.mp3", prefix + image_name):
                    self.assertEqual(before.read(name), after.read(name))
                self.assertEqual(layout_bytes, after.read(prefix + "layout.json"))


if __name__ == "__main__":
    unittest.main()
