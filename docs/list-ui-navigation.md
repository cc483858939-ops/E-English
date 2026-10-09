# Compact library and system Back

2026-10-09. Scope: library UI and navigation only; Room schema, question data, audio controller and annotations are unchanged.

The old book/test levels were `rememberSaveable` values inside the single list destination. They had no system Back callback or NavController entries, so Android could finish the root Activity while a child list was visible. MainActivity has no custom finish-on-Back override; the manifest and current AndroidX dependencies do not require an opt-out from predictive Back.

The list now intercepts Back only while its NavHost destination is active: keyboard first, then search results, Part → Test → books, then an exit confirmation. The toolbar uses the same level transition. Practice and transcript use destination-scoped callbacks with the same `popBackStack` action as their toolbar controls; inactive destinations do not intercept. This uses AndroidX BackHandler / OnBackPressedDispatcher, without horizontal touch listeners or dependency upgrades. See [Android's system Back guidance](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture).

Each list level keeps a separately saved item index and pixel offset. Level values are captured for each LazyColumn content provider so a departing list cannot be emptied by the next level and clamp its old offset. Search text and book/test selection survive destination changes and reconstruction. Keyboard visibility is read from current window insets when Back is dispatched, avoiding a stale Compose value swallowing the next Back after dismissal. Running import dialogs retain their existing non-dismissible behavior; completed dialogs dismiss before page navigation.

Book, Test and Part use grey rounded horizontal cards with 16dp outside margins, 14dp inner horizontal padding, 10dp gaps and a minimum (not fixed) height of 88dp. A small purple action sits on the right. Search/import controls stay compact and accessible. The latest real Room session determines untouched/ongoing/submitted labels and start/resume/result actions; opening still calls the existing ViewModel/repository.

Actual local verification (API 36 emulator):

- `testDebugUnitTest`: 36 passed, 0 failed, 0 skipped. `assembleDebug` and `assembleDebugAndroidTest`: BUILD SUCCESSFUL. Build log: local ignored `artifacts/list-ui-back-current-build.log`.
- 1080×1920, 420dpi, font 1.0, gesture navigation: 13 instrumentation tests passed, none skipped (`artifacts/list-ui-back-normal-current-verified.log`). This includes actual shell-injected left/right edge swipes through transcript → practice → Part → Test → books → exit dialog, not merely clicking toolbar Back.
- 720×1280, 320dpi, font 1.3, three-button navigation: 9 instrumentation tests passed, none skipped (`artifacts/list-ui-back-small-final-verified.log`). Card title/detail/button bounds do not overlap; system Back key injection verifies keyboard-first behavior and import-dialog blocking.
- Tests cover per-level scroll restoration (including saved-state recreation), search dismissal, consistent toolbar/dispatcher navigation, answer recovery after leaving/re-entering, shared audio/transcript modes, fixed player/submit controls, immutable submission, and batch-import refresh. Existing assertions remain; tests use isolated databases and synthetic indexes or local ignored sample assets.
- The original emulator `practice.db` and library catalogue SHA-256 remain identical before/after testing. No database schema, migration, Part IDs, pack resources or business logic changed. Device configuration was restored after testing.

NOT VERIFIED: the user's physical phone/OEM-specific left/right return gestures and Android 8.0 device behavior. No full-library audio sweep or APK delivery is part of this change. Public commits exclude private packs/media and unrelated local launcher-icon changes; `[skip ci]` avoids the existing automatic APK-upload workflow for this source-only delivery.
