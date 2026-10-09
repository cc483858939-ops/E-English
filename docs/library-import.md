> Updated after the source recheck. Latest full-library device results and deployment are recorded in [library-recheck.md](library-recheck.md).

# Offline library import and validation

Date: 2026-10-08. This iteration delivers source, conversion tools and local private data packs; no public APK or Release.

## Actual delivery

- 17 books (Cambridge 5–21), 68 Tests represented, 266 imported Parts, 2660 numbered questions. 6 candidate Parts are explicitly pending, so 68 represented Tests does not mean all 68 have four complete Parts.
- 266 MP3 files: 994,047,953 bytes (994.05 MB / 948.00 MiB). English and Chinese transcript segment reconstruction passed for all 266 imported Parts (100% of imported Parts).
- 17 .eelpack files total 1,003,025,011 bytes. Largest individual book pack: 156,895,619 bytes. All pack metadata, audio and image hashes were checked after generation. Original source ZIP hashes are unchanged.
- Local packs: `private-data/library/rechecked-batch/cambridge-N.eelpack`; unpacked source-independent normalized data: `private-data/library/rechecked-batch/books/`; full per-Part success/failure/duplicate provenance: `private-data/library/recheck-audit.json`; original-stage CSV inventory: `private-data/library/part-inventory.csv` (superseded for status/counts by recheck-audit.json); pack hashes: `private-data/library/recheck-integrity.json`.
- The three input ZIPs remain at the explicitly supplied D:/ paths. The requested separate listening-sources directory did not exist; no source was silently substituted.

| Book | Tests represented | Parts imported | Questions | MP3 bytes |
| --- | ---: | ---: | ---: | ---: |
| 5 | 4 | 16 | 160 | 23,341,437 |
| 6 | 4 | 12 | 120 | 16,081,139 |
| 7 | 4 | 16 | 160 | 23,005,615 |
| 8 | 4 | 16 | 160 | 23,341,224 |
| 9 | 4 | 15 | 150 | 22,244,704 |
| 10 | 4 | 16 | 160 | 23,911,540 |
| 11 | 4 | 15 | 150 | 24,961,940 |
| 12 | 4 | 16 | 160 | 26,916,128 |
| 13 | 4 | 16 | 160 | 25,450,973 |
| 14 | 4 | 16 | 160 | 26,726,587 |
| 15 | 4 | 16 | 160 | 115,169,905 |
| 16 | 4 | 16 | 160 | 101,668,775 |
| 17 | 4 | 16 | 160 | 100,770,569 |
| 18 | 4 | 16 | 160 | 49,638,454 |
| 19 | 4 | 16 | 160 | 85,742,206 |
| 20 | 4 | 16 | 160 | 156,307,748 |
| 21 | 4 | 16 | 160 | 148,769,009 |

## Question types

| Type | Numbered slots | Support |
| --- | ---: | --- |
| FLOW_COMPLETION | 10 | Supported for validated source format |
| IMAGE_BASED | 166 | Supported for validated source format |
| MATCHING | 350 | Supported for validated source format |
| MULTIPLE_CHOICE | 221 | Supported for validated source format |
| NOTE_COMPLETION | 1003 | Supported for validated source format |
| SHORT_ANSWER | 13 | Supported for validated source format |
| SINGLE_CHOICE | 463 | Supported for validated source format |
| TABLE_COMPLETION | 241 | Supported for validated source format |
| TEXT_INPUT | 193 | Supported for validated source format |

428 numbered slots reference images; these overlap the table, flow, note, single-choice and IMAGE_BASED categories rather than adding to the question total. IMAGE_BASED covers map/plan/diagram variants; tables and flow charts retain their completion type and local picture.

## Formats and safety

