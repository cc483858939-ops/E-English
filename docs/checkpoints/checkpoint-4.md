# Checkpoint 4 — transcript and shared audio

English, Chinese and bilingual transcript displays now use the imported source.
Text scrolls independently of bottom audio controls. Both screens collect the
same Activity ViewModel/controller StateFlow, so entering/reentering the
transcript never constructs another player or prepares the MP3 again.
Presentation mode survives Activity recreation; audio stops on Activity stop and
requires manual resume, and is released when its owner is cleared.

Actual verification 2026-10-08:

- JVM: 7 passed, 0 skipped.
- Debug compilation and API 36 UI integration: BUILD SUCCESSFUL (1m05s).
- UI: 7 passed, 0 skipped/failed, including prior CP3 tests, exact imported
  English/Chinese/bilingual content, scrolling to the final transcript segment,
  answer retained after return, seeking to 60s, continued playback across five
  round trips, same controller identity, and background pause/manual resume.
- Actual system/process termination persistence remains CP5, not established by
  the Activity recreation test. No sentence sync or automatic highlighting added.
- Copyrighted screen captures and APKs stay local. Device audible quality and
  physical hardware testing remain NOT VERIFIED.
