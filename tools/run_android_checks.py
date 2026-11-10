"""Run C's instrumented checks on one explicitly selected Android device; never clear app data."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT/'work/c-android-results')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = {'passed': False}
    start = time.monotonic()

    def adb(*command, timeout=180):
        result = subprocess.run([args.adb, '-s', args.serial, *command],
                                capture_output=True, timeout=timeout)
        output = (result.stdout + result.stderr).decode('utf-8', errors='replace')
        if result.returncode:
            raise RuntimeError(output)
        return output

    try:
        if adb('get-state').strip() != 'device':
            raise RuntimeError('The selected device is not online')
        report['model'] = adb('shell', 'getprop', 'ro.product.model').strip()
        report['api'] = adb('shell', 'getprop', 'ro.build.version.sdk').strip()
        if args.install:
            for relative in ['app/build/outputs/apk/debug/app-debug.apk',
                             'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
                apk = ROOT/relative
                if not apk.exists():
                    raise FileNotFoundError(apk)
                print(adb('install', '-r', str(apk)), flush=True)
        output = adb('shell', 'am', 'instrument', '-w', '-r',
                     'com.example.bilingualreader.debug.test/androidx.test.runner.AndroidJUnitRunner')
        (args.output/'instrumentation.txt').write_text(output, encoding='utf-8')
        match = re.search(r'OK \((\d+) tests?\)', output)
        print(output, flush=True)
        if match is None or 'FAILURES!!!' in output:
            raise RuntimeError('Instrumentation did not report a successful test suite')
        report.update(passed=True, tests=int(match.group(1)))
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        report['seconds'] = round(time.monotonic()-start, 2)
        (args.output/'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
