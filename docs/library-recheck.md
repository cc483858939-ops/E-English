# Full library recheck and local installation

> This records the earlier 266-Part baseline and its full-library tests. Current delivery is 272 Parts / 2720 questions after [targeted recovery of the remaining six](pending-part-recovery.md). The new check preserves all 266 old resources and plays only the six restored recordings; it does not claim a new 272-recording full playback sweep.

Completed: 2026-10-09. Three original archives remain unchanged and private.

## Corrected findings

The previous 254-Part result was too conservative. Rechecking identified importer compatibility defects, not new exam content:

- HTML presentation ligatures (`ﬁ`, `ﬃ`, etc.) and their ordinary letters now compare equivalently. Only comparison changes; displayed text is preserved.
- Explicit box-matching instructions are recognized without pretending they are unrestricted text input.
- Explicit numeric-only limits (for example a limit of two numbers) are parsed separately from word limits.
- Letter emphasis in instructions is excluded from actual option-label extraction.

Synthetic regression tests cover these cases and still reject substantive text disagreement, duplicate/missing numbers, inconsistent answer maps and incompatible word limits. Twelve previously pending Parts now pass. All 254 previously normalized Part JSON files remain byte-for-byte identical; existing question IDs, transcript blocks and annotation content hashes are unchanged.

## Actual data installed

- Cambridge 5–21: 17 book packs, 68 Tests represented, **266 Parts and 2660 numbered questions**.
- **6 Parts remain excluded**: five supplied-answer/filling-limit conflicts and one ambiguous map-letter range. Exact IDs are in [the current source audit](library-source-audit.md). No missing answers, options or translation were generated.
- 266 MP3s: **994,047,953 bytes**. English and Chinese reconstruction: 266/266 each. 82 referenced image files; 428 numbered questions reference images.
- Book packs: **1,003,025,011 bytes** total; largest single pack 156,895,619 bytes. The full library is not embedded in either application APK or test APK.
- Latest local packs: `private-data/library/rechecked-batch/cambridge-N.eelpack`.
- Full source/resource/provenance report: `private-data/library/recheck-audit.json`.
- Per-book resource verification and pack hashes: `private-data/library/recheck-integrity.json`.

`recheck_library.py` checks source ZIP CRCs, rebuilds each model from its original archive and compares every normalized JSON/audio/image byte with both the book pack and unpacked data. It also rejects extra/missing Parts, duplicate ZIP entries and undeclared resources. Original archive hashes match the preceding audit.

## Tests actually executed

| Check | Actual result |
| --- | --- |
| Python importer/sample/integrity tests | 25 passed, 0 skipped |
| testDebugUnitTest, including all 266 Part models/canonical grades | 30 passed, 0 failures, 0 skipped |
| assembleDebug / assembleDebugAndroidTest | BUILD SUCCESSFUL |
| Existing connectedDebugAndroidTest regression | 44 passed, 0 failures, 0 skipped |
| FullLibraryDeviceTest, private full-library run | 1 passed, 0 skipped; 201.592 seconds |

The full-library device test imported all 17 packs into a separate test directory, loaded every Part, decoded each referenced image, scored each canonical submission 10/10 and exercised all **266 local MP3s**. Each audio reached ready state, played with advancing position, paused and sought to one second. This verifies short playback/seek operation, not listening to every full recording end-to-end or physical speaker quality.

Only after all checks passed did the test use the existing production importer to install the books into the ordinary App's `files/library` directory. Reopened repositories agree on all 266 summaries. Primary `practice.db` and its sidecar hashes were unchanged during installation; Room schema/version remains 2 and no database was cleared or destructively migrated. Application/test APK updates used `adb install -r`.

The final run emitted `FULL_LIBRARY_VERIFIED 266 Parts`, finished with a passing test result, and the deployment tool independently read the production catalogue/indexes to confirm 17 books / 266 Parts / 2660 questions. The ordinary App was force-stopped/restarted. Its list displays book counts without a load error; scrolling to Cambridge 21 and opening its Test list showed all four Tests normally. No answers or scores were changed by this manual navigation check.

Initial external-directory validation was skipped because Android 16 did not grant access to that transfer location; it is **not counted as PASS**. Subsequent binary-input transfers were caught as incomplete by checksums. The developer-only loader now transfers ASCII Base64 into App-private storage, verifies the transferred SHA-256, and streams the decoded pack into the unchanged production importer. An explicitly requested full run cannot skip missing fixtures; success also requires the completion marker and actual installed catalogue counts. Normal phone file imports still use ordinary `.eelpack` files.

Logs remain local: `artifacts/library-recheck-regression.log`, `artifacts/library-recheck-final-build.log`, `artifacts/library-full-test-build.log`, `artifacts/library-full-device.log`.

## Remaining limits

- The six excluded Parts need reliable source clarification; success is 266, not the theoretical 272.
- Physical phone SAF import, API 26 execution, actual speaker/headphone quality and interrupted-import process-death simulation remain NOT VERIFIED.
- This run installed all 17 books on the API 36 emulator; it did not install them on the user's phone.
- Copyrighted packs/audio/answers/transcripts and APKs stay local and ignored. No public APK, GitHub artifact or Release is published.
- Existing unrelated launcher-icon edits are retained locally and excluded from this commit.
