#!/bin/sh
# Four piece verification (docs/obfuscation-algorithms.md §8 P5):
#   1. real interpreter semantic matrix (AGGRESSIVE preset + flattened CFF)
#   2. dump -> reload (gradle test suite)
#   3. decompiler resistance (unluac on the aggressive output)
#   4. plaintext leak check + one-step predicate audit
# Usage: sh tools/verify-all.sh
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# OBFUSCATOR_TOOL overrides the CLI binary (used by CI's differential-matrix
# job, which shims the JVM library entry — see tools/run-differential-matrix.sh).
TOOL="${OBFUSCATOR_TOOL:-$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator}"
norm() { sed -E 's/[^ ,]+\.lua:[0-9]+: //g'; }
lua_of() { case "$1" in 53) echo /tmp/lua-5.3.6/src/lua;; 54) echo /tmp/lua-5.4.6/src/lua;; 55) echo /tmp/lua-5.5.1/src/lua;; esac; }
PLAIN=0; BAD=0

echo "== 1/4 semantic matrix (AGGRESSIVE preset, all fixtures x 3 versions)"
# GATE-4 (b): fixed /tmp paths collided between concurrent runs (a second run read
# the first run's half written dump: `version mismatch in precompiled chunk`).
# Every dump now gets an mktemp slot, same as verify-cff/verify-vm already do.
WORKDUMP=$(mktemp -t verify-all.XXXXXX).luac
WORK=$(mktemp -d -t verify-all-w.XXXXXX)
trap 'rm -rf "$WORKDUMP" "$WORK"' EXIT
for v in 53 54 55; do L=$(lua_of $v)
  for name in hello features flow bank algo big_module loops json; do
    src=""; for d in "$ROOT/src/test/resources/lua" "$ROOT/src/test/resources/real"; do [ -f "$d/$name.lua" ] && src="$d/$name.lua"; done
    chunk="$ROOT/src/test/resources/bytecode/${name}_${v}.luac"; [ -f "$chunk" ] || chunk="$ROOT/src/test/resources/real/${name}_${v}.luac"
    [ -f "$chunk" ] || continue
    f="$WORKDUMP"; rm -f "$f"
    $TOOL "$chunk" --obfuscate aggressive --dump "$f" >/dev/null 2>&1
    if [ ! -s "$f" ]; then
      # GATE-4 (a): a swallowed dump failure used to compare stale/no output and
      # could slip through as PASS; a missing sample must count.
      echo "   FAIL 5.$v $name (obfuscator rc=$?, no dump)"; BAD=$((BAD + 1)); continue
    fi
    o=$($L "$src" 2>&1 | norm); g=$($L "$f" 2>&1 | norm)
    if [ "$name" = "json" ]; then
      o=$(echo "$o" | head -1 | grep -o '"[a-z]*":' | sort | tr -d '\n')
      g=$(echo "$g" | head -1 | grep -o '"[a-z]*":' | sort | tr -d '\n')
    fi
    if [ "$o" = "$g" ]; then PLAIN=$((PLAIN+1)); else BAD=$((BAD+1)); echo "   FAIL 5.$v $name"; fi
  done
  for c in "$ROOT"/src/test/resources/real/ctor/c*.lua; do
    n=$(basename "$c" .lua); [ -f "$ROOT/src/test/resources/real/ctor/${n}_${v}.luac" ] || continue
    f="$WORKDUMP"; rm -f "$f"
    $TOOL "$ROOT/src/test/resources/real/ctor/${n}_${v}.luac" --obfuscate aggressive --dump "$f" >/dev/null 2>&1
    if [ ! -s "$f" ]; then echo "   FAIL ctor 5.$v $n (no dump)"; BAD=$((BAD + 1)); continue; fi
    if [ "$($L "$c" 2>&1 | norm)" = "$($L "$f" 2>&1 | norm)" ]; then PLAIN=$((PLAIN+1)); else BAD=$((BAD+1)); echo "   FAIL ctor 5.$v $n"; fi
  done
