# Duplicate fill-number display cuts

The shared V4 renderer keeps permanent numbers inside answer controls. Existing
line-prefix removal remains in place. `assets/fill-number-cuts.json` adds 507
reviewed cuts across 73 Parts / 12 books from the current Cambridge 5–21 packs.
The catalog contains IDs, original-text SHA-256 and UTF-16 ranges, with no question
text or answers. Existing installed packs need no reimport.

Every cut must match its group, text block hash, fill slot, question ID, question
number, and position before that slot. Changed text or bindings skip that cut.
Unsupported/missing catalogs retain the old prefix behavior. The runtime does not
guess that an arbitrary number inside a sentence is a question number.

`V4FillNumbering.project` joins original annotation slices around hidden ranges.
Currency signs (including full-width pound), quotes, other words and quantities
remain present. Source text, hashes, highlight keys/offsets, answers and grading
remain unchanged. Practice, transcript questions and review share this renderer.
Image pixels and image hotspots are outside this change.

The inventory was checked against the exact source HTML and fill bindings. One
inherited exception is C6 T3 P3 Q25: the layout's `· 25` source range points to
an earlier age, while the intended blank is under Music preferences. This cut
was separately checked against the original blank and bound to the exact layout
block. This change does not relocate existing source annotations.

To regenerate from the private reviewed audit inventory:

```powershell
python tools/build_fill_number_cuts.py --audit .local/review-audit/numbering-20261022.json --packs private-data/layout-v4-android-test-packs --output app/src/main/assets/fill-number-cuts.json
```

Inventory: 480 numbers at text ends, 20 before currency, 4 before opening quotes,
3 followed by words. The overlapping multi-question-row category contains 36
cuts. The previous rule already handles another 103 numbers.

Focused validation (2026-10-22): 7 JVM tests, 3 catalog-builder tests and 10
emulator UI tests passed. The JVM catalog check reads all 17 current packs and
verifies all 507 new cuts plus 103 existing prefix cuts, retained characters and
original annotation mappings. UI checks use real library, currency, quoted and
multi-question layouts, including transcript review, existing highlights,
native copy and saving only the answer. No full suite or physical-device test
was run.
