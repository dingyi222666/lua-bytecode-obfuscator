#!/usr/bin/env python3
"""Isolate decoy effects from JMP routing; unsupported original versions are not wins."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    'local function f(a) if a then return 11 else return 29 end end print(f(false),f(true))',
    'local n=tonumber(arg[1]) or 3; if n>2 then print(n+1) else print(n-1) end',
    'local function f(x) local r=0 while x>0 do r=r+x;x=x-1 end return r end print(f(3))',
]


def run(args, timeout=30):
    return subprocess.run([str(x) for x in args], capture_output=True, timeout=timeout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--unluac', type=Path, default=Path('/tmp/unluac.jar'))
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    java = Path(os.environ['JAVA_HOME']) / 'bin/java' if 'JAVA_HOME' in os.environ else 'java'
    tool = ROOT / 'build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator'
    if not args.unluac.is_file() or not tool.is_file():
        parser.error('unluac and installDist must exist; audit NOT executed')
    records = []
    with tempfile.TemporaryDirectory(prefix='jmp-decoy-audit-') as temp:
        temp = Path(temp)
        for version in ('5.3.6', '5.4.6', '5.5.1'):
            lua = Path(f'/tmp/lua-{version}/src/lua')
            luac = lua.with_name('luac')
            for index, source in enumerate(SOURCES):
                src = temp / 'input.lua'
                src.write_text(source)
                original = temp / 'original.luac'
                compiled = run([luac, '-o', original, src])
                if compiled.returncode:
                    raise RuntimeError(compiled.stderr.decode())
                expected = run([lua, original])
                if expected.returncode:
                    raise RuntimeError(expected.stderr.decode())
                baseline = run([java, '-jar', args.unluac, original])
                for decoys in (0, 24):
                    out = temp / 'protected.luac'
                    built = run([tool, original, '--summary', '--jmp', '1', '--jmp-decoys', decoys,
                                 '--seed', '42', '--dump', out])
                    if built.returncode:
                        raise RuntimeError((built.stdout + built.stderr).decode())
                    actual = run([lua, out])
                    if actual.returncode or actual.stdout != expected.stdout:
                        raise RuntimeError(f'VM mismatch {version}/{index}/{decoys}: {actual.stderr!r}')
                    record = dict(vm=version, sample=index, decoys=decoys, native_equal=True,
                                  original_supported=baseline.returncode == 0)
                    if baseline.returncode == 0:
                        try:
                            decompiled = run([java, '-jar', args.unluac, out], timeout=15)
                            record.update(exit=decompiled.returncode, output_bytes=len(decompiled.stdout),
                                          error=decompiled.stderr.decode(errors='replace')[:1000])
                        except subprocess.TimeoutExpired:
                            record['timeout'] = True
                    records.append(record)
    isolated_failures = 0
    for i in range(0, len(records), 2):
        control, decoy = records[i:i + 2]
        if control.get('exit') == 0 and (decoy.get('exit', 0) != 0 or decoy.get('timeout')):
            isolated_failures += 1
    report = dict(unluac_sha256=hashlib.sha256(args.unluac.read_bytes()).hexdigest(),
                  native_checks=len(records), isolated_decompiler_failures=isolated_failures, records=records)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(f'{len(records)} native checks passed; {isolated_failures} isolated decompiler failures')


if __name__ == '__main__':
    main()
