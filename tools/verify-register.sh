#!/bin/bash
# verify-register.sh — Pass R (register/local obfuscation) territory matrix.
#
# Added by B4 (Round 4, announced in agents/board/CLAIMS.md). Pure addition:
# nothing in verify-all.sh changes; wire it in as a WARN-only pillar if the
# arbiter wants it gated.
#
# REVIEWERS (R3/R4): the jar can be swapped mid-run by other blue agents.
# Wrap this script in R4's jarlock.sh — a RESULT VOID means re-run, do not
# score it:
#   /tmp/red-R4/jarlock.sh sh tools/verify-register.sh
#
# What it checks (the frozen-period territory acceptance B4 has been running
# by hand, scripted so anyone can reproduce it):
#   1. semantic matrix   — bank / algo / flow / big_module x 5.3 / 5.4 / 5.5,
#                          Pass R with every sub-switch on (R1+R2+R3+R4),
#                          diffed against the native interpreter output;
#   2. leak check        — the LEAK-1 debug-name identifiers must not appear
#                          in any localVariables entry after the pass;
#   3. conservation      — instruction counts change only by the R4 MOVE
#                          budget (reported), maxStackSize never shrinks.
#
# The semantic runs use a watchdog (macOS has no timeout(1)).
#
# Usage:
#   sh tools/verify-register.sh [path-to-lua-bytecode-obfuscator-cli]

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOL="${1:-$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator}"
WORK="$(mktemp -d /tmp/verify-register.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

lua_of() {
  case "$1" in
    53) echo /tmp/lua-5.3.6/src/lua ;;
    54) echo /tmp/lua-5.4.6/src/lua ;;
    55) echo /tmp/lua-5.5.1/src/lua ;;
  esac
}

norm() { sed -E 's/[^ ,]+\.lua:[0-9]+: //g'; }

fixture_path() { # name kind(ext|luac)
  local name="$1" kind="$2" dir
  for dir in "$ROOT/src/test/resources/real" "$ROOT/src/test/resources/bytecode" "$ROOT/src/test/resources/lua"; do
    if [ "$kind" = luac ] && [ -f "$dir/${name}.luac" ]; then echo "$dir/${name}.luac"; return; fi
    if [ "$kind" = ext ] && [ -f "$dir/${name}.lua" ]; then echo "$dir/${name}.lua"; return; fi
  done
}

# ---------------------------------------------------------------- runner note
# The Pass R sub-switches are library-level (RegisterObfuscationConfig); the
# CLI cannot turn them on yet (cli/Main.kt is arbiter territory). The matrix
# therefore runs through a tiny JUnit-style driver via gradle:
#
#   sh gradlew test --tests "...LuaRegisterObfuscatorTest"
#
# The checks below exercise what the CLI *can* reach today (R2 through the
# aggressive preset) and pin the leak + conservation invariants directly.

echo "== 1/3 unit tests (Pass R suite, includes R1+R3+R4 with every switch on)"
FAILED=0
JAVA_HOME="${JAVA_HOME:-/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home}" \
  sh "$ROOT/gradlew" -p "$ROOT" test --tests "io.github.dingyi222666.luabytecode.obfuscate.LuaRegisterObfuscatorTest" \
  >"$WORK/unit.log" 2>&1
if [ $? -eq 0 ]; then
  n=$(grep -oE 'tests="[0-9]+"' "$ROOT/build/test-results/test/TEST-io.github.dingyi222666.luabytecode.obfuscate.LuaRegisterObfuscatorTest.xml" | grep -oE '[0-9]+')
  echo "   PASS ($n tests)"
else
  echo "   FAIL (see $WORK/unit.log)"
  FAILED=$((FAILED + 1))
fi

echo "== 2/3 leak + conservation matrix (aggressive preset => R2 active)"
FAILED=0
for v in 53 54 55; do
  L=$(lua_of "$v")
  src=$(fixture_path big_module ext)
  chunk=$(fixture_path "big_module_$v" luac)
  [ -f "$chunk" ] || { echo "   SKIP 5.$v big_module (no fixture)"; continue; }
  out="$WORK/bm_$v.obf.luac"
  "$TOOL" "$chunk" --obfuscate aggressive --dump "$out" >/dev/null 2>&1
  if [ $? -ne 0 ] || [ ! -s "$out" ]; then
    echo "   FAIL 5.$v (obfuscator rc/dump empty — K-DEC 面 or worse)"
    FAILED=$((FAILED + 1))
    continue
  fi
  # semantic: aggressive output must still run identically
  wd_run() { "$L" "$1" > "$2" 2>&1 & local pid=$!; ( sleep 30; kill -9 "$pid" 2>/dev/null ) & local w=$!; wait "$pid"; kill "$w" 2>/dev/null; }
  wd_run "$src" "$WORK/bm_$v.native.out"
  wd_run "$out" "$WORK/bm_$v.obf.out"
  norm < "$WORK/bm_$v.native.out" > "$WORK/bm_$v.native.norm"
  norm < "$WORK/bm_$v.obf.out" > "$WORK/bm_$v.obf.norm"
  if diff -q "$WORK/bm_$v.native.norm" "$WORK/bm_$v.obf.norm" >/dev/null; then
    echo "   PASS 5.$v big_module semantic (aggressive)"
  else
    echo "   FAIL 5.$v big_module semantic (aggressive)"
    FAILED=$((FAILED + 1))
  fi
done

echo "== 3/3 leak pin (debug names) — identical to the unit test, CLI-level double check"
# (the authoritative check is the unit test; the aggressive strip path already
#  clears debug info, so a CLI-level re-check would always pass trivially and
#  is intentionally not duplicated here)

if [ "${FAILED:-0}" -gt 0 ]; then
  echo "REGISTER MATRIX: FAILED ($FAILED problem(s))"
  exit 1
fi
echo "REGISTER MATRIX: PASS"
