"""Native highlight write -> actual process stop -> native highlight restore.

Uses only synthetic androidTest text and annotation-process-test.db. Never clears
practice.db. Requires installed local debug + androidTest APKs; no APK upload.
"""
import argparse
import json
from pathlib import Path
import subprocess

PACKAGE = "com.eenglish.listening"
TEST_PACKAGE = PACKAGE + ".test"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-disposable-emulator", action="store_true")
    parser.add_argument("--output", default="artifacts/annotations-process")
    args = parser.parse_args()
    if not args.allow_disposable_emulator or not args.serial.startswith("emulator-"):
        parser.error("Only an explicitly disposable emulator is supported")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    def run(*command, allow_failure=False):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, timeout=120)
        text = (result.stdout + result.stderr).decode("utf-8", errors="replace")
        if result.returncode and not allow_failure:
            raise RuntimeError("ADB failed; inspect local diagnostics")
        return result.returncode, text

    def phase(name):
        _, text = run("shell", "am", "instrument", "-w", "-e", "class",
                      PACKAGE + ".AnnotationProcessTest", "-e", "annotationPhase", name,
                      TEST_PACKAGE + "/" + PACKAGE + ".ListeningTestRunner")
        (output / (name + ".log")).write_text(text, encoding="utf-8")
        if "OK (1 test)" not in text or "FAILURES!!!" in text or "INSTRUMENTATION_FAILED" in text:
            raise AssertionError("Process phase failed: " + name)

    phase("write")
    for package in [PACKAGE, TEST_PACKAGE]:
        run("shell", "am", "force-stop", package)
        _, pid = run("shell", "pidof", package, allow_failure=True)
        if pid.strip():
            raise AssertionError("Process was not fully stopped")
    phase("read")
    result = {"write_native_ui": "PASS", "force_stop_and_pid_absent": "PASS",
              "read_native_ui_in_new_process": "PASS", "tests": 2, "skipped": 0,
              "fixture": "synthetic", "database": "annotation-process-test.db"}
    (output / "summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