- Part schema 1 remains compatible. Part schema 2 adds optional acceptedAnswers, instructions, context, local images, wordLimit and multi-answer group identifiers/numbers. Every original number has its own stable question ID and one scoring slot. Book index schema 1 contains only summaries, sizes and hashes.
- The strict original import_sample.py is retained unchanged and used for Cambridge 9/Test 1/Part 3. The representative JVM test compares the original bundled model against the normalized sample. IDs, exact question text, transcript paragraph order and content hashes match.
- Room database version stays 2. Existing MIGRATION_1_2 remains; no destructive migration, table replacement or practice/highlight deletion. New question fields have defaults for old questionsJson snapshots. Submitted attempts remain immutable and preserve their old questions.
- Multi-answer choices are one unordered set copied to every group slot in one Room transaction. A correct distinct letter gives one point for its original canonical numbered slot, with a maximum equal to the requested choices; blank, repeated and invalid oversized sets cannot gain extra credit. Inconsistent group snapshots do not score.
- Text answers normalize case, trim and repeated whitespace, with NFC Unicode composition; only explicitly supplied equivalent answers are accepted. Word/number limits are enforced, including ordinal dates. Overlong variants do not override the stated limit; if no supplied variant complies, the Part is excluded.
- HTML is parsed as inert data, never executed. The tools require exact numbered coverage, existing option references, agreement between answers.json and part.json, and agreement between questions.txt and HTML. Unknown/ambiguous source formats remain pending.
- Images are locally referenced and hashed, with byte/pixel limits. Native Compose images allow viewing and zooming; there are no remote image/audio dependencies.
- English-only or missing transcripts are allowed by schema 2 with explicit UI notices; missing content is not generated. Bilingual selection retains the existing per-language segment behavior.
- SAF copies a selected local package to task-owned staging, verifies hashes and the complete resource set, then publishes an immutable book directory through an AtomicFile catalogue. ZIP traversal, duplicates, undeclared/missing entries and size/storage violations reject the import. Existing resources and Room records remain untouched on failure.
- Same package reimport is idempotent. An expanded same-book package must contain each previous Part unchanged; conflicting content is rejected. Old immutable directories are retained, allowing an active player/history to keep references safely.
- Startup loads small book indexes and the one legacy bundled sample; it does not hash all library MP3s or parse all question/transcript bodies. Opening a Part loads and validates that Part. Lists render only the selected hierarchy and lazy summaries.

## Tests executed

- Python: 25 tests passed, 0 skipped, including the real original sample repeatability test. The first run hit the Windows system temporary-directory boundary; the D-drive rerun passed.
- JVM testDebugUnitTest: 30 passed, 0 failures, 0 skipped. This includes all 266 converted Parts/canonical scores and 24 representative Parts across books 5, 9, 13, 17, 18, 21, plus existing grading, highlight and shell tests.
- assembleDebug: BUILD SUCCESSFUL. The local APK contains only the bundled sample and application code, not the 17-book audio library; it was not delivered or uploaded.
- API 36 emulator connectedDebugAndroidTest regression: 44 passed, 0 failures, 0 skipped. Includes existing navigation, Activity recreation, shared player, resize panel, text-selection/highlights, Room persistence and migration tests, plus new import/type tests.
- Real device-path fixture test: six representative book packs were imported into a disposable local directory; all 24 real MP3s reached ready/play states, advanced position, paused and sought. Image decoding and canonical grading passed, and indexes reopened after repository recreation.
- Initial targeted device runs uncovered API 34+ native ZIP-path rejection (now accepted as the expected rejection while preserving assertions) and background audio-focus suppression; playback tests now hold a foreground Activity instead of weakening play assertions.
- Final source changes to the list and edit buffering are covered by an additional four-test normal-screen run. The same four tests passed at 720×1280, 320dpi and font scale 1.3 (0 failures, 0 skipped). That run exposed an off-screen list title on return; the title is now fixed above the list, and the tests passed after the fix. The emulator was restored to 1080×1920, 420dpi and font scale 1.0.

Build/test logs remain local under artifacts/: library-representative-verified.log, library-device-regression.log, library-batch-final-build.log, library-final-ui-normal.log, library-final-ui-small.log. Gradle SKIPPED setup tasks are not test results; all listed executed tests report zero test skips.

## Remaining verification

- 6 source Parts remain excluded; exact IDs/reasons are in library-source-audit.md. There are no unresolved duplicate-content conflicts.
- Physical phone SAF selection/import, real speaker/headphone listening and API 26 execution: NOT VERIFIED.
- All 266 imported MP3s subsequently passed ready/play/pause/seek checks in the full-library emulator run. Entire-track listening and physical audio quality remain NOT VERIFIED; see library-recheck.md.
- All 17 full-size books were installed and reopened on the emulator. Installation on a physical phone and import interruption by forced process death: NOT VERIFIED. Catalogue publication uses atomic replacement; current tests cover corruption rejection and repository reopen, not abrupt-power-loss simulation.
- Lint was not rerun. Public CI without private data will explicitly skip private-fixture tests; this report describes the actual local run.
- Existing launcher-icon changes remain local and are excluded from this stage commit. They are separate from the library implementation.
