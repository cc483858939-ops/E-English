import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from build_transcript_timelines import build


class TimelineBuilderTest(unittest.TestCase):
    def fixture(self, directory, audio=b'audio', rows=None, matching_audio=True):
        rows = rows or [dict(en='PART 1', zh='', start_ms=0, end_ms=100),
                        dict(en='first', zh='第一', start_ms=100, end_ms=600),
                        dict(en='second', zh='第二', start_ms=550, end_ms=900)]
        pack = directory/'packs'; pack.mkdir()
        source = directory/'source.zip'
        part = dict(id='cambridge-5-test-1-part-1', book=5, test=1, part=1,
                    audioSha256=hashlib.sha256(audio if matching_audio else b'other').hexdigest(),
                    transcript=dict(segments=[dict(english=r['en'], chinese=r['zh']) for r in rows]))
        with zipfile.ZipFile(pack/'cambridge-5.eelpack', 'w') as z:
            z.writestr('listening/cambridge-5-test-1-part-1/part.json', json.dumps(part))
        with zipfile.ZipFile(source, 'w') as z:
            z.writestr('source/Cambridge-5/Test1/Part1/timeline.json', json.dumps(rows))
            z.writestr('source/Cambridge-5/Test1/Part1/audio.mp3', audio)
        return pack, [source]

    def test_exact_binding_preserves_overlaps_and_skips_heading(self):
        with tempfile.TemporaryDirectory() as path:
            catalog, report = build(*self.fixture(Path(path)))
            self.assertEqual(1, report['boundParts'])
            self.assertEqual(1, len(report['overlapParts']))
            self.assertEqual([None, dict(startMs=100, endMs=600), dict(startMs=550, endMs=900)], catalog['parts'][0]['segments'])
            self.assertNotIn('第一', json.dumps(catalog, ensure_ascii=False))

    def test_wrong_audio_is_excluded_without_guessing(self):
        with tempfile.TemporaryDirectory() as path:
            catalog, report = build(*self.fixture(Path(path), matching_audio=False))
            self.assertEqual([], catalog['parts'])
            self.assertEqual(['cambridge-5-test-1-part-1'], report['excludedParts'])

    def test_backwards_source_ranges_are_excluded(self):
        with tempfile.TemporaryDirectory() as path:
            rows = [dict(en='first', zh='第一', start_ms=500, end_ms=600),
                    dict(en='second', zh='第二', start_ms=100, end_ms=400)]
            catalog, report = build(*self.fixture(Path(path), rows=rows))
            self.assertEqual([], catalog['parts'])
            self.assertEqual(0, report['boundParts'])


if __name__ == '__main__':
    unittest.main()
