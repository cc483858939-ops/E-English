"""Strict, repeatable importer for the one user-supplied Cambridge 9 sample.

Copyrighted input and output must stay in ignored local directories. No HTML is
executed. Unsupported or ambiguous input fails before any output is replaced.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

PREFIX = "sample-data/Cambridge-9/Test1/Part3/"
NUMBERS = list(range(21, 31))
PART_ID = "cambridge-9-test-1-part-3"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate JSON key")
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=pairs)


def parse_questions(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    require(lines[:2] == ["Questions 21-30", "Choose the correct letter, A, B or C."],
            "Unsupported question instructions")
    require(len(lines) > 2 and lines[2] == "Course Feedback", "Unsupported question heading")
    questions = []
    for line in lines[3:]:
        prompt = re.fullmatch(r"(\d+)\s+(.+)", line)
        option = re.fullmatch(r"([ABC])\.\s+(.+)", line)
        if prompt:
            number = int(prompt[1])
            questions.append(dict(id=f"{PART_ID}-q{number}", number=number,
                                  type="SINGLE_CHOICE", prompt=prompt[2], options=[]))
        elif option and questions:
            questions[-1]["options"].append(dict(id=option[1], text=option[2]))
        else:
            raise ValueError("Ambiguous question/option line; multiline format needs an explicit parser")
    require([q["number"] for q in questions] == NUMBERS, "Questions must be exactly 21–30, in order")
    for q in questions:
        require([o["id"] for o in q["options"]] == list("ABC"),
                f"Question {q['number']}: missing, duplicated or unordered options")
    return questions


def parse_answers(data, metadata, questions):
    require(data["expected_questions"] == NUMBERS and data["complete"] is True
            and data["captured_questions"] == 10, "Incomplete answer extraction")
    entries = data["answers"]
    require([a["question"] for a in entries] == NUMBERS, "Duplicate/missing answer numbers")
    answer_map = {}
    for q, answer in zip(questions, entries):
        value = answer["answer"]
        require(answer["captured"] is True and answer["accepted_answers"] == [value],
                f"Question {q['number']}: ambiguous accepted answers")
        require(value in [o["id"] for o in q["options"]], "Answer has no corresponding option")
        answer_map[str(q["number"])] = value
        q["correctAnswer"] = value
    require(data["answer_map"] == answer_map == metadata["answers"]["map"],
            "Answer maps disagree across source files")
    require(metadata["answers"]["complete"] is True
            and metadata["answers"]["captured_questions"] == 10, "Metadata answer count mismatch")
    require(data["parser_version"] == metadata["answers"]["parser_version"], "Answer parser version mismatch")
    sources = data["parse_sources"]
    require([s["question"] for s in sources] == NUMBERS, "Answer provenance numbers mismatch")
    for s in sources:
        require(s["source"] == "style_correct"
                and s["text"].strip() == f"{s['question']}. {answer_map[str(s['question'])]}",
                "Answer provenance disagrees")


def nonblank(text):
    return [line.strip() for line in text.splitlines() if line.strip()]


def convert(read):
    metadata = read_json(read("part.json").decode("utf-8-sig"))
    require((metadata["book"], metadata["test"], metadata["part"]) == (9, 1, 3), "Wrong sample identity")
    require(metadata["audio"]["file"] == "audio.mp3" and metadata["audio"]["downloaded"] is True,
            "Missing local audio metadata")
    audio = read("audio.mp3")
    require(len(audio) == metadata["audio"]["bytes"] and len(audio) > 0, "Audio byte count mismatch")
    require(audio.startswith(b"ID3") or audio[:1] == b"\xff", "Not an MP3 stream")
    questions = parse_questions(read("questions.txt").decode("utf-8-sig"))
    answers = read_json(read("answers.json").decode("utf-8-sig"))
    parse_answers(answers, metadata, questions)
    timeline = read_json(read("timeline.json").decode("utf-8-sig"))
    require(isinstance(timeline, list) and len(timeline) == metadata["timeline"]["count"],
            "Transcript segment count mismatch")
    require([t["sort"] for t in timeline] == list(range(1, len(timeline) + 1)), "Transcript segment order mismatch")
    english = read("transcript.txt").decode("utf-8-sig").strip()
    chinese = read("translation.txt").decode("utf-8-sig").strip()
    bilingual = read("bilingual.txt").decode("utf-8-sig")
    require(nonblank(english) == [t["en"].strip() for t in timeline if t["en"].strip()],
            "English transcript disagrees with segments")
    require(nonblank(chinese) == [t["zh"].strip() for t in timeline if t["zh"].strip()],
            "Chinese transcript disagrees with segments")
    without_times = [l for l in nonblank(bilingual) if not re.fullmatch(r"\[\d\d:\d\d\.\d{3} - \d\d:\d\d\.\d{3}\]", l)]
    expected = [f"{language}: {t[key].strip()}" for t in timeline
                for language, key in [("EN", "en"), ("ZH", "zh")] if t[key].strip()]
    require(without_times == expected, "Bilingual transcript disagrees with source segments")
    warnings = list(metadata.get("quality_warnings", []))
    # The captured analysis blocks contain a login gate, not an explanation.
    require("snapshot_html" in answers, "Missing answer extraction snapshot")
    if "style_islogin" in answers["snapshot_html"]:
        warnings.append("EXPLANATIONS_NOT_PRESENT_LOGIN_GATED_IN_SOURCE")
    result = dict(schemaVersion=1, id=PART_ID, examType="IELTS", book=9, test=1, part=3,
                  title="Cambridge IELTS 9 · Test 1 · Part 3", instructions="Choose the correct letter, A, B or C.",
                  audioPath=f"listening/{PART_ID}/audio.mp3", audioSha256=hashlib.sha256(audio).hexdigest(),
                  transcript=dict(english=english, chinese=chinese,
                                  segments=[dict(english=t["en"], chinese=t["zh"]) for t in timeline]),
                  questions=questions)
    return result, audio, warnings


def import_sample(source, output):
    source = Path(source)
    if source.is_dir():
        result, audio, warnings = convert(lambda name: (source / name).read_bytes())
    else:
        with zipfile.ZipFile(source) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "Duplicate ZIP entry")
            result, audio, warnings = convert(lambda name: archive.read(PREFIX + name))
    destination = Path(output) / PART_ID
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temp:
        temp = Path(temp)
        (temp / "part.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (temp / "audio.mp3").write_bytes(audio)
        destination.mkdir(exist_ok=True)
        for name in ["part.json", "audio.mp3"]:
            shutil.copyfile(temp / name, destination / name)
    return dict(partId=PART_ID, questions=10, numbers=NUMBERS, optionsPerQuestion=3,
                audioBytes=len(audio), warnings=warnings)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="Original ZIP or extracted Part3 directory")
    parser.add_argument("--output", default="app/src/main/assets/listening")
    args = parser.parse_args()
    try:
        print(json.dumps(import_sample(args.source, args.output), ensure_ascii=True))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, UnicodeError) as error:
        # Never dump source content or standard answers into public logs.
        parser.exit(1, f"Import validation failed: {type(error).__name__}: {error}\n")