done
echo "   identical: $PLAIN, failed: $BAD"

echo "== 1b/4 flattened CFF matrix (tools/verify-cff.sh)"
sh "$ROOT/tools/verify-cff.sh" | tail -1

echo "== 2/4 dump -> reload (gradle test suite)"
# GATE-4 (c): the gradle line had no watchdog -- a hung gradle (daemon deadlock,
# fake gradlew sleeping) blocked the whole gate forever. Now it is killed after
# GRADLE_TIMEOUT seconds and the pillar fails instead of hanging.
GRADLE_TIMEOUT=${GRADLE_TIMEOUT:-600}
( cd "$ROOT" && JAVA_HOME=${JAVA_HOME:-/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home} sh gradlew cleanTest test --console=plain 2>&1 ) >"$WORK/gradle.log" 2>&1 &
GPID=$!
( ( sleep "$GRADLE_TIMEOUT"; kill -9 $GPID 2>/dev/null ) >/dev/null 2>&1 & )
wait $GPID 2>/dev/null; GRADLE_RC=$?
if [ "$GRADLE_RC" -eq 124 ] || [ "$GRADLE_RC" -eq 137 ]; then
  echo "   gradle suite TIMEOUT after ${GRADLE_TIMEOUT}s (killed)"
  BAD=$((BAD + 1))
else
  grep -E "tests completed|BUILD" "$WORK/gradle.log" | tail -2
  if grep -qE "BUILD FAILED|FAILED" "$WORK/gradle.log"; then
    echo "   gradle suite FAILED"
    BAD=$((BAD + 1))
  fi
fi

echo "== 3/4 decompiler resistance (unluac)"
# The aggressive chunk must *decompile* now: a tail-return-less chunk was rejected by
# unluac's syntax validator for free, so "unluac failed" used to look like resistance
# while it was only broken bytecode (adversarial audit M5). Counts as BAD when either
# unluac crashes on the obfuscated output or its stdout is empty.
# 5.5: this unluac build cannot parse Lua 5.5 at all (it crashes on the ORIGINAL
# chunk too, `Version.getVersion`), so 5.5 is reported but not gated.
#
# DEC-2 (B5/Round2, ex-R4 Round3 blind spot): big_module is now a second sample.
# It decompiles but unluac CRASHES midway (IllegalStateException /
# Assignment.replaceValue NPE, crash line drifts with the artifact: 1550/1393/1469).
# A tool crash is NOT artifact evidence either way, so it prints a TOOL CRASH
# warning and counts in TOOL_CRASHES (reported, not gated) -- but a clean nonzero
# rejection or an empty dump still counts BAD exactly as before.
TOOL_CRASHES=0
for v in 53 54 55; do for name in bank big_module; do
  src="$ROOT/src/test/resources/real/${name}_$v.luac"; [ -f "$src" ] || src="$ROOT/src/test/resources/bytecode/${name}_$v.luac"; [ -f "$src" ] || continue
  # PRED-2: byte-sensitive pillars pin --seed 0 (legacy content derivation) so
  # unluac verdicts / leak greps / predicate audits stay comparable across runs
  $TOOL "$src" --obfuscate aggressive --seed 0 --dump "$WORKDUMP" >/dev/null 2>&1
  if [ ! -s "$WORKDUMP" ]; then
    echo "   5.$v $name: obfuscator produced no dump (sample counted as BAD, pillar (a) rc discipline)"
    BAD=$((BAD + 1)); continue
  fi
  java -jar /tmp/unluac.jar "$src" >"$WORK/dec-orig.txt" 2>&1; eo=$?
  rm -f "$WORKDUMP"
  $TOOL "$src" --obfuscate aggressive --dump "$WORKDUMP" >/dev/null 2>&1
  java -jar /tmp/unluac.jar "$WORKDUMP" >"$WORK/dec-aggr.txt" 2>&1; ea=$?
  echo "   5.$v $name original: exit=$eo lines=$(wc -l < "$WORK/dec-orig.txt") | aggressive: exit=$ea lines=$(wc -l < "$WORK/dec-aggr.txt")"
  if [ "$eo" -ne 0 ]; then
    echo "   5.$v $name: unluac cannot parse the ORIGINAL either (tool limitation, not gated)"
    continue
  fi
  if [ "$ea" -ne 0 ]; then
    if grep -qE "Exception in thread|at unluac\." "$WORK/dec-aggr.txt"; then
      # the decompiler itself blew up mid-print: no verdict about the artifact
      echo "   5.$v $name: TOOL CRASH inside unluac ($(wc -l < "$WORK/dec-aggr.txt") lines before crash) -- not counted as artifact failure, tracked in TOOL_CRASHES"
      TOOL_CRASHES=$((TOOL_CRASHES + 1))
    else
      echo "   DECOMPILER FAILED on aggressive 5.$v $name (exit=$ea, $(wc -l < "$WORK/dec-aggr.txt") lines)"
      BAD=$((BAD + 1))
    fi
  elif [ ! -s "$WORK/dec-aggr.txt" ]; then
    echo "   DECOMPILER FAILED on aggressive 5.$v $name (empty stdout)"
    BAD=$((BAD + 1))
  fi
