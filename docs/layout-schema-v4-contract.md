# E-English layout schema v4 contract

Status: `schemaVersion` 4 layout-data contract for offline candidates and Android integration. The offline validator checks source provenance and measurement records; Android validates the packaged layout and asset metadata, but cannot recheck original ZIP sources that are not included in packs. This contract does not change answer storage or grading semantics.

## 1. Baseline and compatibility

V4 inherits every v3 field, validation rule, size limit, question/response coverage rule, source-range check, answer rule, scoring declaration, and prior evidence format. All existing v3 layouts migrate mechanically by changing only `schemaVersion` from 3 to 4. Existing `reviewedMeasurement` overlays and printed-connector records remain unchanged and continue to use their v3 review records.

For an image-hotspot migration, the Group, question IDs, Slot IDs and definitions, ResponseRules, OptionPools, answer data, scoring, and paired-fill semantics stay unchanged. A Slot moves from its former control block to one image overlay. The former out-of-image control for that Slot is removed. A shared OptionPool remains declared and referenced by the unchanged ResponseRule.

Coordinates are natural pixels of the original source image, with origin at the top left. The source ZIP bytes and image are authoritative.

## 2. Image hotspot measurement evidence

V4 overlays support Slot kinds `fill`, `singleChoice`, and `matching`. V4 does not extend overlay placement to `multipleChoice`.

An overlay may retain the inherited human evidence:

    { "kind": "reviewedMeasurement", "reviewId": "review-id", "imageSha256": "64 lowercase hex digits" }

or use model measurement evidence:

    { "kind": "modelMeasurement", "measurementId": "measurement-id", "imageSha256": "64 lowercase hex digits" }

The evidence kind must describe how the rectangle was measured. Model-produced coordinates must use `modelMeasurement`; they must not use `reviewedMeasurement`, a fabricated review ID, or wording that implies human review.

Each model evidence reference has exactly one sidecar record with these fields:

- `measurementId`, `method` (`modelMeasurement`), `partId`, `groupId`, `questionId`, `slotId`, and `slotIndex`;
- `imageAsset`, `imageSha256`, `imageWidthPx`, `imageHeightPx`, and `rectPx`;
- `sourceArchive`, `sourceArchiveSha256`, `sourceEntry`, and `sourceEntrySha256`;
- `visualLocator` and `measurementBasis`.

The sidecar record must identify the same Slot, Group, original image, rectangle, ZIP entry bytes, and image dimensions as the layout. `sourceEntrySha256` must equal the original image SHA-256. The validator verifies the archive hash against the supplied archive-hash map and the entry hash against supplied source bytes. Rectangle bounds are checked against source-image dimensions. Measurement records cannot contain human-review fields such as `reviewId`.

`visualLocator` names the specific printed question blank or label. `measurementBasis` states that the coordinate is a model measurement against the original image at native pixel resolution. This metadata records provenance; it does not claim human verification.

The image remains a single declared Block in its Group. Each Slot appears exactly once: either its existing control block or one image overlay. It cannot appear in both places or in multiple overlays.

## 3. Validation entry point and failures

The dependency-free entry point is:

    validate_layout_v4(layout_bytes, part_json_bytes, *, source_bytes,
                       image_metadata, review_records,
                       model_measurement_records, source_archive_hashes=None)

The validator enforces the inherited v3 rules, then verifies model-measurement identity, image and entry hashes, archive provenance, source-image dimensions, Slot ownership, and exact overlay rectangles. Stable v4 reason codes are `MODEL_MEASUREMENT_MISSING` and `MODEL_MEASUREMENT_INVALID`. Existing human evidence continues to use the inherited `REVIEW_RECORD_MISSING` behavior. Errors expose reason codes only.

Image metadata must be independently read from the original image bytes. `source_bytes` includes the exact safe POSIX ZIP entry used by each model-measurement record, as well as inherited text-source aliases. `source_archive_hashes` maps exact local archive paths to lowercase SHA-256 values.

## 4. V3 to V4 candidate boundary

The only intended V3-to-V4 changes are:

1. all layouts: `schemaVersion: 3` becomes `schemaVersion: 4`;
2. the eight named hotspot Groups: their 43 specified Slots move to measured image overlays, their former out-of-image Slot controls are removed, and each new overlay receives a `modelMeasurement` reference;
3. sidecar provenance and preview/audit artifacts are added outside the layout documents.

All other layouts must have only the version-field change. In the eight target layouts, Group and Slot definitions, question and response membership, OptionPools, response and scoring rules, text, and all non-target display blocks remain byte-equivalent after removing the declared control-to-overlay movement.

The v4 candidate remains offline data. It does not modify the Android app, Grader, saved scores, V2 packs, original ZIPs, or normalized `part.json` files.
