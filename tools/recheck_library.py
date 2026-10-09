"""Recheck local book packs against source archives. Exam text is never printed."""
import argparse
from collections import Counter
import json
from pathlib import Path
import zipfile

from import_library import normalize, entry, require, sha


def verify(report, directory):
    directory = Path(directory)
    validated = [p for p in report['parts'] if p['status'] == 'validated']
    expected_books = sorted({p['book'] for p in validated})
    require({p.name for p in directory.glob('*.eelpack')} ==
            {f'cambridge-{book}.eelpack' for book in expected_books}, 'PACK_SET_MISMATCH')
    results = []
    for book in expected_books:
        path = directory / f'cambridge-{book}.eelpack'
        with zipfile.ZipFile(path) as pack:
            require(pack.testzip() is None, 'PACK_CRC_ERROR')
            names = pack.namelist()
            require(len(names) == len(set(names)), 'DUPLICATE_PACK_ENTRY')
            index = json.loads(pack.read('index.json'))
            require(index['schemaVersion'] == 1 and index['book'] == book, 'PACK_IDENTITY_MISMATCH')
            expected = [p for p in validated if p['book'] == book]
            require({p['id'] for p in index['parts']} == {p['id'] for p in expected}
                    and len(index['parts']) == len(expected), 'PACK_PART_SET_MISMATCH')
            expected_names = {'index.json'}
            images = audio_bytes = question_count = 0
            types = Counter()
            for record in expected:
                with zipfile.ZipFile(record['source']) as source:
                    model, audio, pictures = normalize(
                        lambda name: source.read(record['prefix'] + name),
                        record['book'], record['test'], record['part'])
                item = next(p for p in index['parts'] if p['id'] == model['id'])
                require(item == entry(model, audio, pictures), 'SOURCE_INDEX_MISMATCH')
                resources = dict(pictures, **{'audio.mp3': audio,
                    'part.json': json.dumps(model, ensure_ascii=False, indent=2).encode() + b'\n'})
                for name, raw in resources.items():
                    packed_name = f"listening/{model['id']}/{name}"
                    require(pack.read(packed_name) == raw, 'SOURCE_PACK_RESOURCE_MISMATCH')
                    require((directory / 'books' / f'cambridge-{book}' / packed_name).read_bytes() == raw,
                            'UNPACKED_RESOURCE_MISMATCH')
                    expected_names.add(packed_name)
                require(pack.read('index.json') ==
                        (directory / 'books' / f'cambridge-{book}' / 'index.json').read_bytes(),
                        'UNPACKED_INDEX_MISMATCH')
                images += len(pictures)
                audio_bytes += len(audio)
                question_count += len(model['questions'])
                types.update(q['type'] for q in model['questions'])
            require(set(names) == expected_names, 'UNDECLARED_PACK_RESOURCE')
            results.append(dict(book=book, parts=len(expected), questions=question_count,
                                audioBytes=audio_bytes, images=images, questionTypes=dict(types),
                                packBytes=path.stat().st_size, sha256=sha(path.read_bytes())))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', default='private-data/library/recheck-audit.json')
    parser.add_argument('--packs', default='private-data/library/rechecked-batch')
    parser.add_argument('--report', default='private-data/library/recheck-integrity.json')
    args = parser.parse_args()
    audit = json.loads(Path(args.audit).read_text(encoding='utf-8'))
    for source in audit['archives']:
        # Original hashes and structure are also rechecked by import_library.py.
        with zipfile.ZipFile(source['path']) as archive:
            require(archive.testzip() is None, 'SOURCE_CRC_ERROR')
    books = verify(audit, args.packs)
    result = dict(books=books, parts=sum(b['parts'] for b in books),
                  questions=sum(b['questions'] for b in books),
                  audioBytes=sum(b['audioBytes'] for b in books),
                  images=sum(b['images'] for b in books), pendingParts=audit['summary']['failedOrConflictingParts'])
    Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'books'}, ensure_ascii=True))


if __name__ == '__main__':
    main()