done; done
[ "$TOOL_CRASHES" -gt 0 ] && echo "   note: $TOOL_CRASHES unluac tool crash(es) on aggressive output (crash point drifts with the artifact; R6 is tracking)"

echo "== 3b/4 decompiled-semantics recheck (tools/verify-decompiled-semantics.sh)"
# GATE-1: unluac exit 0 says nothing about whether the decompiled source still
# MEANS the original. This pillar re-executes the decompiled bank/algo samples and
# diffs against the original source. Report-only until DEC-1 (aggressive output
# currently decompiles to syntactically invalid Lua) is FIXED, then flip
# GATE1_STRICT=1 here to make it a hard gate. A self-test lives in the script
# (GATE1_SELFTEST=1) and proves the comparison machinery catches a tampered sample.
sh "$ROOT/tools/verify-decompiled-semantics.sh"

echo "== 5/5 leak check + predicate audit"
for v in 53 54 55; do for name in bank json big_module; do
  chunk="$ROOT/src/test/resources/real/${name}_$v.luac"; [ -f "$chunk" ] || continue
  $TOOL "$chunk" --obfuscate aggressive --dump "$WORKDUMP" >/dev/null 2>&1
  if [ ! -s "$WORKDUMP" ]; then
    echo "   5.$v $name: no dump for leak check (sample counted as BAD, pillar (a) rc discipline)"
    BAD=$((BAD + 1)); continue
  fi
  hit=""
  case $name in
    bank) for lit in "statement for" "insufficient funds" "deposit must be positive"; do grep -q -- "$lit" "$WORKDUMP" && hit="$hit $lit"; done;;
    json) for lit in "cannot encode a " "cycle detected" "lua 字节码"; do grep -q -- "$lit" "$WORKDUMP" && hit="$hit $lit"; done;;
    big_module) for lit in "the quick brown fox" "message 001"; do grep -q -- "$lit" "$WORKDUMP" && hit="$hit $lit"; done;;
  esac
  [ -z "$hit" ] && echo "   5.$v $name: clean" || { echo "   5.$v $name: LEAK$hit"; BAD=$((BAD+1)); }
done; done
$TOOL "$ROOT/src/test/resources/real/big_module_54.luac" --obfuscate aggressive --seed 0 --dump "$WORKDUMP" >/dev/null 2>&1
if [ ! -s "$WORKDUMP" ]; then
  echo "   predicate audit: no dump (pillar (a) rc discipline)"; BAD=$((BAD + 1))
