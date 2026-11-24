#!/usr/bin/env python3
"""Local BusinessUnits UI fixture adapter, never a production parser replacement.

Runs the unchanged real parser. For exact manifest-listed workbook hashes only,
selects the requested year/scenario in submissionData. Original bytes, decimal
amounts, source addresses, diagnostics and parser records stay unchanged.
The current backend rejects otherwise valid multi-year workbooks; this explicit
local test adapter allows UI testing without rewriting Excel or approving errors.
"""
import copy
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path


def scope_submission(result, year, month):
    result = copy.deepcopy(result)
    def wanted(value):
        if value.get('year') != year:
            return False
        scenario = value.get('scenario')
        return scenario == 'PLAN' or month is not None and (
            scenario == 'ACTUAL' and value.get('month', 99) <= month or
            scenario == 'ESTIMATE' and value.get('month', 0) > month)
    data = result.get('submissionData') or {}
    for line in data.get('lines', []):
        line['values'] = [v for v in line.get('values', []) if wanted(v)]
    for sheet in data.get('sheets', []):
        sheet['periodColumns'] = [v for v in sheet.get('periodColumns', []) if wanted(v)]
    data['controlValues'] = [v for v in data.get('controlValues', []) if wanted(v)]
    return result


def main():
    payload = sys.stdin.buffer.read()
    real = subprocess.run([sys.executable, *sys.argv[1:]], input=payload, capture_output=True)
    if real.returncode or not os.environ.get('BU_DATASET_SCOPE_ROOT'):
        sys.stdout.buffer.write(real.stdout);sys.stderr.buffer.write(real.stderr)
        return real.returncode
    root = Path(os.environ['BU_DATASET_SCOPE_ROOT'])
    manifest = json.loads((root/'manifest.json').read_text())
    allowed = {item['sha256'] for item in manifest['files']}
    filename = sys.argv[sys.argv.index('--filename')+1] if '--filename' in sys.argv else ''
    match = re.fullmatch(r'F\d+_(2026)-(PLAN|10|11)\.xlsx', filename)
    if hashlib.sha256(payload).hexdigest() in allowed and match:
        result = json.loads(real.stdout)
        result = scope_submission(result, int(match[1]), None if match[2]=='PLAN' else int(match[2]))
        sys.stdout.buffer.write(json.dumps(result,ensure_ascii=False,separators=(',',':')).encode())
    else:
        sys.stdout.buffer.write(real.stdout)
    sys.stderr.buffer.write(real.stderr)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
