"""Synthetic fixtures only; no copyrighted question/answer material in source."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from import_library import normalize, inventory, materialize, parse_questions, input_limit, visible, sha
from bs4 import BeautifulSoup


def fixture(book=5, value='alpha'):
    body = '<i>Questions 1-10<br>Complete the notes below.<br>Write ONE WORD ONLY for each answer.</i>'
    body += ''.join(f'<br><strong>{n}</strong> Fixture item <span data-ielts-blank="1"> _____ </span>' for n in range(1,11))
    html = '<section class="questions"><div id="analys-wrap-container"><div><div class="style_gap-filing__fixture"><div>' + body + '</div></div></div></div></section>'
    entries=[dict(question=n,answer=value,accepted_answers=[value],captured=True) for n in range(1,11)]
    answer_map={str(n):value for n in range(1,11)}
    audio=b'ID3'+b'\x00'*300
    metadata=dict(book=book,test=1,part=1,answers=dict(map=answer_map),audio=dict(bytes=len(audio),downloaded=True))
    answers=dict(answers=entries,expected_questions=list(range(1,11)),captured_questions=10,complete=True,answer_map=answer_map)
    timeline=[dict(sort=1,en='English synthetic fixture.',zh='中文合成测试。',start_ms=0,end_ms=1000)]
    return {name:json.dumps(data,ensure_ascii=False).encode() for name,data in [('part.json',metadata),('answers.json',answers),('timeline.json',timeline)]} | {
        'content.html':html.encode(), 'questions.txt':visible(BeautifulSoup(html,'html.parser')).encode(),
        'audio.mp3':audio,'transcript.txt':timeline[0]['en'].encode(),'translation.txt':timeline[0]['zh'].encode()}


def archive(path, resources, book=5):
    with zipfile.ZipFile(path,'w') as z:
        for name,data in resources.items():z.writestr(f'wrapper/Cambridge-{book}/Test1/Part1/'+name,data)


class LibraryImportTests(unittest.TestCase):
    def test_explicit_numbers_options_and_word_limit(self):
        model,_,_=normalize(fixture().__getitem__,5,1,1)
        self.assertEqual(list(range(1,11)),[q['number'] for q in model['questions']])
        self.assertTrue(all(q['type']=='NOTE_COMPLETION' and q['wordLimit']['maxWords']==1 for q in model['questions']))

    def test_missing_or_duplicate_number_is_rejected(self):
        for replacement in ['', '<strong>1</strong>']:
            source=fixture();source['content.html']=source['content.html'].replace(b'<strong>2</strong>',replacement.encode())
            with self.assertRaises(ValueError):normalize(source.__getitem__,5,1,1)

    def test_html_and_text_disagreement_is_not_guessed(self):
        source=fixture();source['questions.txt']=b'Different source text'
        with self.assertRaisesRegex(ValueError,'QUESTION_TEXT_HTML_MISMATCH'):normalize(source.__getitem__,5,1,1)

    def test_typographic_ligatures_compare_without_changing_rendered_text(self):
        source = fixture()
        source['content.html'] = source['content.html'].replace(b'Fixture', 'of\ufb01ce'.encode())
        source['questions.txt'] = source['questions.txt'].replace(b'Fixture', b'office')
        model, _, _ = normalize(source.__getitem__, 5, 1, 1)
        self.assertIn('of\ufb01ce', model['questions'][0]['prompt'])

    def test_answer_maps_and_accepted_answers_are_checked(self):
        source=fixture();answers=json.loads(source['answers.json']);answers['answers'][0]['accepted_answers']=['different']
        source['answers.json']=json.dumps(answers).encode()
        with self.assertRaisesRegex(ValueError,'ACCEPTED_ANSWERS_CONFLICT'):normalize(source.__getitem__,5,1,1)

    def test_missing_chinese_is_allowed_without_fabricating(self):
        source=fixture();timeline=json.loads(source['timeline.json']);timeline[0]['zh']=''
        source['timeline.json']=json.dumps(timeline).encode();del source['translation.txt']
        self.assertEqual('',normalize(source.__getitem__,5,1,1)[0]['transcript']['chinese'])

    def test_semantic_duplicates_and_conflicts_keep_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            a,b=Path(directory)/'a.zip',Path(directory)/'b.zip'
            archive(a,fixture(13),13);archive(b,fixture(13),13)
            report=inventory([a,b]);self.assertEqual(1,report['summary']['validatedParts']);self.assertTrue(report['duplicates'][0]['equivalent'])
            archive(b,fixture(13,'beta'),13)
            report=inventory([a,b]);self.assertEqual(0,report['summary']['validatedParts']);self.assertFalse(report['duplicates'][0]['equivalent'])
            self.assertEqual(2,len(report['duplicates'][0]['sources']))

    def test_zip_slip_and_duplicate_entries_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'bad.zip'
            with zipfile.ZipFile(path,'w') as z:z.writestr('../escape','bad')
            with self.assertRaisesRegex(ValueError,'UNSAFE_RESOURCE_PATH'):inventory([path])

    def test_repeatable_pack_contains_only_validated_parts(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'a.zip';archive(path,fixture())
            report=inventory([path]);out=Path(directory)/'output'
            self.assertEqual(1,materialize(report,out));before=(out/'cambridge-5.eelpack').read_bytes()
            self.assertEqual(1,materialize(report,out));self.assertEqual(before,(out/'cambridge-5.eelpack').read_bytes())
            with zipfile.ZipFile(out/'cambridge-5.eelpack') as z:
                index=json.loads(z.read('index.json'));part=index['parts'][0]
                self.assertEqual(part['partSha256'],sha(z.read('listening/'+part['id']+'/part.json')))
            source=fixture(value='beta');archive(path,source)
            with self.assertRaisesRegex(ValueError,'EXISTING_RESOURCE_CONFLICT'):materialize(inventory([path]),out)

    def test_word_and_numeric_limits(self):
        self.assertEqual(2,input_limit('NO MORE THAN TWO WORDS AND/OR A NUMBER')['maxWords'])
        self.assertEqual(2,input_limit('ONE WORD AND/OR TWO NUMBERS')['maxNumbers'])
        self.assertEqual(dict(maxWords=0, maxNumbers=2, numberOnly=True, wordsOrNumber=False),
                         input_limit('NO MORE THAN TWO NUMBERS'))
        with self.assertRaisesRegex(ValueError,'MISSING_WORD_LIMIT'):input_limit('Complete below')

    def test_split_limit_instruction_is_recovered_without_body_number_contamination(self):
        source = fixture()
        html = source['content.html'].decode()
        html = html.replace(
            '<i>Questions 1-10<br>Complete the notes below.<br>Write ONE WORD ONLY for each answer.</i>',
            '<i>Questions 1-10</i><br><i>Complete the notes below.</i><br><i>Write</i> '
            '<i>NO MORE THAN THREE WORDS AND/OR A NUMBER</i> <i>for each answer.</i>')
        html = html.replace('Fixture item', 'Fixture item; a number of examples and numbers of samples follow')
        source['content.html'] = html.encode()
        soup = BeautifulSoup(html, 'html.parser')
        source['questions.txt'] = visible(soup.select_one('section.questions')).encode()

        model, _, _ = normalize(source.__getitem__, 5, 1, 1)

        self.assertTrue(all(q['wordLimit'] == dict(maxWords=3, maxNumbers=1,
                                                   numberOnly=False, wordsOrNumber=False)
                            for q in model['questions']))

    def test_box_matching_instruction_is_not_treated_as_text_input(self):
        source = fixture(value='A')
        html = source['content.html'].decode().replace(
            'Complete the notes below.<br>Write ONE WORD ONLY for each answer.',
            'Choose your answers from the box and write the letters A-C next to Questions 1-10.')
        html = html.replace('</i>', '</i><br><strong>A</strong> First<br><strong>B</strong> Second<br><strong>C</strong> Third<br>')
        source['content.html'] = html.encode()
        source['questions.txt'] = visible(BeautifulSoup(html, 'html.parser')).encode()
        model, _, _ = normalize(source.__getitem__, 5, 1, 1)
        self.assertTrue(all(q['type'] == 'MATCHING' for q in model['questions']))
        self.assertEqual(['A', 'B', 'C'], [o['id'] for o in model['questions'][0]['options']])

    def test_instruction_letter_is_not_a_duplicate_option_label(self):
        source = fixture(value='A')
        html = source['content.html'].decode().replace(
            'Complete the notes below.<br>Write ONE WORD ONLY for each answer.',
            'Write the correct letter A, B or <strong>C</strong> next to Questions 1-10.')
        html = html.replace('</i>', '</i><br><strong>A</strong> First<br><strong>B</strong> Second<br><strong>C</strong> Third<br>')
        source['content.html'] = html.encode()
        source['questions.txt'] = visible(BeautifulSoup(html, 'html.parser')).encode()
        model, _, _ = normalize(source.__getitem__, 5, 1, 1)
        self.assertEqual(['A', 'B', 'C'], [o['id'] for o in model['questions'][0]['options']])

    def test_overlong_source_answer_is_not_silently_accepted(self):
        source=fixture(value='alpha or beta')
        with self.assertRaisesRegex(ValueError,'ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT'):normalize(source.__getitem__,5,1,1)


if __name__=='__main__':unittest.main()
