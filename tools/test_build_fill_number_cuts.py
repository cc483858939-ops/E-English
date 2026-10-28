import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from build_fill_number_cuts import build_catalog


class FillNumberCutBuilderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.packs = Path(self.temp.name)
        self.text = '💷 Cost 7 £ '
        self.record = dict(book=5, part='part', group='g', slot='s', block='b', question=7,
                           text=self.text, numberStart=self.text.index('7'), numberEnd=self.text.index('7')+1,
                           sourceMatched=True, sourceBold=True, covered=False)
        self.group = dict(groupId='g', slots=[dict(slotId='s', questionId='q7', kind='fill')], blocks=[
            dict(type='formRow', children=[dict(type='text', id='b', text=self.text), dict(type='slot', slotId='s')])])
        self.write_pack()

    def write_pack(self):
        with zipfile.ZipFile(self.packs/'cambridge-5-v4-test.eelpack', 'w') as z:
            z.writestr('listening/part/layout.json', json.dumps(dict(groups=[self.group])))
            z.writestr('listening/part/part.json', json.dumps(dict(questions=[dict(id='q7', number=7)])))

    def build(self, record=None):
        return build_catalog(dict(records=[record or self.record]), self.packs)

    def test_utf16_offsets_and_output_contain_no_source_text(self):
        catalog = self.build()
        cut = catalog['entries'][0]
        self.assertEqual(self.text.index('7')+1, cut['startUtf16'])
        self.assertEqual('7', self.text.encode('utf-16-le')[2*cut['startUtf16']:2*cut['endUtf16']].decode('utf-16-le'))
        self.assertNotIn('Cost', json.dumps(catalog))
        self.assertEqual(catalog, self.build())

    def test_changed_source_unreviewed_range_or_duplicate_inventory_is_rejected(self):
        for updates in [dict(text='changed'), dict(question=8), dict(sourceMatched=False), dict(sourceBold=False),
                        dict(numberStart=0, numberEnd=1)]:
            record = dict(self.record, **updates)
            with self.subTest(updates=updates), self.assertRaisesRegex(ValueError, 'REVIEW_BINDING_MISMATCH'):
                self.build(record)
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_REVIEW_CUT'):
            build_catalog(dict(records=[self.record, copy.deepcopy(self.record)]), self.packs)

    def test_slot_moved_before_text_is_rejected(self):
        self.group['blocks'][0]['children'].reverse()
        self.write_pack()
        with self.assertRaisesRegex(ValueError, 'REVIEW_SLOT_POSITION_MISMATCH'):
            self.build()


if __name__ == '__main__':
    unittest.main()
