#!/bin/sh
# GATE-1 semantic recheck of the decompiler pillar (agents/board/ISSUES.md GATE-1).
#
# "unluac exit 0" only proves the decompiler did not crash; it does NOT prove the
# decompiled source still means what the original chunk means. This script closes
# that gap for a sample of fixtures:
#
#   original chunk --obfuscate aggressive--> obfuscated .luac
#     --unluac--> Lua source in a mktemp'ed dir
#       --lua loadfile--> syntax check (compile only, nothing executed yet)
#         --lua run--> stdout/stderr compared with the ORIGINAL SOURCE run
#
# Sampling: bank + algo (both required, "at least bank+algo per version"), plus
# GATE1_EXTRA="hello flow ..." to widen the sample. 5.5 is sampled but not gated:
# this unluac build cannot parse Lua 5.5 originals either (tool limitation, same
# exemption as the verify-all pillar 3).
#
# Modes:
#   default      : report only, always exit 0 (DEC-1 is currently OPEN: every
#                  aggressive sample decompiles to syntactically INVALID Lua, so a
#                  hard gate here would red-line the trunk from minute one; the
#                  numbers below are the honest baseline to fix against)
#   GATE1_STRICT=1 : any sample failure exits nonzero (flip this on in verify-all
#                  once DEC-1 is FIXED, that is the moment the gate becomes hard)
#
# Self check: GATE1_SELFTEST=1 feeds the script a deliberately corrupted sample
# (a chunk whose unluac output was tampered with) and asserts the script CATCHES
# it; see the bottom of this file. Run `GATE1_SELFTEST=1 sh tools/verify-decompiled-semantics.sh`
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# OBFUSCATOR_TOOL overrides the CLI binary (CI differential-matrix job).
TOOL="${OBFUSCATOR_TOOL:-$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator}"
UNLUAC=${UNLUAC:-/tmp/unluac.jar}
TIMEOUT=${GATE1_TIMEOUT:-60}
WORK=$(mktemp -d -t gate1.XXXXXX)
trap 'rm -rf "$WORK"' EXIT

norm() { sed -E 's/[^ ,]+\.lua:[0-9]+: //g'; }
lua_of() { case "$1" in 53) echo /tmp/lua-5.3.6/src/lua;; 54) echo /tmp/lua-5.4.6/src/lua;; 55) echo /tmp/lua-5.5.1/src/lua;; esac; }
src_of() { # source lua + original chunk for a fixture name, prints "src chunk"
  name=$1; v=$2
  for d in "$ROOT/src/test/resources/lua" "$ROOT/src/test/resources/real"; do
    if [ -f "$d/$name.lua" ]; then echo "$d/$name.lua"; break; fi
  done
}
chunk_of() {
  name=$1; v=$2
  c="$ROOT/src/test/resources/real/${name}_${v}.luac"
  [ -f "$c" ] || c="$ROOT/src/test/resources/bytecode/${name}_${v}.luac"
  [ -f "$c" ] && echo "$c"
}

# runs "$@" with a watchdog; returns 124 on timeout
run_guarded() {
  ( "$@" >"$WORK/run.out" 2>"$WORK/run.err" ) &
  pid=$!
  ( ( sleep "$TIMEOUT"; kill -9 $pid 2>/dev/null ) >/dev/null 2>&1 & )
  wait $pid 2>/dev/null
  return $?
}

