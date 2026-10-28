"""Bind reviewed display-only number cuts to exact V4 text and fill controls.

Input is the private audit inventory, not a new runtime number heuristic. Output
contains identifiers, hashes and UTF-16 offsets only; packs remain untouched.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


# This row's inherited source range points to the earlier age 25. The original
# music-preferences blank and the layout's fill binding were checked separately.
REVIEWED_SOURCE_EXCEPTIONS = {
    ('cambridge-6-test-3-part-3', 'b-g2-music-q25-text', 25),
}


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def build_catalog(audit: dict, packs: Path) -> dict:
    reviewed = [r for r in audit['records'] if not r['covered']]
    by_part = {}
    entries = []
    seen = set()
    for record in reviewed:
        part_id = record['part']
        if part_id not in by_part:
            with zipfile.ZipFile(packs / f"cambridge-{record['book']}-v4-test.eelpack") as z:
                base = f'listening/{part_id}/'
                by_part[part_id] = (json.loads(z.read(base+'layout.json')),
                                    json.loads(z.read(base+'part.json')))
        layout, part = by_part[part_id]
        group = next(g for g in layout['groups'] if g['groupId'] == record['group'])
        slot = next(s for s in group['slots'] if s['slotId'] == record['slot'])
        question = next(q for q in part['questions'] if q['id'] == slot['questionId'])
        block = next(b for b in walk(group['blocks']) if b.get('id') == record['block'])
        text = block['text']
        start, end = record['numberStart'], record['numberEnd']
        exception = (part_id, record['block'], record['question'])
        if (not record['sourceMatched'] or not (record['sourceBold'] or exception in REVIEWED_SOURCE_EXCEPTIONS)
                or slot['kind'] != 'fill' or question['number'] != record['question']
                or text != record['text'] or not (0 <= start < end <= len(text))
                or text[start:end] != str(question['number'])):
            raise ValueError('REVIEW_BINDING_MISMATCH')
        attached = False
        for row in walk(group['blocks']):
            children = row.get('children', [])
            for i, child in enumerate(children):
                if child.get('type') != 'slot' or child.get('slotId') != slot['slotId']:
                    continue
                for preceding in reversed(children[:i]):
                    if preceding.get('type') != 'text':
                        break
                    if preceding.get('id') == block['id']:
                        attached = True
        if not attached:
            raise ValueError('REVIEW_SLOT_POSITION_MISMATCH')
        key = (group['groupId'], block['id'], start, end)
        if key in seen:
            raise ValueError('DUPLICATE_REVIEW_CUT')
        seen.add(key)
        entries.append(dict(groupId=group['groupId'], blockId=block['id'], slotId=slot['slotId'],
                            questionId=question['id'], questionNumber=question['number'],
                            textSha256=hashlib.sha256(text.encode('utf-8')).hexdigest(),
                            startUtf16=len(text[:start].encode('utf-16-le'))//2,
                            endUtf16=len(text[:end].encode('utf-16-le'))//2))
    return {'schemaVersion': 1, 'entries': sorted(entries, key=lambda x: (x['groupId'], x['blockId'], x['startUtf16']))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--packs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    catalog = build_catalog(json.loads(args.audit.read_text(encoding='utf-8')), args.packs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalog, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8')
    print(json.dumps({'entries':len(catalog['entries']), 'groups':len({x['groupId'] for x in catalog['entries']})}))


if __name__ == '__main__':
    main()
