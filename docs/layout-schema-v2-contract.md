# E-English `layout.json` v2: frozen implementation contract

Status: implementation specification. This document fixes the data model and validation behavior for the next implementation task. It does **not** mean that the current Android importer or renderer supports v2. Schema v1 remains as documented in `layout-schema-v1.md`.

## 1. Boundary and authority

`layout.json` is the optional sidecar at `listening/<partId>/layout.json`. The pack index continues to identify its exact bytes with `layoutSha256`; index version 2 and layout schema version 2 are independent. The existing `part.json` bytes are immutable in this migration. Its question IDs, numbers, answer strings, word limits, transcript, audio, image hashes, practice answers, and existing annotation keys remain authoritative. A v2 sidecar supplies *grouping, interaction declarations, and display placement*; it does not supply correct answers.

Every v2 document is complete for its Part: each `part.questions` entry occurs in exactly one group and one response rule. V1 documents may remain partial. A Part may have no sidecar and use legacy rendering. A v2 document must never be silently interpreted as v1. No v2 packs are to be built or imported until the Android decoder, validator, renderer, and fallback path have been implemented and checked.

The JSON is UTF-8, strict JSON, at most 4,000,000 bytes. Unknown keys are errors at every level. IDs match `[a-z][a-z0-9-]{0,79}` and are unique in their stated scope. All arrays preserve source/display order. No rule may derive group membership, response kind, position, or option reuse from prompt punctuation, underscores, repeated text, or question order.

Safety bounds are fixed: at most 64 groups per document; per group, at most 10 question IDs, 32 slots, 32 response rules, 32 option pools, and 32 instruction Text nodes; at most 512 Blocks per group and 2,048 per document, counting table Cells and flow Nodes but not table Rows; at most 64 rows per table, 64 cells per row, 64 nodes and 128 edges per flow, and 128 overlays per image. Nesting depth starts at 1 for a top-level Block and may not exceed 8. Each visible Text is at most 100,000 UTF-16 units and all visible Text in the document totals at most 1,000,000 UTF-16 units. **Only Text `resourceRange.resource`** is limited to exactly `content.html` or `content.txt`; `ReviewRecord.sourceResource` follows the separate rule below. An empty optional list is represented by `[]`, never omitted or `null`.

## 2. Exact JSON objects

All fields listed without `?` are required, even when their value is an empty array. All nullable/optional fields are forbidden unless their variant lists them. In the tables, `string` means nonempty Unicode text, `sha256` means 64 lowercase hex digits, and `id` means the ID pattern above. Integers exclude booleans. Array order is significant.

### Document

| Field | Type | Rule |
| --- | --- | --- |
| `schemaVersion` | integer | Exactly `2`. |
| `partId` | string | Exactly `part.id`; the Part's own ID validation remains authoritative. |
| `basePartSha256` | sha256 | Hash of the original `part.json` bytes, not re-encoded JSON. |
| `groups` | array of Group | 1–64, in display order; covers all Part questions exactly once. |

### Group

| Field | Type | Rule |
| --- | --- | --- |
| `groupId` | id | Stable within a Part. Once shipped, never derive it again from visible text or change it to reflect styling. |
| `questionIds` | array of string | 1–10 existing question IDs, in the Part's question order. |
| `promptHashes` | object, question ID → sha256 | Keys exactly equal `questionIds`; SHA-256 of each untouched UTF-8 prompt. |
| `instructions` | array of Text node | Shared visible instruction text, in display order; empty only when no group instruction exists. |
| `optionPools` | array of OptionPool | Can be empty. |
| `slots` | array of Slot | Every user-input identity in this group. |
| `responses` | array of ResponseRule | Partitions `questionIds` exactly once. |
| `blocks` | array of Block | Nonempty, in display order. |

An instruction appearing in multiple legacy question fields is emitted once here. Its source reference must identify one exact legacy field or exact source range. `part.instructions` remains Part-level content; it is not automatically copied to each group.

### OptionPool

`{ "poolId": id, "questionIds": [question IDs], "optionIds": [option IDs] }`. `questionIds` is nonempty and follows Part order. `optionIds` is the nonempty, unique order shown to the user. For every listed question, the corresponding `part.json` options must have **exactly** these IDs, in this order, with identical texts. Pools contain no copied option text: the renderer reads it from `part.json`. If options differ, use separate pools. Every choice response refers to one pool containing all its questions.