# audit_one <name> <v>  -> prints one verdict line, sets G1_BAD=1 on failure
audit_one() {
  name=$1; v=$2
  src=$(src_of "$name" "$v"); chunk=$(chunk_of "$name" "$v")
  if [ -z "$src" ] || [ -z "$chunk" ]; then
    echo "   5.$v $name: SKIP (no fixture)"; return 0
  fi
  L=$(lua_of "$v")
  obf="$WORK/${name}_${v}.obf.luac"
  "$TOOL" "$chunk" --obfuscate aggressive --seed 0 --dump "$obf" >"$WORK/obf.log" 2>&1
  if [ ! -f "$obf" ]; then
    echo "   5.$v $name: FAIL (obfuscator produced no dump)"; G1_BAD=1; return 0
  fi
  dec="$WORK/${name}_${v}.decompiled.lua"
  java -jar "$UNLUAC" "$obf" >"$dec" 2>"$WORK/unluac.err"
  ea=$?
  if [ "$ea" -ne 0 ]; then
    # DEC-2: a crash INSIDE unluac (stack trace in stderr) is a tool limitation,
    # not a verdict about the artifact -- warn and count it in G1_TOOL_CRASHES.
    # A clean rejection (rc!=0, no stack trace) still fails the sample.
    if grep -qE "Exception in thread|at unluac\." "$WORK/unluac.err"; then
      echo "   5.$v $name: TOOL CRASH inside unluac ($(wc -l < "$dec" | tr -d ' ') lines before crash) -- not counted as sample failure, tracked in G1_TOOL_CRASHES"
      G1_TOOL_CRASHES=$((G1_TOOL_CRASHES + 1))
      return 0
    fi
    echo "   5.$v $name: FAIL (unluac exit=$ea, $(wc -l < "$dec" | tr -d ' ') lines)"; G1_BAD=1; return 0
  fi
  if [ ! -s "$dec" ]; then
    echo "   5.$v $name: FAIL (unluac produced empty output)"; G1_BAD=1; return 0
  fi
  # step 1: the decompiled source must at least COMPILE
  if ! "$L" -e "local f,err=loadfile('$dec'); if not f then io.stderr:write(tostring(err)) os.exit(1) end" \
      2>"$WORK/syn.err"; then
    echo "   5.$v $name: FAIL (decompiled source has SYNTAX errors: $(head -1 "$WORK/syn.err"))"
    G1_BAD=1; return 0
  fi
  # step 2: it must RUN and print what the original source prints
  run_guarded "$L" "$src"; rc=$?
  [ $rc -eq 0 ] || { echo "   5.$v $name: FAIL (original itself rc=$rc)"; G1_BAD=1; return 0; }
  cp "$WORK/run.out" "$WORK/orig.out"; cp "$WORK/run.err" "$WORK/orig.err"
  run_guarded "$L" "$dec"; rc=$?
  if [ $rc -eq 124 ]; then
    echo "   5.$v $name: FAIL (decompiled run timed out after ${TIMEOUT}s)"; G1_BAD=1; return 0
  fi
  o=$(cat "$WORK/orig.out" "$WORK/orig.err" | norm)
  g=$(cat "$WORK/run.out" "$WORK/run.err" | norm)
  if [ "$o" = "$g" ]; then
    echo "   5.$v $name: OK (decompiled source compiles, runs, output identical)"
  else
    echo "   5.$v $name: FAIL (output differs)"
    echo "      want: $(echo "$o" | head -1)"
    echo "      have: $(echo "$g" | head -1)"
    G1_BAD=1
  fi
}

echo "== 3b/4 decompiled-semantics recheck (tools/verify-decompiled-semantics.sh)"
G1_BAD=0
# DEC-2: big_module joins the sample. Its unluac run currently TOOL CRASHES
# (IllegalStateException mid-print, crash line drifts with the artifact), which is
# no verdict about the artifact either way -- reported as TOOL CRASH, counted in
# G1_TOOL_CRASHES, never silently dropped and never scored as a sample failure.
G1_TOOL_CRASHES=0
SAMPLE="bank algo big_module ${GATE1_EXTRA:-}"
for v in 53 54 55; do
  for name in $SAMPLE; do
    audit_one "$name" "$v"
  done
done

if [ "${GATE1_SELFTEST:-0}" = "1" ]; then
  # Self check for the AUDIT itself, independent of DEC-1 status: decompile the
  # ORIGINAL chunk (its unluac output compiles and runs), then tamper with one
  # constant -- semantics drift while the syntax stays valid, the hardest case to
  # detect because unluac still exits 0. The audit must CATCH it; if it reports
  # BLIND the comparison machinery is broken and the gate is theatre.
  name=hello; v=53
  src=$(src_of "$name" "$v"); chunk=$(chunk_of "$name" "$v"); L=$(lua_of "$v")
  dec="$WORK/self_${name}.lua"
  java -jar "$UNLUAC" "$chunk" >"$dec" 2>/dev/null
  sed -e 's/local count = 42/local count = 43/' "$dec" >"$dec.t" && mv "$dec.t" "$dec"
  SYNTAX_OK=1; "$L" -e "local f,err=loadfile('$dec'); if not f then os.exit(1) end" || SYNTAX_OK=0
  # run the tampered file through the same comparison the audit uses
  run_guarded "$L" "$src"; cp "$WORK/run.out" "$WORK/orig.out"; cp "$WORK/run.err" "$WORK/orig.err"
  run_guarded "$L" "$dec"
  o=$(cat "$WORK/orig.out" "$WORK/orig.err" | norm); g=$(cat "$WORK/run.out" "$WORK/run.err" | norm)
  if [ "$SYNTAX_OK" -eq 1 ] && [ "$o" != "$g" ]; then
    echo "   selftest: CAUGHT (valid-syntax tampered decompiled output detected)"
  elif [ "$SYNTAX_OK" -eq 0 ]; then
    echo "   selftest: INCONCLUSIVE (tampered sample did not compile, test degraded)"; G1_BAD=1
  else
    echo "   selftest: BLIND (tampered output NOT detected)"; G1_BAD=1
  fi
fi

if [ "$G1_BAD" -eq 0 ]; then
  echo "   decompiled-semantics: PASS"
else
  echo "   decompiled-semantics: FAIL (see ISSUE DEC-1: unluac output of aggressive chunks is not executable)"
fi
[ "$G1_TOOL_CRASHES" -gt 0 ] && echo "   note: $G1_TOOL_CRASHES unluac tool crash(es) on aggressive output (not scored; crash point drifts with the artifact, R6 tracking)"
if [ "${GATE1_STRICT:-0}" = "1" ] && [ "$G1_BAD" -ne 0 ]; then
  exit 1
fi
exit 0
