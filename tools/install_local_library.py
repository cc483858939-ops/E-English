"""Validate/install full private library on an explicitly selected debug emulator.

Uses the production importer only after every validated Part passes device checks.
Does not embed packs in either APK, clear application data, or edit Room records.
"""
import argparse
import base64
import json
import re
from pathlib import Path
import subprocess

from import_library import require, sha

PACKAGE = 'com.eenglish.listening'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    parser.add_argument('--adb', default='.local/android-sdk/platform-tools/adb.exe')
    parser.add_argument('--packs', default='private-data/library/rechecked-batch')
    parser.add_argument('--integrity-report', default='private-data/library/recheck-integrity.json')
    parser.add_argument('--log', default='artifacts/library-full-device.log')
    args = parser.parse_args()
    require(args.device.startswith('emulator-'), 'EXPLICIT_DEBUG_EMULATOR_REQUIRED')
    root = Path(__file__).resolve().parent.parent
    adb = str((root / args.adb).resolve())
    folder = root / args.packs
    report = json.loads((root / args.integrity_report).read_text(encoding='utf-8'))
    require(254 <= report['parts'] <= 272 and report['questions'] == report['parts'] * 10
            and {b['book'] for b in report['books']} == set(range(5, 22)), 'FULL_INTEGRITY_REPORT_REQUIRED')
    expected = {f"cambridge-{book['book']}.eelpack" for book in report['books']}
    require({p.name for p in folder.glob('*.eelpack')} == expected, 'PACK_SET_MISMATCH')
    for book in report['books']:
        pack = folder / f"cambridge-{book['book']}.eelpack"
        require(pack.stat().st_size == book['packBytes'] and sha(pack.read_bytes()) == book['sha256'],
                'PACK_CHANGED_SINCE_RECHECK')

    def run(*command, timeout=180):
        result = subprocess.run([adb, '-s', args.device, *command], capture_output=True, timeout=timeout)
        require(result.returncode == 0, 'ADB_COMMAND_FAILED')
        return result.stdout.decode('utf-8', errors='replace')

    # Install/update without -d, uninstall, pm clear or database deletion.
    for apk in ('app/build/outputs/apk/debug/app-debug.apk',
                'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        require((root / apk).is_file(), 'BUILD_DEBUG_AND_ANDROID_TEST_FIRST')
        require('Success' in run('install', '-r', str(root / apk)), 'APK_INSTALL_FAILED')
    remote = 'files/full-library-packs'
    run('shell', 'run-as', PACKAGE, 'mkdir', '-p', remote)
    for book in sorted(report['books'], key=lambda b: b['book']):
        name = f"cambridge-{book['book']}.eelpack"
        # Windows ADB can interpret binary Ctrl-Z as EOF on stdin. ASCII transfer
        # is verified here and decoded as a stream by the private device test.
        # Normal phone SAF imports still use the original unmodified .eelpack.
        encoded = base64.b64encode((folder / name).read_bytes())
        target = f'{remote}/{name}.b64'
        result = subprocess.run([adb, '-s', args.device, 'shell', '-T', '-e', 'none',
            'run-as', PACKAGE, 'dd', f'of={target}', 'bs=65536', 'status=none'],
            input=encoded, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=180)
        require(result.returncode == 0, 'PRIVATE_PACK_COPY_FAILED')
        copied_hash = run('shell', 'run-as', PACKAGE, 'sha256sum', target).split()[0]
        require(copied_hash == sha(encoded), 'PRIVATE_PACK_COPY_CHECKSUM_MISMATCH')
        print(f"Copied private book {book['book']}", flush=True)
    print(f"Validating all {report['parts']} Parts and local MP3s before publishing book catalogue…", flush=True)
    log = root / args.log
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w', encoding='utf-8') as output:
        process = subprocess.Popen([adb, '-s', args.device, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', f'{PACKAGE}.FullLibraryDeviceTest',
            '-e', 'listening.expectedParts', str(report['parts']),
            '-e', 'listening.installFullLibrary', 'true', f'{PACKAGE}.test/{PACKAGE}.ListeningTestRunner'],
            stdout=output, stderr=subprocess.STDOUT)
        try:
            require(process.wait(timeout=900) == 0, 'DEVICE_CHECK_COMMAND_FAILED')
        finally:
            if process.poll() is None:
                process.terminate()
    result = log.read_text(encoding='utf-8', errors='replace')
    print(result, flush=True)
    require('OK (1 test)' in result and 'FAILURES' not in result
            and 'INSTRUMENTATION_STATUS_CODE: -3' not in result
            and f"FULL_LIBRARY_VERIFIED {report['parts']} Parts" in result, 'FULL_DEVICE_TEST_NOT_PASSED')
    catalogue = json.loads(run('shell', 'run-as', PACKAGE, 'cat', 'files/library/catalog.json'))
    require(set(catalogue) == {str(book) for book in range(5, 22)}, 'INSTALLED_BOOK_COUNT_MISMATCH')
    parts = questions = 0
    for book, directory in catalogue.items():
        require(re.fullmatch(f'book-{book}-[a-f0-9]{{64}}', directory) is not None, 'UNSAFE_CATALOGUE_PATH')
        index = json.loads(run('shell', 'run-as', PACKAGE, 'cat', f'files/library/{directory}/index.json'))
        parts += len(index['parts'])
        questions += sum(part['questionCount'] for part in index['parts'])
    require(parts == report['parts'] and questions == report['questions'], 'INSTALLED_PART_COUNT_MISMATCH')
    # Restart the normal Application, which reads files/library rather than the test directory.
    run('shell', 'am', 'force-stop', PACKAGE)
    require('Status: ok' in run('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/.MainActivity'),
            'NORMAL_APP_START_FAILED')
    print(f"Installed 17 books / {report['parts']} Parts / {report['questions']} questions; normal App restarted.", flush=True)


if __name__ == '__main__':
    main()
