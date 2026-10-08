"""Public synthetic parser fixtures are tests only, never app practice content."""
import copy
import tempfile
import unittest
from pathlib import Path
from import_sample import parse_questions, parse_answers, read_json, import_sample, validate_html, NUMBERS


def questions_text():
    return "Questions 21-30\nChoose the correct letter, A, B or C.\nCourse Feedback\n" + "\n".join(
        f"{n} Synthetic parser fixture {n}\nA. First\nB. Second\nC. Third" for n in NUMBERS)


class ImportTests(unittest.TestCase):
    def test_exact_ten_and_options(self):
        questions = parse_questions(questions_text())
        self.assertEqual([q['number'] for q in questions], NUMBERS)
        self.assertTrue(all([o['id'] for o in q['options']] == list('ABC') for q in questions))

    def test_duplicate_number(self):
        with self.assertRaises(ValueError):
            parse_questions(questions_text().replace('22 Synthetic', '21 Synthetic'))

    def test_missing_question(self):
        with self.assertRaises(ValueError):
            parse_questions(questions_text().split('30 Synthetic')[0])

    def test_invalid_options(self):
        for text in [questions_text().replace('C. Third', 'B. Third'),
                     questions_text().replace('C. Third', ''),
                     questions_text().replace('A. First', 'D. First')]:
            with self.assertRaises(ValueError):
                parse_questions(text)

    def test_ambiguous_wrapped_prompt(self):
        with self.assertRaises(ValueError):
            parse_questions(questions_text() + '\nunknown continuation')

    def test_duplicate_json_key(self):
        with self.assertRaises(ValueError):
            read_json('{"a":1,"a":2}')

    def test_inert_html_cross_check(self):
        questions = parse_questions(questions_text())
        html = '<audio src="audio.mp3"></audio>' + ''.join(
            f'<div id="titleNum{q["number"]}">{q["prompt"]}' + ''.join(o['text'] for o in q['options']) + '</div>'
            for q in questions)
        validate_html(html, questions, local_audio=True)
        for bad in [html.replace('audio.mp3', 'https://example.invalid/audio.mp3'),
                    html.replace('titleNum22', 'titleNum21'),
                    html.replace('Synthetic parser fixture 21', 'Different text')]:
            with self.assertRaises(ValueError):
                validate_html(bad, questions, local_audio=True)

    def test_answer_cross_checks(self):
        data = dict(expected_questions=NUMBERS, complete=True, captured_questions=10, parser_version='test',
                    answers=[dict(question=n, answer='A', accepted_answers=['A'], captured=True) for n in NUMBERS],
                    answer_map={str(n): 'A' for n in NUMBERS},
                    parse_sources=[dict(question=n, source='style_correct', text=f'{n}. A') for n in NUMBERS])
        metadata = {'answers': dict(map=data['answer_map'], complete=True, captured_questions=10, parser_version='test')}
        parse_answers(data, metadata, parse_questions(questions_text()))
        for mutate in [lambda d: d['answers'][0].update(answer='Z'),
                       lambda d: d['answers'][0].update(accepted_answers=['A', 'B']),
                       lambda d: d['answer_map'].update({'21': 'B'}),
                       lambda d: d.update(complete=False),
                       lambda d: d['answers'][1].update(question=21)]:
            bad = copy.deepcopy(data)
            mutate(bad)
            with self.assertRaises(ValueError):
                parse_answers(bad, metadata, parse_questions(questions_text()))

    def test_real_sample_repeatable_when_explicitly_provided(self):
        import os
        source = os.environ.get('LISTENING_SAMPLE_ZIP')
        if not source:
            self.skipTest('Set LISTENING_SAMPLE_ZIP for private real sample validation')
        with tempfile.TemporaryDirectory() as temp:
            first = import_sample(source, temp)
            files = {str(p.relative_to(temp)): p.read_bytes() for p in Path(temp).rglob('*') if p.is_file()}
            second = import_sample(source, temp)
            self.assertEqual(first, second)
            self.assertEqual(files, {str(p.relative_to(temp)): p.read_bytes() for p in Path(temp).rglob('*') if p.is_file()})


if __name__ == '__main__':
    unittest.main()
