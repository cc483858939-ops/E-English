"""Audit/import private Cambridge archives without changing inputs or guessing content.

Reports, normalized books and .eelpack ZIP files belong in ignored private-data/.
Only counts and diagnostic codes are printed; exam text/answers stay in private files.
Requires beautifulsoup4 and Pillow (see requirements-listening.txt).
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import tempfile
import unicodedata
import zipfile

from bs4 import BeautifulSoup
from PIL import Image
from import_sample import read_json, require, convert as convert_sample


IDENTITY = re.compile(r"(?:^|/)Cambridge-(\d+)/Test(\d+)/Part(\d+)/part\.json$")
KINDS = ("SINGLE_CHOICE", "MULTIPLE_CHOICE", "MATCHING", "TEXT_INPUT", "SHORT_ANSWER",
         "TABLE_COMPLETION", "NOTE_COMPLETION", "FLOW_COMPLETION", "IMAGE_BASED")
COUNT_WORDS = {"ONE": 1, "TWO": 2, "THREE": 3, "FOUR": 4, "FIVE": 5, "SIX": 6}
TYPOGRAPHIC_LIGATURES = str.maketrans({"\ufb00": "ff", "\ufb01": "fi", "\ufb02": "fl",
                                    "\ufb03": "ffi", "\ufb04": "ffl", "\ufb05": "st", "\ufb06": "st"})


def sha(data):
    return hashlib.sha256(data).hexdigest()


def text(data):
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            pass
    raise ValueError("UNSUPPORTED_TEXT_ENCODING")


def clean(value):
    return re.sub(r"\s+", " ", value).strip()


def safe_name(name):
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in name
            and ":" not in name, "UNSAFE_RESOURCE_PATH")
    return name


def is_matching_instruction(value):
    value = clean(value).lower()
    return ("correct letter" in value or "letters from" in value or
            "choose your answers from the box" in value and "write the letters" in value)


def source_types(html):
    soup=BeautifulSoup(html,'html.parser')
    counts=Counter()
    for wrapper in soup.select('section.questions #analys-wrap-container > div'):
        block=wrapper.find('div',recursive=False)
        if block is None:
            continue
        cls=' '.join(block.get('class',[]))
        title=block.find('div',recursive=False)
        context=visible(title) if title else ''
        if 'single-choice__' in cls:
            counts['SINGLE_CHOICE']+=1
        elif 'multiple-choice__' in cls:
            try:
                counts['MULTIPLE_CHOICE']+=len(range_numbers(context))
            except ValueError:
                counts['UNCLASSIFIED']+=1
        elif 'gap-filing__' in cls:
            kind='TEXT_INPUT'
            if is_matching_instruction(context):
                kind='IMAGE_BASED' if title.select('img') else 'MATCHING'
            else:
                for phrase,name in [('complete the table','TABLE_COMPLETION'),('complete the notes','NOTE_COMPLETION'),('complete the flow','FLOW_COMPLETION'),('answer the questions','SHORT_ANSWER')]:
                    if phrase in context.lower():
                        kind=name;break
                if kind=='TEXT_INPUT' and title.select('img'):
                    kind='IMAGE_BASED'
            counts[kind]+=len(title.select('[data-ielts-blank]'))
    return dict(counts)


def visible(node):
    copy = BeautifulSoup(str(node), "html.parser")
    for tag in copy.select("script, style, .style_islogin, [class*='answer-con']"):
        tag.decompose()
    for tag in copy.find_all("br"):
        tag.replace_with("\n")
    return "\n".join(clean(line) for line in copy.get_text().splitlines() if clean(line))


def range_numbers(value):
    match = re.search(r"Questions?\s+(\d+)(?:\s*[-–—]\s*(\d+)|\s+and\s+(\d+))?", value, re.I)
    require(match is not None, "MISSING_QUESTION_RANGE")
    first = int(match[1])
    if match[2]:
        return list(range(first, int(match[2]) + 1))
    if match[3]:
        return [first, int(match[3])]
    return [first]


def input_limit(value):
    upper = clean(value).upper()
    numeric = re.search(r"\b(ONE|TWO|THREE|FOUR|FIVE|SIX|[1-6])\s+NUMBERS?\b", upper)
    if numeric and "WORD" not in upper:
        count = COUNT_WORDS.get(numeric[1], int(numeric[1]) if numeric[1].isdigit() else None)
        return dict(maxWords=0, maxNumbers=count, numberOnly=True, wordsOrNumber=False)
    if "NUMBER ONLY" in upper or "A NUMBER" in upper and "WORD" not in upper:
        return dict(maxWords=0, maxNumbers=1, numberOnly=True, wordsOrNumber=False)
    match = re.search(r"\b(ONE|TWO|THREE|FOUR|FIVE|SIX|[1-6])\s+WORDS?\b", upper)
    require(match is not None, "MISSING_WORD_LIMIT")
    count = COUNT_WORDS.get(match[1], int(match[1]) if match[1].isdigit() else None)
    number = re.search(r"\b(A|ONE|TWO|THREE|[1-3])\s+NUMBERS?", upper)
    maximum = COUNT_WORDS.get(number[1], 1 if number[1] == "A" else int(number[1]) if number[1].isdigit() else None) if number else (None if "NUMBERS" in upper else 0)
    return dict(maxWords=count, maxNumbers=maximum, numberOnly=False,
                wordsOrNumber=bool(re.search(r"WORDS? OR (?:A|ONE) NUMBER", upper)))


def _word_limit_phrase(value):
    token = r"(?:ONE|TWO|THREE|FOUR|FIVE|SIX|[1-6])"
    number = r"(?:A|ONE|TWO|THREE|[1-3])"
    patterns = (
        rf"\bNO MORE THAN\s+{token}\s+WORDS?(?:\s+(?:AND/OR|OR)\s+{number}\s+NUMBERS?)?",
        rf"\b{token}\s+WORDS?(?:\s+ONLY)?(?:\s+(?:AND/OR|OR)\s+{number}\s+NUMBERS?)?",
        rf"\b{number}\s+NUMBERS?(?:\s+ONLY)?",
        r"\bNUMBER ONLY\b",
    )
    normalized = clean(value)
    for pattern in patterns:
        match = re.search(pattern, normalized, re.I)
        if match:
            return match.group(0)
    return None


def word_limit_instruction(instruction, context):
    """Recover a visibly split rule only when the selected instruction lost its limit.

    Preserve the historic parser whenever the selected text already contains a word/number
    rule. One source splits "Write", its limit, and "for each answer" across adjacent <i>
    nodes; in that case the complete visible direction line is a safe recovery source.
    """
    if isinstance(instruction, str) and instruction:
        if (not re.search(r"\bWORDS?\b|\bNUMBERS?\b", instruction, re.I)
                and re.search(r"\bWrite\b", instruction, re.I)):
            for line in context.splitlines() if isinstance(context, str) else ():
                if (re.search(r"\bWrite\b", line, re.I) and
                        re.search(r"\bfor each answer\b", line, re.I)):
                    phrase = _word_limit_phrase(line)
                    if phrase:
                        return phrase
    return None


def response_parts(value, separator=None):
    value = unicodedata.normalize('NFC', clean(value)).lower()
    if separator is None:
        return [value]
    require(separator in ('and', 'or', 'to'), 'INVALID_ANSWER_SEPARATOR')
    parts = re.split(r'(?:^|\s+)' + separator + r'(?:\s+|$)', value)
    return parts if len(parts) == 2 and all(parts) else []


def within_limit(value, limit, separator=None):
    parts = response_parts(value, separator)
    if not parts or not all(parts):
        return False
    # AM/PM belongs to a clock number, including source variants with spaces/dots.
    counted = re.sub(r'(?<!\S)(\d{1,2}(?:[.:]\d{2})?)\s+([ap]\.?m\.?)(?!\S)', r'\1\2', ' '.join(parts))
    tokens=counted.split()
    numbers=sum(bool(re.fullmatch(r'(?:[£$€+-]?\d+(?:[.,:/-]\d+)*(?:st|nd|rd|th)?%?|\d{1,2}(?:[.:]\d{2})?[ap]\.?m\.?)', token)) for token in tokens)
    words=len(tokens)-numbers
    return words<=limit['maxWords'] and (limit['maxNumbers'] is None or numbers<=limit['maxNumbers']) and (not limit['numberOnly'] or words==0) and (not limit['wordsOrNumber'] or words==0 or numbers==0)


def options(node, multiple=False):
    selector = "[class*='multiple-choice-one__']" if multiple else "[class*='single-choice-one__']"
    result = []
    for element in node.select(selector):
        match = re.fullmatch(r"([A-Z])\s*[.．]\s*(.+)", clean(element.get_text(" ", strip=True)))
        require(match is not None, "AMBIGUOUS_OPTION")
        result.append(dict(id=match[1], text=match[2]))
    require(len(result) >= 2 and len({o['id'] for o in result}) == len(result), "INVALID_OPTIONS")
    return result


def parse_questions(html, part_id, expected, answers):
    soup = BeautifulSoup(html, "html.parser")
    section = soup.select_one("section.questions")
    require(section is not None, "MISSING_QUESTIONS_SECTION")
    blocks = section.select("#analys-wrap-container > div")
    require(bool(blocks), "UNSUPPORTED_QUESTION_CONTAINER")
    result, instructions = [], []
    for wrapper in blocks:
        block = wrapper.find("div", recursive=False)
        require(block is not None, "MISSING_QUESTION_BLOCK")
        cls = " ".join(block.get("class", []))
        title = block.find("div", recursive=False)
        require(title is not None, "MISSING_QUESTION_TITLE")
        context = visible(title)
        image_paths = [safe_name(t.get("src", "")) for t in title.select("img")]
        require(all(p and "/" not in p for p in image_paths), "NONLOCAL_QUESTION_IMAGE")
        instruction = " ".join(visible(i) for i in title.find_all("i") if "Question" in visible(i) or "Write" in visible(i) or "Choose" in visible(i) or "Complete" in visible(i))
        if not instruction:
            instruction = " ".join(line for line in context.splitlines() if re.match(r"(?:Questions?|Write|Choose|Complete|Label|Answer)\b", line, re.I))
        if instruction:
            instructions.append(instruction)
        base = dict(options=[], images=image_paths, context="", instructions=instruction,
                    acceptedAnswers=[], groupId=None, groupNumbers=[], wordLimit=None)
        if "style_single-choice__" in cls:
            match = re.fullmatch(r"titleNum(\d+)", block.get("id", ""))
            require(match is not None, "SINGLE_CHOICE_NUMBER_MISSING")
            number = int(match[1])
            marker = next((s for s in title.find_all("strong") if clean(s.get_text()) == str(number)), None)
            require(marker is not None, "SINGLE_CHOICE_PROMPT_MISSING")
            # Find the actual numbered prompt, not a repeated word or answer string.
            copied = BeautifulSoup(str(title), "html.parser")
            marker_copy = next(s for s in copied.find_all("strong") if clean(s.get_text()) == str(number))
            marker_copy.replace_with("[[PROMPT]]")
            prompt = clean(visible(copied).split("[[PROMPT]]", 1)[1])
            require(bool(prompt), "EMPTY_PROMPT")
            base.update(type="SINGLE_CHOICE", options=options(block), prompt=prompt)
            numbers = [number]
        elif "style_multiple-choice__" in cls:
            numbers = range_numbers(context)
            choice_count = re.search(r"Choose\s+(TWO|THREE|FOUR|FIVE|SIX)\s+letters", clean(context), re.I)
            require(choice_count is not None and COUNT_WORDS[choice_count[1].upper()] == len(numbers), "UNSUPPORTED_MULTI_SCORING")
            base.update(type="MULTIPLE_CHOICE", options=options(block, True), prompt=context,
                        groupId=f"{part_id}-group-{numbers[0]}", groupNumbers=numbers)
        elif "style_gap-filing__" in cls:
            copied = BeautifulSoup(str(title), "html.parser")
            blanks = copied.select("[data-ielts-blank]")
            require(bool(blanks), "MISSING_GAP_MARKERS")
            for blank in blanks:
                blank.replace_with("[[BLANK]]")
            for strong in copied.find_all("strong"):
                label = clean(strong.get_text())
                if label.isdigit() and int(label) in expected:
                    strong.replace_with(f"[[Q:{label}]]")
            marked = visible(copied)
            numbers, answer_separators = [], {}
            blank_matches = list(re.finditer(r"\[\[BLANK\]\]", marked))
            for position, blank in enumerate(blank_matches):
                # Some source labels are plain text, not <strong>. Read the explicit
                # nearest number; never synthesize missing labels from blank order.
                preceding = list(re.finditer(r"(?<![\w])(" + '|'.join(map(str,expected)) + r")(?![\w])", marked[:blank.start()]))
                require(bool(preceding), "GAP_NUMBER_NOT_EXPLICIT")
                number = int(preceding[-1][1])
                numbers.append(number)
                # Source sites collapse two answer boxes into one input and explicitly
                # document its fixed joining text. Never infer this from an answer.
                end = blank_matches[position+1].start() if position+1 < len(blank_matches) else len(marked)
                tail = marked[blank.end():end]
                hint = re.match(r'\s*[（(]\s*(?:此题|本题)(?:请)?(?:以|按)\s*_{2,}\s*(and|or|to)\s*_{2,}\s*形式作答\s*[）)]', tail, re.I)
                if hint:
                    answer_separators[number] = hint[1].lower()
            require(len(numbers) == len(set(numbers)), "AMBIGUOUS_GAP_NUMBERS")
            letter_range = re.search(r"\b([A-Z])\s*[-–—]\s*([A-Z])\b", clean(context))
            matching = is_matching_instruction(context)
            if matching:
                explicit_labels = [clean(s.get_text()) for s in title.find_all('strong')
                                   if re.fullmatch('[A-Z]', clean(s.get_text())) and s.find_parent('i') is None]
                labels = [chr(i) for i in range(ord(letter_range[1]), ord(letter_range[2])+1)] if letter_range else list(dict.fromkeys(explicit_labels))
                require(2 <= len(labels) <= 26, "INVALID_MATCHING_RANGE")
                candidate = []
                for line in context.splitlines():
                    match = re.fullmatch(r"([A-Z])\s+(.+)", line)
                    if match and match[1] in labels:
                        candidate.append(dict(id=match[1], text=match[2]))
                if len(candidate) == len(labels) and [o['id'] for o in candidate] == labels:
                    opts = candidate
                else:
                    # Explicit DOM labels allow two options on the same visual line.
                    candidate = []
                    for label_node in title.find_all('strong'):
                        label = clean(label_node.get_text())
                        if label not in labels or label_node.find_parent('i') is not None:
                            continue
                        fragments = []
                        for sibling in label_node.next_siblings:
                            if getattr(sibling, 'name', None) == 'br' or getattr(sibling, 'name', None) == 'strong' and clean(sibling.get_text()) in labels:
                                break
                            fragments.append(sibling.get_text() if hasattr(sibling, 'get_text') else str(sibling))
                        value = clean(''.join(fragments))
                        if value:
                            candidate.append(dict(id=label,text=value))
                    if len(candidate) == len(labels) and [o['id'] for o in candidate] == labels:
                        opts = candidate
                    else:
                        require(bool(image_paths), "MATCHING_OPTION_TEXT_MISSING")
                        opts = [dict(id=label, text=label) for label in labels]
                base.update(type="IMAGE_BASED" if image_paths else "MATCHING", options=opts)
            else:
                kind = "TEXT_INPUT"
                for phrase, name in [("complete the table", "TABLE_COMPLETION"), ("complete the notes", "NOTE_COMPLETION"), ("complete the flow", "FLOW_COMPLETION"), ("answer the questions", "SHORT_ANSWER")]:
                    if phrase in context.lower():
                        kind = name
                        break
                limit_source = word_limit_instruction(instruction, context) or instruction or context
                base.update(type="IMAGE_BASED" if image_paths and kind == "TEXT_INPUT" else kind,
                            wordLimit=input_limit(limit_source))
            base.update(prompt=context)
        else:
            raise ValueError("UNSUPPORTED_QUESTION_BLOCK")
        for number in numbers:
            require(number in answers and number in expected, "UNEXPECTED_QUESTION_NUMBER")
            entry = answers[number]
            accepted = [clean(v) for v in entry['accepted_answers'] if clean(v)]
            require(bool(accepted), "EMPTY_ACCEPTED_ANSWERS")
            question = dict(base, id=f"{part_id}-q{number}", number=number,
                            correctAnswer=accepted[0], acceptedAnswers=accepted)
            if question['options']:
                require(all(a.upper() in [o['id'] for o in question['options']] for a in accepted), "ANSWER_OPTION_MISMATCH")
                question['correctAnswer'] = accepted[0].upper()
            else:
                separator = answer_separators.get(number)
                candidates = [value for value in accepted if separator is None or response_parts(value,separator)]
                valid=[value for value in candidates if within_limit(value,question['wordLimit'])]
                if not valid and number in answer_separators:
                    separator = answer_separators[number]
                    valid = [value for value in candidates if within_limit(value,question['wordLimit'],separator)]
                    if valid:
                        question['answerSeparator'] = separator
                require(bool(valid), 'ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT')
                question['correctAnswer']=valid[0]
            result.append(question)
    require(sorted(q['number'] for q in result) == expected, "INCOMPLETE_OR_DUPLICATE_QUESTIONS")
    for group in {q['groupId'] for q in result if q['groupId']}:
        members = [q for q in result if q['groupId'] == group]
        require(len(set(q['correctAnswer'] for q in members)) == len(members)
                and all(len({a.upper() for a in q['acceptedAnswers']}) == 1 for q in members), "AMBIGUOUS_MULTI_ANSWER_GROUP")
    return sorted(result, key=lambda q: q['number']), "\n\n".join(dict.fromkeys(instructions))


def load_corrections(path):
    data = read_json(Path(path).read_text(encoding='utf-8'))
    require(data.get('schemaVersion') == 1 and isinstance(data.get('parts'),list), 'INVALID_CORRECTION_RECORD')
    require(len({r['id'] for r in data['parts']}) == len(data['parts']), 'DUPLICATE_CORRECTION_RECORD')
    return {r['id']:r for r in data['parts']}


def corrected_reader(read, record):
    require(bool(record.get('evidence')) and bool(record.get('resourceHashes')) and bool(record.get('edits')), 'INVALID_CORRECTION_RECORD')
    resources = {}
    for filename, digest in record['resourceHashes'].items():
        require(safe_name(filename) == filename and '/' not in filename, 'INVALID_CORRECTION_RESOURCE')
        raw = read(filename)
        require(sha(raw) == digest, 'CORRECTION_SOURCE_HASH_MISMATCH')
        resources[filename] = raw
    for edit in record['edits']:
        name = edit['resource']
        require(name in ('content.html','questions.txt','answers.json','part.json') and name in resources,
                'INVALID_CORRECTION_RESOURCE')
        value = text(resources[name])
        require(edit['old'] and edit['new'] and edit['count'] > 0 and value.count(edit['old']) == edit['count'],
                'CORRECTION_TEXT_MISMATCH')
        resources[name] = value.replace(edit['old'],edit['new']).encode('utf-8')
    return lambda name: resources[name] if name in resources else read(name)


def normalize(read, book, test, part, corrections=None):
    part_id = f"cambridge-{book}-test-{test}-part-{part}"
    if corrections and part_id in corrections:
        read = corrected_reader(read, corrections[part_id])
    metadata = read_json(text(read("part.json")))
    require((metadata['book'], metadata['test'], metadata['part']) == (book,test,part), "IDENTITY_MISMATCH")
    answer_data = read_json(text(read("answers.json")))
    expected = list(range((part-1)*10+1, part*10+1))
    require(answer_data['expected_questions'] == expected and answer_data['complete'] is True
            and answer_data['captured_questions'] == len(expected), "INCOMPLETE_ANSWERS")
    entries = answer_data['answers']
    require(sorted(e['question'] for e in entries) == expected and all(e['captured'] for e in entries), "ANSWER_NUMBERS_INVALID")
    require({str(e['question']):e['answer'] for e in entries} == answer_data['answer_map'] == metadata['answers']['map'], "ANSWER_MAP_CONFLICT")
    for entry in entries:
        require([v.strip() for v in entry['answer'].split(';') if v.strip()] == entry['accepted_answers'], "ACCEPTED_ANSWERS_CONFLICT")
    audio = read('audio.mp3')
    require(len(audio) == metadata['audio']['bytes'] and metadata['audio']['downloaded'] is True
            and (audio.startswith(b'ID3') or audio[:1] == b'\xff'), "INVALID_MP3")
    part_id = f"cambridge-{book}-test-{test}-part-{part}"
    html = text(read('content.html'))
    soup = BeautifulSoup(html, 'html.parser')
    section = soup.select_one('section.questions')
    require(section is not None, "MISSING_QUESTIONS_SECTION")
    # Detect text loss/disagreement without relying on questions.txt as structured data.
    canonical = lambda s: re.sub(r'\s+', '', s)
    # Compare equivalent presentation glyphs only. Keep the original HTML text
    # in models, so existing prompts, transcript keys and highlight hashes stay unchanged.
    require(canonical(visible(section).translate(TYPOGRAPHIC_LIGATURES)) ==
            canonical(text(read('questions.txt')).translate(TYPOGRAPHIC_LIGATURES)), "QUESTION_TEXT_HTML_MISMATCH")
    questions, instructions = parse_questions(html, part_id, expected, {e['question']:e for e in entries})
    require(bool(instructions), "MISSING_INSTRUCTIONS")
    segments = []
    try:
        timeline = read_json(text(read('timeline.json')))
        require(isinstance(timeline,list) and [t['sort'] for t in timeline] == list(range(1,len(timeline)+1)), "TIMELINE_SEGMENT_ORDER")
        segments = [dict(english=t.get('en',''), chinese=t.get('zh','')) for t in timeline]
    except KeyError:
        timeline = []
        raw_text = {}
        for filename in ('transcript.txt','translation.txt'):
            try:
                raw_text[filename] = text(read(filename)).strip()
            except KeyError:
                raw_text[filename] = ''
        if any(raw_text.values()):
            segments = [dict(english=raw_text['transcript.txt'],chinese=raw_text['translation.txt'])]
    english = '\n\n'.join(t['english'].strip() for t in segments if t['english'].strip())
    chinese = '\n\n'.join(t['chinese'].strip() for t in segments if t['chinese'].strip())
    for file, value in [('transcript.txt',english),('translation.txt',chinese)]:
        try:
            raw = text(read(file)).strip()
        except KeyError:
            raw = ''
        require(canonical(raw) == canonical(value), "TRANSCRIPT_SEGMENT_MISMATCH")
    images = {}
    for path in sorted({p for q in questions for p in q['images']}):
        raw = read(path)
        require(len(raw) <= 20_000_000, "IMAGE_TOO_LARGE")
        with Image.open(io.BytesIO(raw)) as image:
            require(image.width * image.height <= 32_000_000, 'IMAGE_TOO_LARGE')
            image.verify()
        images[path] = raw
    # Keep the original sample model, exact prompts, paragraph keys and hashes unchanged.
    if (book,test,part) == (9,1,3):
        model, sample_audio, _ = convert_sample(read)
        require(sample_audio == audio, "SAMPLE_AUDIO_MISMATCH")
        return model, audio, images
    return dict(schemaVersion=2, id=part_id, examType='IELTS',book=book,test=test,part=part,
                title=f'Cambridge IELTS {book} · Test {test} · Part {part}', instructions=instructions,
                audioPath=f'listening/{part_id}/audio.mp3', audioSha256=sha(audio),
                transcript=dict(english=english,chinese=chinese,segments=segments),questions=questions), audio, images


def inventory(sources, corrections=None, part_ids=None):
    records, archives = [], []
    for source in sources:
        source = Path(source)
        require(source.is_file(), 'SOURCE_ZIP_MISSING')
        archive_sha = sha(source.read_bytes())
        with zipfile.ZipFile(source) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), 'DUPLICATE_ZIP_ENTRY')
            for name in names:
                safe_name(name)
            for name in names:
                match = IDENTITY.search(name)
                if not match:
                    continue
                book,test,part = map(int,match.groups())
                require(5 <= book <= 21 and 1 <= test <= 4 and 1 <= part <= 4, 'UNEXPECTED_PART_IDENTITY')
                part_id = f'cambridge-{book}-test-{test}-part-{part}'
                if part_ids is not None and part_id not in part_ids:
                    continue
                prefix = name[:-9]
                resource_names = [n for n in names if n.startswith(prefix) and not n.endswith('/')]
                resources = {n[len(prefix):]:archive.read(n) for n in resource_names}
                record = dict(id=f'cambridge-{book}-test-{test}-part-{part}',book=book,test=test,part=part,
                              source=str(source),archiveSha256=archive_sha,prefix=prefix,
                              hashes={f:sha(raw) for f,raw in resources.items()},
                              audioBytes=len(resources.get('audio.mp3',b'')),
                              english=bool(resources.get('transcript.txt',b'').strip()),chinese=bool(resources.get('translation.txt',b'').strip()),
                              images=len([f for f in resources if f.lower().endswith(('.png','.jpg','.jpeg','.webp'))]),
                              status='pending',questionTypes={},errors=[])
                if corrections and part_id in corrections:
                    record['correctionSha256'] = sha(json.dumps(corrections[part_id],ensure_ascii=False,sort_keys=True).encode())
                try:
                    data=read_json(text(resources['answers.json']))
                    record['sourceQuestionCount']=len(data['answers'])
                    record['sourceAnswersComplete']=data.get('complete') is True
                    record['sourceQuestionTypes']=source_types(text(resources['content.html']))
                except (KeyError,ValueError,TypeError):
                    record['sourceQuestionCount']=0
                    record['sourceAnswersComplete']=False
                    record['sourceQuestionTypes']={}
                try:
                    model,audio,images = normalize(resources.__getitem__,book,test,part,corrections)
                    record['questionTypes']=dict(Counter(q['type'] for q in model['questions']))
                    record['questions']=len(model['questions'])
                    record['semanticSha256']=sha(json.dumps(model,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()+audio+b''.join(images[p] for p in sorted(images)))
                    record['status']='validated'
                except (ValueError,KeyError,TypeError,UnicodeError,OSError) as error:
                    # Do not write exception values containing captured exam content.
                    record['errors']=[str(error) if isinstance(error,ValueError) and re.fullmatch('[A-Z_]+',str(error)) else type(error).__name__]
                    try:
                        html=BeautifulSoup(text(resources['content.html']),'html.parser')
                        for kind,selector in [('SINGLE_CHOICE',"[id^='titleNum']"),('MULTIPLE_CHOICE',"[class*='multiple-choice__']"),('GAP_OR_MATCHING',"[class*='gap-filing__']")]:
                            record['questionTypes'][kind]=len(html.select('section.questions '+selector))
                    except (KeyError,ValueError):
                        pass
                records.append(record)
        archives.append(dict(path=str(source),sha256=archive_sha,zipBytes=source.stat().st_size))
    grouped=defaultdict(list)
    for record in records:
        grouped[record['id']].append(record)
    if part_ids is not None:
        require(set(grouped) == set(part_ids), 'REQUESTED_PART_MISSING')
    duplicates=[]
    for part_id,copies in grouped.items():
        if len(copies)<2:
            continue
        semantic = [r.get('semanticSha256') for r in copies]
        equal = all(semantic) and len(set(semantic))==1
        if not all(semantic):
            # A parser limitation is not a source conflict. Even pending duplicates
            # can be proven equal by canonical answers/timing and exact content files.
            fingerprints=[]
            for copy in copies:
                with zipfile.ZipFile(copy['source']) as archive:
                    a=read_json(text(archive.read(copy['prefix']+'answers.json')))
                    t=read_json(text(archive.read(copy['prefix']+'timeline.json')))
                    payload=dict(answers=[(e['question'],e['answer'],e['accepted_answers']) for e in a['answers']],
                                 timeline=[(s['en'],s['zh'],s['start_ms'],s['end_ms']) for s in t],
                                 resources={f:copy['hashes'].get(f) for f in ('questions.txt','audio.mp3','transcript.txt','translation.txt','bilingual.txt')},
                                 images={f:h for f,h in copy['hashes'].items() if f.endswith(('.png','.jpg','.jpeg','.webp'))})
                    fingerprints.append(sha(json.dumps(payload,sort_keys=True,ensure_ascii=False).encode()))
            equal=len(set(fingerprints))==1
        duplicates.append(dict(id=part_id,equivalent=equal,sources=[r['source'] for r in copies],
                               changedResources=sorted({f for r in copies for f in r['hashes'] if len({c['hashes'].get(f) for c in copies})>1})))
        if equal:
            for record in copies[1:]:
                record['status']='duplicate'
        else:
            for record in copies:
                record['status']='conflict';record['errors'].append('DUPLICATE_CONTENT_CONFLICT')
    validated=[r for r in records if r['status']=='validated']
    total_types=Counter()
    for record in validated:
        total_types.update(record['questionTypes'])
    summary=dict(archives=len(archives),sourceParts=len(records),uniqueCandidateParts=len(grouped),
                 validatedParts=len(validated),duplicateParts=sum(r['status']=='duplicate' for r in records),
                 failedOrConflictingParts=len({r['id'] for r in records if r['status'] in ('pending','conflict')}),
                 books=sorted({r['book'] for r in validated}),tests=len({(r['book'],r['test']) for r in validated}),
                 questions=sum(r.get('questions',0) for r in validated),questionTypes=dict(total_types),
                 sourceAudioBytes=sum(r['audioBytes'] for r in records),
                 usableAudioBytes=sum(r['audioBytes'] for r in validated),usableAudioCount=len(validated),
                 englishParts=sum(r['english'] for r in validated),chineseParts=sum(r['chinese'] for r in validated),
                 sourceImageCount=sum(r['images'] for r in records))
    return dict(summary=summary,archives=archives,parts=records,duplicates=duplicates)


def entry(model,audio,images):
    return dict(id=model['id'],book=model['book'],test=model['test'],part=model['part'],title=model['title'],
                questionCount=len(model['questions']),firstNumber=min(q['number'] for q in model['questions']),
                lastNumber=max(q['number'] for q in model['questions']),audioBytes=len(audio),
                partSha256=sha(json.dumps(model,ensure_ascii=False,indent=2).encode()+b'\n'),
                audioSha256=sha(audio),imageHashes={p:sha(raw) for p,raw in images.items()})


def materialize(report, output, representatives=False, corrections=None):
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    grouped=defaultdict(list)
    for record in report['parts']:
        if record['status']=='validated' and (not representatives or record['book'] in (5,9,13,17,18,21)):
            grouped[record['book']].append(record)
    if representatives:
        for book, records in grouped.items():
            grouped[book]=[next(r for r in sorted(records,key=lambda r:r['test']) if r['part']==part) for part in range(1,5)]
    for book,records in grouped.items():
        directory=output/'books'/f'cambridge-{book}'
        directory.mkdir(parents=True,exist_ok=True)
        entries=[]
        for record in records:
            with zipfile.ZipFile(record['source']) as archive:
                model,audio,images=normalize(lambda f:archive.read(record['prefix']+f),record['book'],record['test'],record['part'],corrections)
            destination=directory/'listening'/model['id'];destination.mkdir(parents=True,exist_ok=True)
            resources=dict(images,**{'part.json':json.dumps(model,ensure_ascii=False,indent=2).encode()+b'\n','audio.mp3':audio})
            for filename,raw in resources.items():
                target=destination/filename
                require(not target.exists() or target.read_bytes()==raw,'EXISTING_RESOURCE_CONFLICT')
                if not target.exists():
                    target.write_bytes(raw)
            entries.append(entry(model,audio,images))
        index=dict(schemaVersion=1,book=book,parts=entries)
        (directory/'index.json').write_text(json.dumps(index,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        pack=output/f'cambridge-{book}.eelpack'
        with tempfile.NamedTemporaryFile(dir=output,suffix='.tmp',delete=False) as temporary:
            temp_path=Path(temporary.name)
        try:
            with zipfile.ZipFile(temp_path,'w') as archive:
                archive.writestr(zipfile.ZipInfo('index.json'),(directory/'index.json').read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
                for item in entries:
                    for resource in (directory/'listening'/item['id']).iterdir():
                        archive.writestr(zipfile.ZipInfo(resource.relative_to(directory).as_posix()),resource.read_bytes(),compress_type=zipfile.ZIP_STORED if resource.suffix=='.mp3' else zipfile.ZIP_DEFLATED)
            temp_path.replace(pack)
        finally:
            temp_path.unlink(missing_ok=True)
    return sum(len(records) for records in grouped.values())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sources',nargs='+')
    parser.add_argument('--report',default='private-data/library/audit.json')
    parser.add_argument('--output',default='private-data/library')
    parser.add_argument('--audit-only',action='store_true')
    parser.add_argument('--representatives',action='store_true')
    parser.add_argument('--corrections', help='Private, evidence-backed corrections with exact source resource hashes')
    parser.add_argument('--part', action='append', help='Only read this Part ID; repeat for a targeted import')
    args=parser.parse_args()
    corrections = load_corrections(args.corrections) if args.corrections else None
    report=inventory(args.sources,corrections,set(args.part) if args.part else None)
    path=Path(args.report);path.parent.mkdir(parents=True,exist_ok=True)
    if not args.audit_only:
        report['summary']['materializedParts']=materialize(report,args.output,args.representatives,corrections)
    path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report['summary'],ensure_ascii=True))


if __name__=='__main__':
    main()