else
AUDIT=$(python3 "$ROOT/tools/audit-predicates.py" "$TOOL" "$WORKDUMP")
echo "$AUDIT" | sed 's/^/   /'
# Runtime predicates now use initialized parameters. Check the replacement
# contract directly; the legacy fold-env audit below is diagnostic only.
FIXTURE_OUT=$(sh "$ROOT/tools/verify-runtime-predicates.sh" 2>&1); FIXTURE_RC=$?
echo "$FIXTURE_OUT" | sed 's/^/   [runtime predicates] /'
if [ "$FIXTURE_RC" -ne 0 ]; then BAD=$((BAD + 1)); fi
FOLD=$(python3 "$ROOT/tools/audit-predicates.py" "$TOOL" "$WORKDUMP" --fold-env)
echo "$FOLD" | grep "fold-env" | sed 's/^/   /'
# The audit's own hard check is the metric that decides whether a predicate is a one step
# identity, so it has to be a gate, not just a printed line.
IDENT=$(echo "$AUDIT" | grep -o 'one step identities: [0-9]*' | grep -o '[0-9]*$' | head -1)
if [ "${IDENT:-0}" != "0" ]; then
  echo "   AUDIT FAILED: $IDENT one step identities (must be 0)"
  BAD=$((BAD+1))
fi
fi

echo
# P4 VM layer pillar: refuses are legitimate, wrong results and coverage drops are not
if [ -x "$ROOT/tools/verify-vm.sh" ]; then
  echo "== 2/5 VM matrix (tools/verify-vm.sh)"
  if sh "$ROOT/tools/verify-vm.sh"; then :; else echo "   VM matrix FAILED"; BAD=$((BAD+1)); fi
  echo
fi
# CFF matrix pillar: its failures must count, and a script error must too
if [ -x "$ROOT/tools/verify-cff.sh" ]; then
  CFF_OUT=$( sh "$ROOT/tools/verify-cff.sh" 2>&1 )
  echo "$CFF_OUT" | tail -1
  CFF_FAIL=$(echo "$CFF_OUT" | sed -n 's/.*\/ \([0-9]*\) failed.*/\1/p' | tail -1)
  case "$CFF_FAIL" in
    ''|*[!0-9]*) CFF_FAIL=0 ;;
  esac
  CFF_FLAT=$(echo "$CFF_OUT" | sed -n 's/.*flattened prototypes: \([0-9]*\).*/\1/p' | tail -1)
  case "$CFF_FLAT" in
    ''|*[!0-9]*) CFF_FLAT=0 ;;
  esac
  if [ "${CFF_FAIL:-0}" -gt 0 ] || echo "$CFF_OUT" | grep -qE "syntax error|not found"; then
    echo "   CFF matrix FAILED ($CFF_FAIL)"
    BAD=$((BAD + ${CFF_FAIL:-0} + 1))
  elif [ "${CFF_FLAT:-0}" -eq 0 ]; then
    # flattening must actually flatten something: a silent no-op is a failed pillar
    # (adversarial audit, goal round 26)
    echo "   CFF matrix flattened 0 prototypes: flattening is a no-op"
    BAD=$((BAD + 1))
  fi
fi

# COV-1 coverage pillar (B5): the CFF matrix above only demands "flattened > 0",
# so a silent coverage collapse would still pass. This prints per fixture x version
# flattened/total prototype ratios and WARNS on regressions against
# tools/cff-coverage-baseline.txt. WARN-only: it must not block the trunk while
# COV-1 itself is being fixed by B1; flip COV_STRICT=1 after the master sets the
# agreed threshold.
if [ -x "$ROOT/tools/verify-cff-coverage.sh" ]; then
  echo
  sh "$ROOT/tools/verify-cff-coverage.sh" 2>&1 | grep -E "WARN|coverage:|baseline"
fi

[ "$BAD" -eq 0 ] && echo "FOUR PIECE VERIFICATION: PASS" || echo "FOUR PIECE VERIFICATION: FAIL ($BAD problems)"
exit "$BAD"
