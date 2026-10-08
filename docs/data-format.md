# Local listening assets, schema v1

Only the user-supplied Cambridge 9 / Test 1 / Part 3 is imported. Original data and
normalized JSON/MP3 are excluded from Git. No exam content is embedded in UI code.

Run from the project root:

```powershell
python tools/import_sample.py D:/Cambridge_9_Test_1_Part_3_Codex_Sample.zip
$env:LISTENING_SAMPLE_ZIP='D:/Cambridge_9_Test_1_Part_3_Codex_Sample.zip'
python -m unittest discover -s tools -v
```

An extracted Part3 directory can also be supplied. Output defaults to
`app/src/main/assets/listening/cambridge-9-test-1-part-3/`. Repeat runs produce
identical JSON and audio. Input files are never modified. All inputs are validated
before output is written. Unknown wrapped question lines, duplicate JSON keys,
missing/duplicate numbers, missing options, ambiguous accepted answers and
disagreeing answer/provenance maps fail with explicit errors. Output messages do
not include answer values or copyrighted question text.

The plain answer file and all supplied HTML question snapshots are also read and
cross-checked against question text/options. HTML is treated as inert source data.
The source content page must reference the single local MP3. It is never loaded
in a WebView. Timeline ranges/count/max-end metadata are validated, with no claim
that source timings align precisely with the MP3.

The normalized part has `schemaVersion`, stable `id`, `examType`, book/test/part,
title, instructions, asset-relative audio path, SHA-256, transcript and questions.
Transcript includes complete English/Chinese and ordered bilingual segments.
Questions have stable IDs, number, extensible type, prompt, options and
`correctAnswer`. Options contain ID and text. SINGLE_CHOICE is the only v0.1
renderer/grader; other enum values reserve future extension, and unsupported
renderers fail resource validation instead of displaying incorrect controls.

Source observations:

- `part.json` is metadata, not question content. `questions.txt` uses a strict
  numbered prompt plus A/B/C grammar; this importer deliberately does not infer
  arbitrary multiline formats.
- `answers.json` has entries, accepted answers, maps and extraction provenance.
  All are cross-checked. Its HTML analysis blocks are login gates, so no actual
  explanations are available. The app must not invent explanations.
- 51 transcript segments agree with the English, Chinese and bilingual files.
  Bilingual timestamps are validated as text markers, then discarded for display.
- Audio metadata is 427.598 seconds; timeline maximum is 415.400 seconds. This
  source warning is informational; playback uses the MP3's measured duration.
- Translations are preserved verbatim, including any source wording/speaker
  errors. No inferred corrections or precision audio synchronization are applied.

The asset repository validates the normalized schema and MP3 SHA-256 before
exposing a part as playable. Public checkouts have no exam assets and must show
an empty import state. Tests containing synthetic prompts are parser fixtures
only. The real-asset JVM test reports SKIPPED when private resources are absent;
the private importer test requires `LISTENING_SAMPLE_ZIP` explicitly.
