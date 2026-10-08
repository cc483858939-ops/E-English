# Cambridge 5–21 source audit

Date: 2026-10-08. Copyrighted inputs, detailed hashes/provenance and converted resources remain local and ignored.

The requested `D:/code/E-English.local/listening-sources` directory was absent. All three explicitly supplied `D:/IELTS-Listening-Cambridge-*.zip` files were present and scanned read-only.

## Actual resource inventory

- 3 archives; 288 source Parts; 272 unique candidate Parts; 17 books and 68 Tests.
- Source answer records: 2720; complete answer files: 272/272. Presence is not proof of correct reconstruction.
- Source MP3 bytes before deduplication: 1,027,818,165; unique Part MP3 bytes: 1,002,367,192.
- English/Chinese source files present: 272/272 and 272/272; 87 image files.
- Normalization passed: 254 Parts, 2540 numbered questions. 18 Parts remain pending and are excluded from playable indexes.

| Book | Tests | Source Parts | Validated Parts | Questions | MP3 bytes (source) | EN / ZH | Images |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| 5 | 4 | 16 | 14 | 140 | 23,341,437 | 16 / 16 | 6 |
| 6 | 4 | 16 | 9 | 90 | 21,537,217 | 16 / 16 | 10 |
| 7 | 4 | 16 | 11 | 110 | 23,005,615 | 16 / 16 | 12 |
| 8 | 4 | 16 | 15 | 150 | 23,341,224 | 16 / 16 | 7 |
| 9 | 4 | 16 | 15 | 150 | 23,650,110 | 16 / 16 | 11 |
| 10 | 4 | 16 | 16 | 160 | 23,911,540 | 16 / 16 | 3 |
| 11 | 4 | 16 | 15 | 150 | 26,419,695 | 16 / 16 | 5 |
| 12 | 4 | 16 | 16 | 160 | 26,916,128 | 16 / 16 | 5 |
| 13 | 4 | 16 | 16 | 160 | 25,450,973 | 16 / 16 | 3 |
| 14 | 4 | 16 | 15 | 150 | 26,726,587 | 16 / 16 | 1 |
| 15 | 4 | 16 | 16 | 160 | 115,169,905 | 16 / 16 | 4 |
| 16 | 4 | 16 | 16 | 160 | 101,668,775 | 16 / 16 | 3 |
| 17 | 4 | 16 | 16 | 160 | 100,770,569 | 16 / 16 | 1 |
| 18 | 4 | 16 | 16 | 160 | 49,638,454 | 16 / 16 | 4 |
| 19 | 4 | 16 | 16 | 160 | 85,742,206 | 16 / 16 | 5 |
| 20 | 4 | 16 | 16 | 160 | 156,307,748 | 16 / 16 | 3 |
| 21 | 4 | 16 | 16 | 160 | 148,769,009 | 16 / 16 | 4 |

## Source types and validated model types

| Type | Source numbered slots | Validated slots |
| --- | ---: | ---: |
| FLOW_COMPLETION | 10 | 10 |
| IMAGE_BASED | 172 | 163 |
| MATCHING | 353 | 334 |
| MULTIPLE_CHOICE | 224 | 212 |
| NOTE_COMPLETION | 1021 | 992 |
| SHORT_ANSWER | 13 | 7 |
| SINGLE_CHOICE | 467 | 423 |
| TABLE_COMPLETION | 256 | 223 |
| TEXT_INPUT | 204 | 176 |

## Format differences and deduplication

- questions.txt is unstructured. The importer reads inert `section.questions` DOM blocks, explicit numbered blanks, single/multiple option nodes and letter boxes; it checks text against HTML. No WebView is used.
- The answer schema includes accepted-answer arrays and semicolon-delimited answer maps. Later archives additionally include extraction provenance fields and discovery metadata.
- Cambridge 13: 16 semantic duplicates; canonical answers, audio and segment text/timing agree. Differences in answer provenance, timeline child metadata and generated HTML are retained in the private audit hashes. No content conflict was found; one normalized copy is selected explicitly.
- Answer choices use UTF-16-independent stable question IDs; the existing Cambridge 9/Test 1/Part 3 sample still uses the original strict converter and exact question/transcript strings.
- Some labels are plain text instead of strong tags; extraction checks explicit nearest numbers and complete unique ranges. Missing or inconsistent labels fail validation.
- Image-dependent tables/maps remain native image resources; only source-declared option letters are exposed. The timeline is not used for precise synchronization.

## Pending Parts

| Part ID | Validation reason |
| --- | --- |
| cambridge-5-test-2-part-2 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-5-test-4-part-3 | MISSING_WORD_LIMIT |
| cambridge-6-test-1-part-1 | MISSING_WORD_LIMIT |
| cambridge-6-test-1-part-2 | ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT |
| cambridge-6-test-1-part-4 | ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT |
| cambridge-6-test-2-part-4 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-6-test-3-part-4 | ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT |
| cambridge-6-test-4-part-2 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-6-test-4-part-4 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-7-test-2-part-3 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-7-test-2-part-4 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-7-test-4-part-1 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-7-test-4-part-3 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-7-test-4-part-4 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-8-test-2-part-2 | QUESTION_TEXT_HTML_MISMATCH |
| cambridge-9-test-1-part-4 | ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT |
| cambridge-11-test-1-part-2 | INVALID_MATCHING_RANGE |
| cambridge-14-test-1-part-2 | MATCHING_OPTION_TEXT_MISSING |

Reason definitions: QUESTION_TEXT_HTML_MISMATCH = captured question text differs from inert HTML; MISSING_WORD_LIMIT = no reliable explicit input limit; ACCEPTED_ANSWER_WORD_LIMIT_CONFLICT = every provided variant exceeds the stated limit; INVALID_MATCHING_RANGE = no unambiguous option labels; MATCHING_OPTION_TEXT_MISSING = declared option text cannot be reconstructed. No pending Part is invented or silently included.

Per-Test/Part resource hashes, answer completeness, type counts, original archive hashes and all provenance copies are recorded in ignored `private-data/library/audit.json`. Aggregate counts here contain no exam bodies or answers.

Importer validation: 19 Python tests passed (including the original real sample repeatability test). The 6-book representative subset contains 24 validated Parts; Android verification is recorded separately in the implementation report.
