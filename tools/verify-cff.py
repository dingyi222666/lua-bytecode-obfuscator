#!/usr/bin/env python3
"""Native, bounded CFF differential matrix; no missing-tool or timeout success."""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = {'53': '5.3.6', '54': '5.4.6', '55': '5.5.1'}
TOOL = ROOT / 'build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator'


def run(argv, timeout):
    result = subprocess.run([str(x) for x in argv], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    return result.returncode, result.stdout.decode('utf-8', errors='replace')


def normalize(output, name):
    # Existing observation contract excludes source location text, not error values.
    output = re.sub(r'[^\s,]+\.lua:\d+: ', '', output)
    if name == 'json':
        # Only object member order is irrelevant; compare ALL values and ALL lines.
        return [json.loads(line) for line in output.splitlines()]
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--modes', nargs='+', choices=['baseline', 'full', 'stress'],
                        default=['baseline', 'full', 'stress'])
    parser.add_argument('--seed', type=int, default=0)
    parser.add_argument('--timeout', type=float, default=float(os.getenv('CFF_TIMEOUT', '15')))
    parser.add_argument('--combinations', action='store_true')
    parser.add_argument('--aggressive-only', action='store_true', help='Recheck only the aggressive combination')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'tools/cff-vm-manifest.json').read_text())
    for suffix, version in VERSIONS.items():
        if not os.access(f'/tmp/lua-{version}/src/lua', os.X_OK):
            parser.error(f'missing required native VM {version}; matrix NOT executed')
        for filename, expected in manifest['builds'][version].items():
            source = Path(f'/tmp/lua-{version}/src/{filename}')
            if not source.exists() or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
                parser.error(f'unverified VM source {version}/{filename}')
    if not TOOL.exists():
        parser.error('build installDist before running the matrix')
    passed = failed = flattened = 0
    features = collections.Counter()
    records = []
    with tempfile.TemporaryDirectory(prefix='cff-matrix-') as temp:
        temp = Path(temp)
        for suffix, version in VERSIONS.items():
            lua = f'/tmp/lua-{version}/src/lua'
            fixtures = []
            for name in ('hello', 'features', 'flow', 'bank', 'algo', 'big_module', 'loops', 'json'):
                paths = [ROOT / f'src/test/resources/{d}/{name}_{suffix}.luac' for d in ('bytecode', 'real')]
                chunk = next((p for p in paths if p.exists()), None)
                if chunk is None:
                    raise RuntimeError(f'missing required fixture {name}_{suffix}')
                fixtures.append((name, chunk, [[], ['--jmp', '3']]))
            fixtures += [(p.stem, p, [[]]) for p in sorted(
                (ROOT / 'src/test/resources/real/ctor').glob(f'c*_{suffix}.luac'))]
            for name, chunk, combinations in fixtures:
                if args.combinations:
                    combinations += [['--strings', '4', '--junk', '2'], ['--obfuscate', 'aggressive']]
                if args.aggressive_only:
                    combinations = [['--obfuscate', 'aggressive']]
                expected_rc, expected = run([lua, chunk], args.timeout)
                expected = normalize(expected, name)
                for mode in args.modes:
                    for flags in combinations:
                        label = f'{version} {name} {mode} {" ".join(flags)}'
                        output = temp / 'out.luac'
                        report = temp / 'report.json'
                        output.unlink(missing_ok=True)
                        report.unlink(missing_ok=True)
                        try:
                            command = [TOOL, chunk, '--summary', *flags, '--cff', mode,
                                       '--seed', str(args.seed), '--cff-report', report, '--dump', output]
                            if mode == 'stress':
                                command += ['--cff-allow-allocation-effects']
                            rc, log = run(command, max(60, args.timeout))
                            if rc != 0 or not output.exists():
                                raise RuntimeError(f'tool failed ({rc}): {log[-2000:]}')
                            got_rc, got = run([lua, output], args.timeout)
                            if got_rc != expected_rc or normalize(got, name) != expected:
                                raise RuntimeError(f'exit {expected_rc} -> {got_rc}; expected {expected!r}; got {got!r}')
                            prototype_reports = json.loads(report.read_text())
                            applied = sum(p['status'] == 'APPLIED' for p in prototype_reports)
                            flattened += applied
                            features.update(f for p in prototype_reports for f in p['actual'])
                            records.append(dict(case=label, applied=applied, prototypes=prototype_reports))
                            passed += 1
                        except (RuntimeError, subprocess.TimeoutExpired, ValueError) as error:
                            failed += 1
                            print(f'FAIL {label}: {error}', flush=True)
            print(f'VM {version}: cumulative {passed} identical / {failed} failed', flush=True)
    if not passed or not flattened:
        failed += 1
        print('FAIL: empty semantic or CFF coverage')
    print(f'CFF matrix: {passed} identical / {failed} failed (flattened prototypes: {flattened})')
    print('Actual features:', dict(sorted(features.items())))
    if args.report:
        args.report.write_text(json.dumps(dict(passed=passed, failed=failed, features=features,
                                               records=records), ensure_ascii=False, indent=2) + '\n')
    return int(failed != 0)


if __name__ == '__main__':
    raise SystemExit(main())