### Slot

`{ "slotId": id, "questionId": string, "slotIndex": integer, "kind": enum }`. `kind` is exactly `fill`, `singleChoice`, `multipleChoice`, or `matching`. The slot question must be in the group. `(questionId, slotIndex)` and `slotId` are each unique within the Part. Slot indices for a question start at 0 and have no gaps. A choice question has one slot. A normal fill question has one slot. A fill question whose legacy `answerSeparator` is present has exactly two slots, indices 0 and 1. Both slots still map to **one** stored answer and one score. The separator is one visible Text node with `role: "answerConnector"` between the two `slot` Blocks; it is never a third slot. Paired fill slots therefore cannot be image overlays in v2; a source requiring that combination stays `needsReview` until a versioned image-text design is approved.

### ResponseRule: closed variants

Common required keys: `kind`, `questionIds`, `slotIds`, `scoring`. The rule's questions are unique, in Part order, and its slots are exactly the corresponding slots in question and slot-index order. No question belongs to two rules. `scoring` is a declaration of current behavior, not a new algorithm.

| `kind` | Additional required keys | Exact constraints |
| --- | --- | --- |
| `fill` | none | One question; one or two `fill` slots; `scoring: "legacyPerQuestion"`; legacy question has no options and has `wordLimit`. Two slots iff `answerSeparator` exists. |
| `singleChoice` | `optionPoolId` | One question and one slot; `scoring: "legacyPerQuestion"`; legacy options contain at least two entries. |
| `matching` | `optionPoolId`, `reuse` | One or more questions and one slot per question; `scoring: "legacyPerQuestion"`; `reuse` is exactly `allowed` or `forbidden`, established from source instructions or manual review. Unknown reuse is a review item, never a default. |
| `multipleChoice` | `optionPoolId`, `selectCount`, `order`, `storage` | Two to six questions, one slot per question; legacy questions share the same non-null `groupId` and `groupNumbers`; `selectCount` equals their count; `order: "unordered"`; `scoring: "onePointPerCorrectOption"`; `storage: "sameSortedSetOnEveryQuestion"`. |

For `multipleChoice`, the existing `Grader` compares the one chosen option set with each member question's accepted option IDs and awards at most one point per member. The renderer persists the same sorted, comma-separated set on every member question. V2 must preserve this behavior; it must not introduce all-or-nothing grading or positional matching. A legacy `IMAGE_BASED` question may declare `fill` or `singleChoice` according to its actual options and word limit. No renderer may infer this from the old enum.

Validation must reject a legacy `MULTIPLE_CHOICE` question in any rule other than `multipleChoice`, and reject a `multipleChoice` rule containing any other legacy type. The rule must include **all** members of that legacy `groupId`, not a subset. This is a compatibility check against the current `Grader`, not an instruction to infer interaction from old text. A violation is `LEGACY_INTERACTION_MISMATCH`.

`reuse` controls *new matching input*: `forbidden` prevents selecting the same option for two member questions, while `allowed` permits it. Existing saved answers remain readable and are still graded by the unchanged legacy per-question rule; conversion never rewrites them. A source that does not establish reuse must be reviewed before assigning either value.

For `reuse: "forbidden"`, the current accepted option IDs must admit at least one injective assignment across **all** questions in that matching rule. For each question, candidates are `acceptedAnswers` when nonempty, otherwise `[correctAnswer]`, compared to option IDs without case distinction as in Part validation. A standard bipartite matching check is sufficient; repeated primary answers are allowed when alternatives still permit distinct assignments. No complete assignment gives `MATCHING_REUSE_CONFLICT`. This mechanical check does not replace source review of whether reuse really is forbidden.

### Block: closed variants

Every Block has a unique Part-scoped `id`. The variants below list all remaining permitted keys. A nested `children` array contains Blocks and has at least one child. No block may contain itself indirectly. Maximum nesting depth is 8; total Blocks across the document, including table cells and flow nodes, is 2,048.

