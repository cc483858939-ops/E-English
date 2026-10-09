"""Synthetic regressions for explicit paired blanks and hash-bound source corrections."""
import json
import io
from pathlib import Path
import unittest
from bs4 import BeautifulSoup
from PIL import Image, ImageDraw
from import_library import normalize, within_limit, input_limit, visible, sha
from test_import_library import fixture


def paired_fixture(value='alpha and beta', separator='and', hint=True, limit='NO MORE THAN TWO WORDS'):
    source = fixture(value='42' if 'NUMBERS' in limit else 'alpha')
    html = source['content.html'].decode().replace('ONE WORD ONLY',limit)
    if hint:
        html = html.replace('</span>',f'</span>（此题请以 _____ {separator} _____ 形式作答）',1)
    source['content.html'] = html.encode()
    source['questions.txt'] = visible(BeautifulSoup(html,'html.parser')).encode()
    answers = json.loads(source['answers.json']); metadata = json.loads(source['part.json'])
    answers['answers'][0].update(answer=value,accepted_answers=[value])
    answers['answer_map']['1'] = metadata['answers']['map']['1'] = value
    source['answers.json'] = json.dumps(answers).encode(); source['part.json'] = json.dumps(metadata).encode()
    return source


class PendingPartTests(unittest.TestCase):
    def test_shared_python_and_kotlin_limit_vectors(self):
        path=Path(__file__).resolve().parents[1]/'app/src/test/resources/input-limits.json'
        cases=json.loads(path.read_text(encoding='utf-8'))
        self.assertEqual(28,len(cases))
        for case in cases:
            with self.subTest(case=case):
                self.assertEqual(case['limit'],input_limit(case['instruction']))
                self.assertEqual(case['allowed'],within_limit(case['value'],case['limit'],case.get('separator')))

    def test_paired_blanks_preserve_original_answer_and_require_explicit_hint(self):
        source=paired_fixture()
        model,_,_=normalize(source.__getitem__,5,1,1)
        q=model['questions'][0]
        self.assertEqual('and',q['answerSeparator'])
        self.assertEqual(['alpha and beta'],q['acceptedAnswers'])
        self.assertEqual('alpha and beta',q['correctAnswer'])
        self.assertEqual(2,q['wordLimit']['maxWords'])
        self.assertNotIn('answerSeparator',model['questions'][1])
        with self.assertRaisesRegex(ValueError,'ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT'):
            normalize(paired_fixture(hint=False).__getitem__,5,1,1)

    def test_paired_hint_never_relaxes_limits_or_splits_equivalent_answers(self):
        for value in ('alpha gamma and beta','alpha and','alpha and beta and gamma','alpha/beta'):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError,'ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT'):
                normalize(paired_fixture(value=value).__getitem__,5,1,1)

    def test_explicit_clock_range_has_two_numbers_and_no_typed_joining_word(self):
        source=paired_fixture('9 a.m. to 6.45 p.m.','to',limit='NO MORE THAN TWO NUMBERS')
        q=normalize(source.__getitem__,5,1,1)[0]['questions'][0]
        self.assertEqual('to',q['answerSeparator']); self.assertTrue(q['wordLimit']['numberOnly'])

    def test_map_range_correction_requires_hash_and_edits_both_question_sources(self):
        source=fixture(value='A')
        html=source['content.html'].decode().replace('Write ONE WORD ONLY for each answer.',
            'Write the correct letter A-l next to Questions 1-10.')
        html=html.replace('</i>','</i><img src="map.png"/>')
        image=Image.new('RGB',(180,40),'white'); ImageDraw.Draw(image).text((10,10),'A    B    C',fill='black')
        raw=io.BytesIO(); image.save(raw,format='PNG'); source['map.png']=raw.getvalue()
        source['content.html']=html.encode(); source['questions.txt']=visible(BeautifulSoup(html,'html.parser')).encode()
        with self.assertRaisesRegex(ValueError,'INVALID_MATCHING_RANGE'):
            normalize(source.__getitem__,5,1,1)
        record=dict(resourceHashes={k:sha(v) for k,v in source.items()},evidence=['Synthetic explicit labels A-C'],
            edits=[dict(resource=k,old='A-l',new='A-C',count=1) for k in ('content.html','questions.txt')])
        corrections={'cambridge-5-test-1-part-1':record}
        model,_,_=normalize(source.__getitem__,5,1,1,corrections)
        self.assertEqual(['A','B','C'],[o['id'] for o in model['questions'][0]['options']])
        source['transcript.txt']+=b'changed'
        with self.assertRaisesRegex(ValueError,'CORRECTION_SOURCE_HASH_MISMATCH'):
            normalize(source.__getitem__,5,1,1,corrections)


if __name__=='__main__': unittest.main()
