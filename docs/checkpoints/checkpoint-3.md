# Checkpoint 3 — real questions and local playback

Actual sample is loaded through the asset repository. The only imported part is
listed, all 10 questions render from data, single choices can be replaced, and
the scrolling question list is independent of local MP3 play/pause/seek controls.
Player duration comes from Media3, with system audio focus/volume/noisy handling.
One Activity ViewModel owns and releases the player; Activity stop pauses it.

Verification on 2026-10-08:

- JVM tests: 7 passed, 0 skipped.
- Debug app and test APK compile: BUILD SUCCESSFUL.
- API 36 default x86_64 emulator, WHPX: 5 UI tests passed, 0 skipped/failed.
  Covered empty import state, every question number 21–30, replacing one radio
  choice, no pre-submit standard answers, navigation/system back, Activity
  recreation/mode retention, actual MP3 duration/play/pause/resume and seeking.
- Windows verification helper initially aborted on an SDK stderr warning and
  process execution policy. Fixed native-warning handling; helper can be run with
  process-only `-ExecutionPolicy Bypass`. System policy is not changed.

At this stage answers are ViewModel state only; durable storage and grading are
explicitly deferred to CP5. Transcript body remains a placeholder until CP4.
No copyrighted screenshot, asset or APK is included in this commit. Emulator
host sound output is disabled; audible quality on physical hardware is unverified.