| `type` | Additional required keys | Rule |
| --- | --- | --- |
| `text` | `textId`, `text`, `source`; connector variant also has `role`, `questionId` | Nonempty visible text; `textId` unique in Part. Ordinary Text has exactly the five keys `id`, `type`, `textId`, `text`, `source`. Connector Text additionally has exactly `role: "answerConnector"` and its paired `questionId`. No other role is valid. Group `instructions` contains ordinary Text only. |
| `paragraph` | `children` | Ordered inline or nested content. |
| `listItem` | `marker`, `children` | `marker` is `bullet`, `numbered`, or `none`; a visible number/label belongs in a Text node. |
| `formRow` | `children` | Labels and values are child nodes, not an unannotated `label` string. |
| `table` | `rows` | Rectangular grid; see below. |
| `flow` | `nodes`, `edges` | Directed flow graph; see below. |
| `image` | `asset`, `widthPx`, `heightPx`, `overlays` | Image asset is declared in `part.json` and pack index; dimensions match decoded image. Empty `overlays` is valid when controls are beside the image. |
| `slot` | `slotId` | Inline/form/table/flow placement of exactly one Slot. |
| `choiceList` | `optionPoolId`, `slotIds` | Shows one referenced pool and binds one or more choice Slots; a shared multiple-choice control has all member slot IDs here. |

`table.rows` is a nonempty array of `{ "id": id, "cells": [Cell] }`; `Cell` is `{ "id": id, "rowSpan": positive integer, "colSpan": positive integer, "children": [Block] }`. Every row has at least one cell. Span expansion must occupy a gapless rectangle; overlapping or missing cells are invalid. Cell IDs are also Part-scoped block IDs. A slot inside a cell has its exact placement through the containment tree.

`flow.nodes` is a nonempty array of `{ "id": id, "children": [Block] }`; `flow.edges` is an array of `{ "from": node ID, "to": node ID }`. Node IDs are Part-scoped block IDs. Endpoints must be existing nodes in this flow; duplicate edges and self-edges are invalid. Cycles are allowed when the source actually contains one. Displayed edge labels or connectors are Text nodes inside the relevant node, not unannotated strings. Flow order is `nodes` order, not inferred from the graph.

`image.overlays` is an array of `{ "slotId": id, "rectPx": { "x": integer, "y": integer, "width": positive integer, "height": positive integer }, "evidence": ImageEvidence }`. The rectangle lies entirely within `widthPx × heightPx` measured in natural image pixels, with origin at the top left. Each overlay binds exactly one `fill` Slot. `ImageEvidence` is exactly `{ "kind": "reviewedMeasurement", "reviewId": id, "imageSha256": sha256 }`. The offline validator requires a review record with the same review ID, image hash, slot ID, and rectangle; the record names the original source and explains how the coordinates were verified. The pack validator can check the image hash and rectangle but cannot reperform the absent-source review. A missing or uncertain rectangle cannot be encoded as zero, `null`, or an arbitrary default: it stays in the offline review queue and prevents that Part from being released as complete v2. Image choice questions normally use an image Block plus a separate `choiceList`, without invented overlays.

