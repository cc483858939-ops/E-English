"""Bind source timings to existing pack audio and transcript hashes; never rewrite exam content."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import zipfile
from contextlib import ExitStack
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCES = [Path('D:/IELTS-Listening-Cambridge-5-13_已修复.zip'),
                   Path('D:/IELTS-Listening-Cambridge-13-17.zip'), Path('D:/IELTS-Listening-Cambridge-18-21.zip')]

def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def build(packs: Path, sources: list[Path]) -> tuple[dict, dict]:
    parts, excluded, overlap_parts = [], [], []
    with ExitStack() as stack:
        archives = [stack.enter_context(zipfile.ZipFile(p)) for p in sources]
        candidates = {}
        for archive in archives:
            for name in archive.namelist():
                match = re.search(r'Cambridge-(\d+)/Test([1-4])/Part([1-4])/timeline\.json$', name)
                if match:
                    candidates.setdefault(tuple(map(int, match.groups())), []).append((archive, name.removesuffix('timeline.json')))
        for pack_path in sorted(packs.glob('*.eelpack')):
            with zipfile.ZipFile(pack_path) as pack:
                for name in sorted(n for n in pack.namelist() if n.endswith('/part.json')):
                    part = json.loads(pack.read(name))
                    bound = None
                    for archive, prefix in candidates.get((part['book'], part['test'], part['part']), []):
                        source = json.loads(archive.read(prefix + 'timeline.json').decode('utf-8-sig'))
                        expected = [(s['english'], s['chinese']) for s in part['transcript']['segments']]
                        if expected != [(s.get('en', ''), s.get('zh', '')) for s in source]:
                            continue
                        if digest(archive.read(prefix + 'audio.mp3')) != part['audioSha256']:
                            continue
                        cues = []
                        previous_end = 0
                        previous_start = 0
                        overlaps = False
                        valid = True
                        for row in source:
                            if re.fullmatch(r'(?:PART|SECTION)\s*[1-4]', row.get('en', '').strip(), re.I) or not (row.get('en', '').strip() or row.get('zh', '').strip()):
                                cues.append(None)
                                continue
                            start, end = row['start_ms'], row['end_ms']
                            if not (isinstance(start, int) and isinstance(end, int) and 0 <= previous_start <= start < end and previous_end <= end):
                                valid = False
                                break
                            cues.append(dict(startMs=start, endMs=end))
                            overlaps |= start < previous_end
                            previous_start = start
                            previous_end = end
                        if valid and any(cues):
                            text = '\x01'.join(en + '\x00' + zh for en, zh in expected)
                            bound = dict(partId=part['id'], audioSha256=part['audioSha256'],
                                         transcriptSha256=digest(text.encode('utf-8')), segments=cues)
                            if overlaps:
                                overlap_parts.append(part['id'])
                            break
                    if bound:
                        parts.append(bound)
                    else:
                        excluded.append(part['id'])
    parts.sort(key=lambda p: p['partId'])
    return dict(schemaVersion=1, parts=parts), dict(boundParts=len(parts), excludedParts=excluded, overlapParts=overlap_parts)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--packs', type=Path, default=ROOT/'private-data/layout-v4-android-test-packs')
    parser.add_argument('--sources', type=Path, nargs='+', default=DEFAULT_SOURCES)
    parser.add_argument('--output', type=Path, default=ROOT/'app/src/main/assets/transcript-timelines.json')
    args = parser.parse_args()
    catalog, report = build(args.packs, args.sources)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalog, ensure_ascii=True, separators=(',', ':'))+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True))

if __name__ == '__main__':
    main()
