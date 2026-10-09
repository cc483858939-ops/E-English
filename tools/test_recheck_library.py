"""Synthetic integrity checks, without exam content."""
from pathlib import Path
import tempfile
import unittest
import zipfile

from import_library import inventory, materialize
from recheck_library import verify
from test_import_library import archive, fixture


class RecheckLibraryTests(unittest.TestCase):
    def setup_pack(self, root):
        source = root / 'source.zip'
        archive(source, fixture())
        report = inventory([source])
        output = root / 'packs'
        materialize(report, output)
        return report, output

    def test_rechecks_exact_source_and_unpacked_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            report, output = self.setup_pack(Path(directory))
            result = verify(report, output)
            self.assertEqual(1, result[0]['parts'])
            self.assertEqual(10, result[0]['questions'])
            self.assertEqual({'NOTE_COMPLETION': 10}, result[0]['questionTypes'])

    def test_changed_pack_audio_is_rejected_even_with_valid_zip_crc(self):
        with tempfile.TemporaryDirectory() as directory:
            report, output = self.setup_pack(Path(directory))
            path = output / 'cambridge-5.eelpack'
            with zipfile.ZipFile(path) as pack:
                resources = {name: pack.read(name) for name in pack.namelist()}
            name = next(name for name in resources if name.endswith('audio.mp3'))
            resources[name] += b'changed'
            with zipfile.ZipFile(path, 'w') as pack:
                for name, raw in resources.items():
                    pack.writestr(name, raw)
            with self.assertRaisesRegex(ValueError, 'SOURCE_PACK_RESOURCE_MISMATCH'):
                verify(report, output)

    def test_extra_book_is_not_silently_installed(self):
        with tempfile.TemporaryDirectory() as directory:
            report, output = self.setup_pack(Path(directory))
            (output / 'cambridge-6.eelpack').write_bytes(b'unexpected')
            with self.assertRaisesRegex(ValueError, 'PACK_SET_MISMATCH'):
                verify(report, output)


if __name__ == '__main__':
    unittest.main()
