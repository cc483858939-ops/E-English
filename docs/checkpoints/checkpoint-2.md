# Checkpoint 2 — real local sample import

Completed 2026-10-08 after Checkpoint 1 acceptance. One actual part only, 10 single
choice questions numbered 21–30, three options each. Strict Python conversion,
normalized schema, extensible domain models, asset repository with checksum
validation, test-only synthetic fixtures and private real-resource checks added.
See `../data-format.md` for source schema, limitations and repeatable commands.

Actual results:

- Python importer tests: 8 passed, 0 skipped, including two identical real imports.
- `gradlew testDebugUnitTest`: 7 passed, 0 skipped; actual normalized JSON and MP3
  validated from local ignored assets.
- `gradlew assembleDebug`: BUILD SUCCESSFUL (combined verification 1m45s).
- Initial Gradle DSL compile failed at `systemProperty` receiver; fixed to use
  the unit test task parameter and reran successfully. Failed log is retained
  locally in ignored artifacts; successful aggregate logs are included below.
- UI remains the CP1 shell at this commit; practical playback and answers are CP3.
- No copyrighted JSON/text/MP3 or generated APK is committed. Missing private
  resources cause explicit skips in private integration tests, not fabricated PASS.

Nonblocking tool warnings: newer SDK XML and unstripped Compose path native
library, already present at CP1. Source analysis is login-gated; no explanations
are invented. Timeline duration differs from MP3 by 12.198s; precise sync excluded.
