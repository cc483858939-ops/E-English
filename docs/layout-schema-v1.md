# E-English structured question layout, Schema v1

`layout.json` is an optional display sidecar at `listening/<partId>/layout.json`. It is tied to the exact UTF-8 `part.json` bytes and leaves the Part model, prompt strings, question IDs, answers, scoring, Room records, and highlight keys unchanged.

The machine-readable shape is [layout-v1.schema.json](schemas/layout-v1.schema.json). JSON Schema covers field shape; the Python and Kotlin validators add cross-field checks against a specific Part.

## Top-level fields

| Field | Type | Required | Rule |
|---|---|---:|---|
| `schemaVersion` | integer | yes | Exactly `1`. This is independent of the `.eelpack` index version. |
| `partId` | string | yes | Exact Part identifier; must match the decoded Part. |
| `basePartSha256` | lowercase SHA-256 | yes | Hash of the original `part.json` bytes, before decoding or re-encoding. |
| `groups` | array | yes | Zero to 64 independently renderable groups; no group is synthesized from adjacent questions. |

Unknown properties are rejected. The encoded document is limited to 4 MB. Validators also cap group count, blocks, nested depth, text length, and aggregate text.

## Group fields

| Field | Type | Required | Rule |
|---|---|---:|---|
| `groupId` | string | yes | Stable Part-scoped identifier, formed from Part ID, lower-case layout kind, and explicit question range. |
| `layoutKind` | enum | yes | `summaryCompletion`, `notesCompletion`, `formCompletion`, `sentenceCompletion`, `linearTextCompletion`, or `shortAnswerInline`. |
| `questionIds` | string array | yes | Unique existing IDs in source order. A question can belong to at most one group in a Part. |
| `promptHashes` | object | yes | One SHA-256 per `questionId`, over the untouched prompt encoded as UTF-8. |
| `source` | object | yes | `resource` (`content.html`), its source SHA-256, a bounded structural locator, and a source-kind label. |
| `blocks` | block array | yes | Ordered structured content. Each blank is checked against the question map and slot rules. |

Layout JSON never contains answers. A group may cover one question or several. Unsupported group types stay outside `groups` and continue through the legacy renderer.

The Python prototype maps only explicit HTML blank markers to question IDs. Before a group is marked `converted`, it reconstructs the source-visible text after removing those labels and replacing each marked blank with one placeholder, then compares that projection with the generated blocks. A mismatch is reported as `SOURCE_RENDER_MISMATCH` for manual review.

## Content blocks

`text` and `fixedText` contain visible strings and a source reference. `blank` contains `questionId`, `questionNumber`, `slotIndex`, and a source reference. `lineBreak` is explicit. `paragraph`, `listItem`, and `formRow` keep their children in a nested list rather than flattening structure into a single string. Unknown block types fail validation.

Text source references are one of:

- `prompt`: `questionId`, `startUtf16`, `endUtf16`; the exact prompt substring must equal the block text.
- `html`: `resource` and a locator; this marks source-independent text that has no exact prompt mapping.

Blank source references are one of:

- `prompt-anchor`: `questionId`, UTF-16 start/end; the exact prompt range must contain an underscore gap.
- `html-marker`: `resource` and a locator for an explicit source marker. No offset is invented.

Every offset is a UTF-16 code-unit offset, matching Kotlin `String` and Android `TextView`. Python converts code-point indexes to UTF-16 offsets only after locating exact source text. A supplementary Unicode character therefore contributes two offset units. A range that bisects one is invalid.

Ordinary text questions require exactly one slot at index `0`. A question with `answerSeparator` requires exactly two slots at indices `0` and `1`; both remain one logical answer and one scoring question. The fixed connector remains display text and is not scored. Group IDs and question slots are unique, and no question may be occupied by two groups.

## Validation and fallback

Install-time import verifies the index-declared `layoutSha256`, exact ZIP entry set, base Part byte hash, prompt hashes, source offsets, question IDs/numbers, slot counts, and size bounds. The importer rejects a damaged pack before publishing its catalogue pointer.

At runtime, an unexpected sidecar read or validation error is recorded as a non-content-bearing warning and uses the existing parser or `QuestionCard`. Source-independent HTML text can be copied and displayed; it is never translated into a guessed prompt offset, so permanent highlighting is disabled for that text. Existing prompt highlights continue using the original content hashes and offsets.

Schema v1 supports text and explicit source gap markers. Image, table-cell, flow-node, and image-relative input geometry are not supported in this version; a Part that needs those structures remains on the legacy renderer. The schema version and the `.eelpack` index version have separate upgrade paths. Index v1 continues to describe packs without sidecars; index v2 may declare `layoutSha256` per Part.

## Migration plan

The prototype generates only selected A/B groups in local `private-data/layout-prototype/`. The next stage can batch the audited A+B inventory after the generator's group statuses and field-diff report have been reviewed. C-class images need a separate measured-coordinate design and must not be inferred from screenshots or image dimensions.
