# Six pending Parts: targeted recovery

Date: 2026-10-09. Only the six pending Parts were read from the original Cambridge 5–13 archive. Original archives remain unchanged. Detailed source text, answers, image evidence and resource hashes remain in ignored private files.

## Exact causes and evidence

The original import failure required at least one accepted answer to satisfy `within_limit()`. For five questions it counted the site's fixed joining word as user input. These sources explicitly describe two blanks joined by fixed text; the original answer records serialize both fields and that connector into one string. No answer alternatives were invented or split apart.

| Part ID | Failing questions | Classification and correction | Restored |
| --- | --- | --- | --- |
| cambridge-6-test-1-part-1 | 7 | A: two-number limit; explicit two-blank `to` template in HTML/text and image. Count the two filled fields; recognize clock numbers with attached, spaced or dotted AM/PM. | Yes, 10 questions |
| cambridge-6-test-1-part-2 | 19 | A: two words and/or a number; explicit `and` template and table image with two blanks. Fixed connector is not an additional filled word. | Yes, 10 questions |
| cambridge-6-test-1-part-4 | 32 | A: at most two words; explicit `and` template and table image with two blanks. Count only the filled fields. | Yes, 10 questions |
| cambridge-6-test-3-part-4 | 36 | A: at most two words; matching HTML/text explicitly specify two blanks joined by `and`. The supplied map is unrelated to these blanks and is not used as evidence for them. | Yes, 10 questions |
| cambridge-9-test-1-part-4 | 33 | A: at most two words; matching HTML/text explicitly specify two blanks joined by `or`. Preserve each full paired accepted answer; neither side becomes an independent accepted answer. | Yes, 10 questions |
| cambridge-11-test-1-part-2 | 15–20 | B: HTML and questions.txt both print an uppercase A followed by lowercase ell as the range. The original map visibly labels A–I; all answer letters are within that range. A private hash-bound record corrects only the two instruction strings to A–I. | Yes, 10 questions |

For the map, `INVALID_MATCHING_RANGE` came from the uppercase range regex failing to match the lowercase ell, followed by fewer than two explicit option labels. It was not caused by emphasized labels being read as options. There is no global ell-to-I substitution. The map correction verifies original resource hashes, exact old text and replacement count before applying a virtual read; it never writes the ZIP. This is the only source correction needed; no correct-answer file was edited. No category C ambiguity remains among these six after the above checks.

## Minimal compatible changes

- Optional `Question.answerSeparator` is emitted only for an explicitly documented paired answer that needs it. `and`, `or` and `to` are supported; no connector is inferred from an answer. Existing Part schema 2 and BookIndex schema 1 remain.
- Python and Kotlin use the same 28 synthetic limit vectors. Ordinary answers still count conjunctions as words. Empty, incomplete, repeated-connector and over-limit paired answers remain invalid. Slash notation is not automatically expanded into new alternatives.
- A paired question renders two editable fields with fixed text between them. It still saves one serialized answer through the existing callback, PracticeViewModel and Room repository. A half-filled pair counts as unanswered; submitted fields remain read-only.
- Room remains version 2. No new migration, table or destructive reset. Old question snapshots default to a null separator. Player, annotations, scoring history and import publication mechanisms are retained.

The importer accepts repeatable `--part` filters and an optional `--corrections` JSON file. Filtering happens before reading Part resources. The correction format uses `schemaVersion: 1` and a `parts` list; each record contains `id`, `resourceHashes`, `evidence` and exact `edits` (`resource`, `old`, `new`, `count`). Correction files containing source details stay private. For example:

```powershell
python tools/import_library.py 'D:/IELTS-Listening-Cambridge-5-13_已修复.zip' --part cambridge-11-test-1-part-2 --corrections private-data/library/pending-six/corrections-private.json --output private-data/targeted-check --report private-data/targeted-check.json
```

## Private data delivery and preservation

Actual result: **17 books, 68 complete Tests, 272 Parts, 2720 questions**, no pending Parts. There are 272 usable MP3s totaling **1,002,367,192 bytes**, 87 image files, and English/Chinese transcripts in 272/272 Parts each. Only books 6, 9 and 11 need updated packages. The other 14 packages are unchanged.

All original 266 Part JSON files, existing index summaries, audio and image bytes were compared against the new packages and are unchanged. Thus old Part IDs, question IDs, paragraph keys and content hashes remain unchanged. The existing importer accepts these expanded same-book packages while rejecting conflicting old content; repeat import adds nothing. Nothing is bundled into the APK or uploaded publicly.

Local ignored outputs:

- `private-data/library/pending-six/updated-library/cambridge-N.eelpack`: complete current set of 17 packages; transfer only books 6, 9 and 11 to update an existing library.
- `private-data/library/pending-six/normalized/`: the six validated new Parts.
- `private-data/library/pending-six/inspection-private.json`: original per-question evidence and accepted-answer analysis.
- `private-data/library/pending-six/corrections-private.json`: original hashes and map correction evidence.
- `private-data/library/pending-six/targeted-audit.json`, `updated-audit.json`, `updated-integrity.json`: targeted results, combined status/provenance and package integrity.

Earlier `rechecked-batch` outputs and the earlier 266-Part reports remain as the unchanged baseline, not the latest delivery.

## Executed verification

| Check | Actual result |
| --- | --- |
| Python importer/sample/integrity tests | 30 passed, 0 failures, 0 skipped |
| `testDebugUnitTest` | 36 passed, 0 failures, 0 skipped; includes all 266 baseline canonical grades, six restored Parts and all supplied variants of the five paired answers |
| `assembleDebug`, `assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| API 36 emulator: six selected Android test classes | 12 passed, 0 failures, 0 skipped |
| Recovery test rerun with explicitly enabled production-library update | 1 passed, 0 failures, 0 skipped; ordinary emulator library expanded from 266 to 272 Parts |
| Ordinary application restart and list navigation | PASS: production catalogue has 17 books / 272 unique Parts / 2720 questions; Cambridge 6 → Test 1 → newly restored Part 4 is visible after scrolling |

The targeted device test streams the old/new book 6, 9 and 11 packages into an isolated directory: 42 old Parts become 48. It checks old JSON hashes, duplicate import, immediate list refresh and all six new Parts. Five paired questions are answered by typing into both real fields; the new map and every A–I option are displayed and a radio option is selected. All sixty numbered questions persist and score 10/10 across the six submissions; subsequent edits to submitted attempts are rejected. Every new image decodes, English/Chinese texts load, and a single controller exercises each of the six new MP3s through ready/play/advancing position/pause/seek.

An isolated Room database contains an old submitted sample and highlight before import. They remain equal after new submissions and a database close/reopen. After this passed, the existing production importer installed only the three expanded packages on the development emulator. Its original `practice.db` SHA-256 remained identical before/after, and every old Part summary remained equal. No database was cleared. APK updates used `adb install -r`; instrumented tests ran directly through the AndroidJUnitRunner to avoid the Gradle runner's application uninstall cleanup.

The scope is these six Parts plus existing importer/type regressions; the earlier 266 recordings are not replayed. Initial synthetic-fixture/temporary-directory test errors were corrected and rerun; none are counted as successful checks. Logs stay local: `artifacts/pending-six-python.log`, `pending-six-build.log`, `pending-six-device-build.log`, `pending-six-device.log` and `pending-six-install-device.log`.

Physical-phone operation, API 26 runtime and speaker/headphone listening quality remain NOT VERIFIED. Emulator playback checks exercise decoding, playback position, pause and seek, not the entire duration of each recording. No APK or public Release is delivered by this task.
