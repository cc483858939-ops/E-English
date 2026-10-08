"""Force-stop/relaunch integration checks on an explicitly disposable emulator.

Creates test attempts through the real UI. Never run on a personal device. Raw
UI dumps, screenshots and database copies contain private exam data: local only.
"""
import argparse
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import time
import tempfile
import xml.etree.ElementTree as ET

PACKAGE = "com.eenglish.listening"


class Device:
    def __init__(self, adb, serial, output):
        self.adb, self.serial, self.output = adb, serial, Path(output)
        self.output.mkdir(parents=True, exist_ok=True)

    def run(self, *args):
        process = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=30)
        if process.returncode:
            raise RuntimeError("ADB command failed (raw output kept private)")
        return process.stdout

    def tree(self):
        self.run("shell", "uiautomator", "dump", "/sdcard/listening-smoke.xml")
        content = self.run("shell", "cat", "/sdcard/listening-smoke.xml")
        (self.output / "window.xml").write_bytes(content)
        return ET.fromstring(content)

    def find(self, predicate, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            root = self.tree()
            for node in root.iter("node"):
                if predicate(node):
                    return root, node
            time.sleep(0.3)
        raise AssertionError("Expected UI state not found; inspect private window.xml")

    def click(self, label=None, prefix=None):
        _, node = self.find(lambda n: n.get("text") == label if label else n.get("text", "").startswith(prefix))
        bounds = list(map(int, re.findall(r"\d+", node.get("bounds"))))
        self.run("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))

    def launch(self):
        output = self.run("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        if b"Status: ok" not in output:
            raise AssertionError("Activity launch failed; install local debug APK first")
        self.find(lambda n: n.get("text") == "试题列表")
        self.click(label="开始练习")
        self.find(lambda n: n.get("text") == "听力答题")

    def snapshot(self):
        self.run("shell", "am", "force-stop", PACKAGE)
        # Each frozen copy has a fresh WAL index. Reusing a previous live SQLite
        # connection/copy can return stale pages after files are overwritten.
        base = Path(tempfile.mkdtemp(prefix="snapshot-", dir=self.output)) / "practice.db"
        for suffix in ["", "-wal"]:
            process = subprocess.run([self.adb, "-s", self.serial, "exec-out", "run-as", PACKAGE,
                                      "cat", "databases/practice.db" + suffix], capture_output=True, timeout=30)
            file = Path(str(base) + suffix)
            if process.returncode == 0:
                file.write_bytes(process.stdout)
            elif suffix == "":
                raise AssertionError("Database not accessible; use debuggable local build")
        connection = sqlite3.connect(base)
        try:
            connection.row_factory = sqlite3.Row
            sessions = [dict(row) for row in connection.execute("SELECT * FROM sessions ORDER BY startedAt DESC")]
            answers = [dict(row) for row in connection.execute("SELECT * FROM answers")]
        finally:
            connection.close()
        return sessions, answers


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", default="artifacts/device-smoke")
    parser.add_argument("--allow-disposable-emulator", action="store_true")
    args = parser.parse_args()
    if not args.allow_disposable_emulator or not args.serial.startswith("emulator-"):
        parser.error("Requires an explicitly disposable Android emulator")
    device = Device(args.adb, args.serial, args.output)
    results = {}
    device.run("shell", "am", "force-stop", PACKAGE)
    device.launch()
    # Reuse a fresh draft, or create one via the submitted result's actual UI.
    if any(n.get("text") == "重新练习" for n in device.tree().iter("node")):
        device.click(label="重新练习")
    device.click(prefix="A. ")
    device.find(lambda n: n.get("text", "").startswith("已保存 ") and not n.get("text", "").startswith("已保存 0 "))
    before, answers_before = device.snapshot()
    attempt = before[0]
    first_id = attempt["id"]
    selected = {a["questionId"]: a["selectedAnswer"] for a in answers_before if a["sessionId"] == first_id}
    questions = json.loads(attempt["questionsJson"])
    assert selected[questions[0]["id"]] == "A"
    device.launch()
    root, option = device.find(lambda n: n.get("text", "").startswith("A. "))
    parents = {child: parent for parent in root.iter() for child in parent}
    assert parents[option].get("checked") == "true", "Saved radio choice not restored"
    results["forceStopAnswerRestoration"] = "PASS"
    expected = sum(selected.get(q["id"]) == q["correctAnswer"] for q in questions)
    device.click(label="提交答案")
    if len(selected) < len(questions):
        device.click(label="确认提交")
    device.find(lambda n: n.get("text", "").startswith(f"正确 {expected} / {len(questions)}"))
    completed, _ = device.snapshot()
    assert completed[0]["id"] == first_id and completed[0]["status"] == "SUBMITTED"
    assert completed[0]["correctCount"] == expected
    device.launch()
    device.find(lambda n: n.get("text", "").startswith(f"正确 {expected} / {len(questions)}"))
    restored, _ = device.snapshot()
    assert restored == completed
    results["forceStopScoreRestoration"] = "PASS"
    device.launch()
    device.click(label="重新练习")
    device.find(lambda n: n.get("text") == f"已保存 0 / {len(questions)} 题")
    new, new_answers = device.snapshot()
    assert new[0]["id"] != first_id and new[0]["status"] == "IN_PROGRESS"
    assert next(row for row in new if row["id"] == first_id) == completed[0]
    assert not any(row["sessionId"] == new[0]["id"] for row in new_answers)
    results["newAttemptPreservesHistory"] = "PASS"
    device.launch()
    (device.output / "screen.png").write_bytes(device.run("exec-out", "screencap", "-p"))
    (device.output / "results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(results))


if __name__ == "__main__":
    main()
