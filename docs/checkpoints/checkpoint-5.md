# Checkpoint 5 — durable answers, grading and history

Room stores sessions and one answer per (session, question) key. UI selection
events are serialized; choices are displayed/acknowledged only after the write
commits. Submission runs in a Room transaction with a conditional status update,
so concurrent/repeated requests return the same immutable score record. Missing
answers require a confirmation dialog and count as wrong. Each attempt keeps a
question snapshot; future asset updates cannot rewrite past answers or grades.
New practice creates/reuses a fresh draft and preserves old completed attempts.
Practice records can be opened from the history list. No answer is displayed
before the selected attempt is submitted.

Actual results 2026-10-08:

- JVM unit tests: 12 passed, 0 skipped (data, grading and saved presentation state).
- Debug application and test APK: compile successful, Room schema v1 exported.
- API 36 emulator: 15 instrumented tests passed, 0 skipped/failed (1m32s combined).
- Covered 10/10 and 8/10 through real UI, missing-answer confirmation/cancellation,
  1/10 partial submission, standard answers after submission, immutable choices,
  history navigation, retry preserving previous scores, rapid choice ordering,
  eight concurrent submissions yielding one score, concurrent resume yielding one
  draft, invalid answers rejected, and closing/reopening a file-backed database.
- Previous transcript/navigation/background/playback tests continue to pass.
- UI test runner uses `instrumentation-practice.db`; repository tests use
  `repository-test.db`. Production `practice.db` is never cleared by tests.

Remaining full integration checks: actual force-stop/relaunch persistence,
airplane mode, smaller display/font layout, final source/repository consistency.
Physical hardware and audible sound remain NOT VERIFIED. Temporary debug APKs
are local test products, not a final installation deliverable or a Release.
