"""Check indexed paths and local text for accidental private asset publication."""
import json
import re
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
paths = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout.decode().split("\0")
for name in filter(None, paths):
    path = Path(name)
    if (any(part in {".local", "build", "artifacts", "sample-data", "private-data", "__pycache__"} for part in path.parts)
            or name.startswith("app/src/main/assets/listening/")
            or name.startswith("app/src/androidTest/assets/private-library/")
            or path.suffix.lower() in {".mp3", ".mp4", ".wav", ".ogg", ".zip", ".eelpack", ".apk", ".aab", ".db"}):
        raise SystemExit("FAIL: private resource or build artifact indexed: " + name)

fragments = []
private_parts = list((root / "app/src/main/assets/listening").glob("*/part.json"))
private_parts += list((root / "private-data/library/batch/books").glob("*/listening/*/part.json"))
for file in private_parts:
    part = json.loads(file.read_text(encoding="utf-8"))
    fragments += [q["prompt"] for q in part["questions"]]
    # Very short options can be ordinary interface/build phrases; scan longer
    # text fragments alongside the unconditional resource-path checks above.
    fragments += [o["text"] for q in part["questions"] for o in q["options"] if len(o["text"]) >= 20]
    fragments += [text for segment in part["transcript"]["segments"] for text in segment.values() if len(text) >= 20]
checked = 0
for name in filter(None, paths):
    file = root / name
    if file.suffix.lower() in {".png", ".jar"}:
        continue
    try:
        text = file.read_text(encoding="utf-8-sig")
    except (UnicodeError, IsADirectoryError):
        continue
    # Whole token boundaries avoid treating an ordinary suffix (for example an
    # option word inside a documentation word) as a leaked exam quotation.
    if any(fragment in text and re.search(r"(?<!\w)" + re.escape(fragment) + r"(?!\w)", text)
           for fragment in fragments):
        raise SystemExit("FAIL: possible copyrighted text in indexed file: " + name)
    checked += 1
print(json.dumps({"indexedTextFilesChecked": checked, "privateFragmentsChecked": len(fragments),
                  "privateAssetsOrExamTextIndexed": False}))
