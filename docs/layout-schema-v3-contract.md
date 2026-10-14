# E-English layout schema v3 contract

Status: frozen specification for offline V3 candidate data. schemaVersion is 3; “V3” names this candidate batch. This document does not assert that Android can import, render, or grade v3 layouts.

## 1. Authority and inherited rules

V3 inherits the complete field model, source verification, limits, error behavior, question coverage, response coverage, block traversal, option-pool rules, UTF-16 ranges, matching reuse checks, and scoring declarations from the v2 contract, except for the explicit v3 additions and replacements in this document.

The original part.json, accepted answers, question IDs and numbers, prompt text, word limits, audio, images, and existing annotations remain authoritative and byte-for-byte unchanged. A v3 sidecar adds only grouping, interaction declarations, and display placement. The sidecar has exactly the v2 root fields with schemaVersion: 3.

Every Part layout remains complete: each question appears in exactly one Group and one ResponseRule. Unknown fields remain errors. Arrays retain display order. Image coordinates are natural source-image pixels with origin at top left.

## 2. V3 additions

### Image printed connectors

Every image Block retains overlays and adds the required printedConnectors array. Both arrays may be empty. A printed connector is exactly an object with:

    questionId: "q-id"
    text: "and"
    rectPx: { x: 10, y: 20, width: 25, height: 14 }
    evidence: { kind: "reviewedMeasurement", reviewId: "review-id", imageSha256: "64 lowercase hex digits" }

questionId identifies the paired-fill question; text is the exact visible connector; rectPx encloses that printed connector in the original image. It is evidence metadata, not a display instruction: a future renderer shows the original image and must not draw text a second time.

A paired image-fill question has two fill overlays, ordered by slotIndex, and exactly one printed connector in the same image Block. The connector text equals the ResponseRule joiner. The connector rectangle and input rectangles must be in bounds and must not overlap. Text answerConnector Blocks are used for text layouts; they must not duplicate a connector already printed in an image.

Each connector requires a human measurement review record. New records identify the Part, Group, question, exact connector text, image hash, rectangle, source archive path and SHA-256, source ZIP entry, source entry SHA-256, a concrete visual locator, and the review explanation. New slot-overlay records for the five v3 repairs use the same archive provenance. Existing v2 overlay records remain valid unchanged. The offline validator compares record identity, rectangle, image hash, source-entry bytes, and—when supplied—the source archive hash.

No image coordinate may be inferred from question-number spacing, generated as a default, or left as a placeholder.

### Paired fill joiner

A fill ResponseRule with one Slot has no joiner. A paired fill has exactly one question, two fill Slots for that same question at indices 0 and 1, one ResponseRule with scoring: legacyPerQuestion, and a required joiner whose value is and, or, or to. This continues to represent one saved answer and one score, not two answers.

When both inputs are nonblank, the saved legacy value is trim(slot0) + one space + joiner + one space + trim(slot1).

If either input is blank after trimming, the paired question is incomplete and must not be counted as answered.

When part.json.answerSeparator exists, joiner must equal it. Text layouts also retain one answerConnector Text Block between the two Slot Blocks, with the same text and question ID.

When answerSeparator is absent, a paired fill is permitted only when both Slots are measured image overlays, a matching printed connector and its measurement review record exist, and the reconstructed value is accepted by the unchanged Grader semantics and satisfies the original word limit. Validate against the original accepted answers (or correctAnswer when accepted answers are absent); do not edit or supplement part.json. A missing connector, review record, source image measurement, compatible accepted answer, or valid word count prevents a complete Part layout.

## 3. Offline validation and stable failures

The entry point is validate_layout_v3(layout_bytes, part_json_bytes, *, source_bytes, image_metadata, review_records, source_archive_hashes=None).

The validator retains the v2 stable reason codes and deterministic precedence. V3-specific checks use ANSWER_CONNECTOR_MISMATCH for a missing, extra, or wrong joiner/connector; IMAGE_EVIDENCE_MISSING when a connector has no measurement evidence; REVIEW_RECORD_MISSING for missing or inconsistent review provenance; and ANSWER_JOINER_INCOMPATIBLE when the old accepted answers or word limit cannot support a no-separator pair. Errors expose reason codes only, never answer or source text.

source_bytes keys are exact safe POSIX archive-entry paths as well as content.html and content.txt where required. source_archive_hashes maps an exact local ZIP path to its lowercase SHA-256. Image metadata is independently obtained from the original image bytes. Validation verifies image dimensions, bounds, evidence hashes, review-record hashes, and source ranges; it does not claim that a machine can judge whether human-measured rectangles are visually correct.

## 4. Required synthetic acceptance tests

The v3 test suite must cover:

- A normal single-slot fill.
- A text double fill with a matching legacy answerSeparator and intervening connector Text.
- An image double fill with two measured overlays and one printed connector.
- A connector/joiner mismatch.
- A missing image measurement or review record.
- A no-answerSeparator paired fill with a reviewed joiner, including C9 q12 answer compatibility and original word limit.
- A blank first or second slot returning incomplete, without producing a stored answer.
- Compatibility with unchanged one-answer/one-score grading behavior.
- Strict schema version 3 and closed object fields.

Synthetic tests use only synthetic fixtures under docs/examples; no Cambridge wording, answers, or images belong in tracked examples.

## 5. Candidate-data boundary

V3 candidates are offline review artifacts. Do not modify Android code, the Grader, saved scores, original archives, existing V2 .eelpack files, source part.json, or the previous 267 v2 candidate layouts. Do not stage, commit, or push. A successful Python validation does not establish Android v3 support, pack-import compatibility, or release readiness.