The offline `ReviewRecord` shape is exactly `{ "reviewId": id, "partId": string, "groupId": id, "slotId": id, "imageSha256": sha256, "rectPx": RectPx, "method": enum, "sourceResource": string, "sourceSha256": sha256, "sourceLocator": string, "explanation": string }`. `method` is `sourceMarkup`, `manualOverlayReview`, or `syntheticExplicitCoordinate`; the last value is allowed only in public synthetic fixtures. `sourceResource`, `sourceLocator`, and `explanation` are nonempty, bounded to 512, 512, and 2,000 characters respectively. `sourceResource` may be a POSIX-style relative archive entry such as `original/part/map.png`; it must not start with `/`, contain `\` or NUL, have an empty, `.` or `..` path component after splitting on `/`, or begin with `[A-Za-z]:`. It is an exact key into `source_bytes`, not a filesystem path to open. A matching record is necessary for mechanical validation, but a real record's evidentiary quality is a separate human review decision; the validator must not infer it from an explanation's wording.

Each Slot occurs at exactly one display placement: either one `slot` Block, one image overlay, or one `choiceList`'s `slotIds`. No dangling or multiply placed Slot is valid. A `choiceList` may contain multiple Slots only for a single `multipleChoice` ResponseRule; it renders one shared control. All other choice lists contain one Slot.

For each paired fill question, traverse display leaves in declared order: Block arrays and container `children` order, table rows then cells order, flow `nodes` order, and image `overlays` array order. In that traversal, its slot 0 must precede its one `answerConnector` Text node, which must precede slot 1. The connector's `questionId` and exact `text` must equal the legacy question ID and `answerSeparator`; its source is checked by the ordinary Text source rules. A missing, extra, or wrong connector gives `ANSWER_CONNECTOR_MISMATCH`; reversed slots give `SLOT_PLACEMENT_MISMATCH`. A question without `answerSeparator` may not have a connector. Other Text nodes may occur between these three leaves.

### Text source: closed variants

Every Text node has exactly one `source`:

* Legacy field: `{ "kind": "partField", "field": "part.instructions" | "question.prompt" | "question.instructions" | "question.context", "questionId": string? , "startUtf16": integer, "endUtf16": integer }`. `questionId` is required exactly for the three question fields and forbidden for `part.instructions`. The UTF-16 substring of that unchanged field must equal `text` exactly. A range cannot bisect a surrogate pair.
* Source resource: `{ "kind": "resourceRange", "resource": "content.html" | "content.txt", "sha256": sha256, "normalization": "htmlTextV1" | "rawTextV1", "startUtf16": integer, "endUtf16": integer }`. HTML must use `htmlTextV1`; TXT must use `rawTextV1`. The range in the normalized source must equal `text` exactly. The original resource bytes are an **offline conversion input**, not a new pack entry. Runtime checks the stored shape/hash; offline validation also verifies source bytes and substring.

`rawTextV1`: strict UTF-8 decode; replace CRLF and lone CR with LF; no trimming or whitespace folding. `htmlTextV1`: strict UTF-8 decode; parse HTML text nodes in document order with HTML character references decoded; omit comments and `script`/`style` content; concatenate node text without inserting guessed separators; replace CRLF/CR with LF and NBSP with ordinary space; collapse every run of ASCII space, tab, LF, form feed, or vertical tab to one space; trim leading/trailing ordinary spaces. UTF-16 offsets are calculated *after* this normalization. A generator may preserve layout breaks as separate container Blocks, but must not claim those breaks came from a Text range.

For an unchanged legacy prompt range, the Android annotation target remains `scope="question"`, `textId="<questionId>/prompt"`, with the original prompt hash and original UTF-16 offset. Text sourced from another field/resource has a new target `scope="layout"`, `textId="layout/<groupId>/<textId>"`, hash of its own exact UTF-8 text, and local UTF-16 offsets. The new target is a **future Android storage/rendering requirement**; current `StructuredLayoutProjection` handles prompt-backed text only. V2 adoption must include that implementation and tests. Rewording a text node changes its content hash and must not silently reattach an old highlight.

## 3. Cross-document checks and review queue

The offline validator receives exact `part.json` bytes, the decoded Part, declared image hashes/dimensions, and source resource bytes when any `resourceRange` or image source evidence is used. It checks all shape and cross-reference rules above plus aggregate text length ≤1,000,000 UTF-16 units, each Text ≤100,000 UTF-16 units, and that every existing Part question is covered once. The future Android validator repeats all checks possible from the installed pack, including source-independent Part ranges and image dimensions. It cannot verify absent original HTML/TXT bytes and must not pretend to.

The Python entry point is `validate_layout_v2(layout_bytes: bytes, part_json_bytes: bytes, *, source_bytes: dict[str, bytes], image_metadata: dict[str, dict], review_records: list[dict]) -> dict`. `image_metadata` entries are `{ "sha256": sha256, "widthPx": positive integer, "heightPx": positive integer }`, supplied by a caller that has independently hashed and decoded the image bytes. The validator checks each image Block against these entries and each reviewed overlay against `review_records`. A required source missing from `source_bytes` gives `SOURCE_RESOURCE_MISSING`; a required image absent from `image_metadata` gives `UNKNOWN_IMAGE`; a referenced review record missing or not matching the hash, slot, rectangle, Part, or group gives `REVIEW_RECORD_MISSING`. Invalid JSON gives `INVALID_LAYOUT_JSON`, an invalid Part input gives `INVALID_BASE_PART_JSON`, missing/incorrect field shapes not assigned a more specific reason give `INVALID_LAYOUT_SHAPE`, and unrecognized cross-references give `INVALID_REFERENCE`. This contract adds no Python dependency; the JSON Schema and dependency-free Python validator must agree on structural acceptance.

Error precedence is deterministic: parse and schema version; unknown/missing fields and shape limits; base Part hash/Part validity; group/question ownership; response coverage and rule shape; slot identity; block structures (including table, flow, and image); slot placement; option pools; source hashes/ranges/text; review records. Within each phase, visit groups, arrays, and object fields in document order. A specific reason code in the acceptance table takes precedence over the general code for that condition.

The offline conversion report, separate from `layout.json`, has one row per proposed group: `partId`, `groupId`, `questionIds`, `status` (`converted` or `needsReview`), `reasonCode`, source resource names/hashes, and review record ID when applicable. Only `converted` groups can enter a **complete** v2 Part; a Part with any `needsReview` group has no released v2 sidecar. This keeps the all-question invariant meaningful.

The report must also list original answer candidates separately from `part.json`'s effective accepted answers and evaluate each candidate with current `Grader.withinLimit` semantics. A rejected candidate is `needsReview`, not proof of a grading bug and not permission to edit `part.json`. The two Cambridge 13 source HTML variants remain distinct evidence; matching normalized questions, answers, audio, and images do not establish layout equivalence. Their layout differences need a recorded comparison and review decision before conversion.

## 4. Required synthetic acceptance cases

The provided Part/source/image fixtures are synthetic. Each case has an exact expected validation result. IDs and visible words in the fixtures are arbitrary synthetic values; the *structure and assertions* below are fixed.

| Case | Required structure | Required result |
| --- | --- | --- |
| Single choice | One question, one `singleChoice` Slot/ResponseRule, one exact OptionPool and `choiceList`. | Pass; option order and IDs preserved. |
| Multiple choice | Two questions sharing one legacy multi group, two Slots, one `multipleChoice` rule, `selectCount=2`, `order=unordered`, `scoring=onePointPerCorrectOption`, one shared `choiceList`. | Pass; two member answers persist the same sorted selection set. |
| Matching | Two targets, one explicit shared pool, two matching Slots at two target locations, explicit `reuse`. | Pass; distinct targets remain distinct questions. |
| Ordinary fill | One question, one fill Slot placed between Text nodes. | Pass; no underscore parsing required. |
| Double fill | One question with legacy `answerSeparator="and"`, Slots 0 and 1 with an intervening Text node `and`, `role: "answerConnector"`, `questionId: "q7"`. | Pass; one stored answer/score and two displayed controls. |
| Table | A real 2×2 `table` with one Slot inside an identified Cell. | Pass; cell placement survives row/column traversal. |
| Flow | A two-node acyclic `flow`, one edge, one Slot inside a node. | Pass; node and edge references valid. |
| Image/map | Declared image dimensions, a fill overlay with source-backed coordinates; separately, an image with a choice list beside it. | Pass; both interaction forms are explicit. |

The implementation must also include failing variants with these exact reason codes. Start each mutation from `docs/examples/layout-v2/layout.json`, change only the indicated value, and validate it against the accompanying `part.json`, `content.html`, `map.png`, and `context.json`. The first applicable check in document/field order gives the listed result; do not replace these results with raw parser exceptions.

| Mutation to the synthetic layout | Expected reason code |
| --- | --- |
| Remove the final group. | `QUESTION_COVERAGE_MISMATCH` |
| Append a copy of `example-fill` with only its `groupId` changed. | `QUESTION_IN_MULTIPLE_GROUPS` |
| Remove the response in `example-fill`. | `RESPONSE_COVERAGE_MISMATCH` |
| Append a copy of `example-fill.slots[0]` to that group's `slots`. | `DUPLICATE_SLOT` |
| Remove `place-6-0` from the fill paragraph. | `SLOT_PLACEMENT_MISMATCH` |
| Change `pool-shapes.optionIds[0]` from `A` to `Z`. | `OPTION_POOL_MISMATCH` |
| Change `q1-image.asset` to `missing.png`. | `UNKNOWN_IMAGE` |
| Change `q1-image.widthPx` to `101`. | `IMAGE_DIMENSION_MISMATCH` |
| Change the map overlay's `rectPx.x` to `90`. | `IMAGE_RECT_OUT_OF_BOUNDS` |
| Remove the map overlay's `evidence`. | `IMAGE_EVIDENCE_MISSING` |
| Remove `cell-answer` from the second table row. | `TABLE_GRID_INVALID` |
| Change the flow edge's `to` to `missing-node`. | `FLOW_EDGE_INVALID` |
| Change `source-label.text` to `Different label`. | `SOURCE_TEXT_MISMATCH` |
| Change `source-label.source.sha256` to 64 zeroes. | `SOURCE_HASH_MISMATCH` |
| Change `source-label.source.endUtf16` to `1000`. | `INVALID_UTF16_RANGE` |
| Add `unexpected: true` at the document root. | `UNKNOWN_FIELD` |
| Change `basePartSha256` to 64 zeroes. | `BASE_PART_HASH_MISMATCH` |
| Change `schemaVersion` to `3`. | `UNSUPPORTED_LAYOUT_SCHEMA` |

Additional repair acceptance cases, each starting from the same synthetic fixture unless stated otherwise:

| Mutation | Expected result |
| --- | --- |
| Replace `example-multiple`'s one `multipleChoice` rule with separate `singleChoice` rules for q2/q3; change their Slot kinds to `singleChoice` and split the shared `choiceList` into two lists, leaving all questions and options present. | `LEGACY_INTERACTION_MISMATCH` |
| Move `s5-0` into `match-first-choice.slotIds` and remove `match-second-choice`, so each matching Slot is placed once but both share one `choiceList`. | `SLOT_PLACEMENT_MISMATCH` |
| Remove `double-connector` from `example-double`. | `ANSWER_CONNECTOR_MISMATCH` |
| Keep `double-connector` but change its `text` from `and` to `or`; change synthetic q7's `instructions` from `and` to `or` and change the connector's `source.endUtf16` from `3` to `2`; reserialize `part.json` with the format below and update `basePartSha256`. Leave q7's `answerSeparator` as `and`. | `ANSWER_CONNECTOR_MISMATCH` |
| Exchange the two `slot` Blocks around `double-connector`, retaining their IDs and Slot references. | `SLOT_PLACEMENT_MISMATCH` |
| Change synthetic q5's `correctAnswer` from `Y` to `X`; reserialize `part.json` with `json.dumps(part, ensure_ascii=False, indent=2) + "\n"` and update `basePartSha256`. Both q4 and q5 then accept only `X` while `reuse` remains `forbidden`. | `MATCHING_REUSE_CONFLICT` |
| As a positive alternative case, give synthetic q5 `acceptedAnswers: ["X", "Y"]`, reserialize/update the hash as above. | Pass: q4 can use `X` and q5 can use `Y`. |
| In the synthetic review record, change `sourceResource` to `archive/path/map.png` and provide the same PNG bytes under that exact `source_bytes` key. | Pass. |
| In that review record, change `sourceResource` to `../map.png`, even if a matching `source_bytes` key is supplied. | `REVIEW_RECORD_MISSING` |

Report only reason codes in import errors; never include answer or source text in an error. The JSON Schema may reject a malformed object before cross-field validation; the offline validator's public error mapping must still produce the codes above for these exact cases.

## 5. Implementation order for Luna

1. Read this contract and the existing v1 schema/validator. Implement `docs/schemas/layout-v2.schema.json` with closed variants and all structural limits. Do not change v1 files.
2. Use the eight complete synthetic groups and companion files in `docs/examples/layout-v2/` as the passing fixture. Add the failing mutations above. No real Cambridge wording, image, or answer may enter public files.
3. Implement a separate offline v2 validator that accepts exact Part/source bytes and image metadata and emits the listed reason codes. It may share pure hashing/UTF-16 helpers with v1, but may not loosen v1 behavior.
4. Run fixture validation and existing v1 tests. Report commands, results, changed files, and any contract ambiguity. If a rule here cannot be implemented literally, stop that rule and report the conflicting input; do **not** invent a fallback, new enum, or default.

This task does not authorize Android model, importer, renderer, grading, database, real library, or pack changes. After review, those are separate implementation phases. Do not stage, commit, or push.
