#!/bin/sh
# verify-empty-output.sh — REG-53-class "silent empty output" detector (B6/Round4).
#
# Detects the failure family R4 characterised in REG-53-AGGR: the obfuscated
# chunk exits 0 but produces NO output while the original produces some —
# the worst failure mode because it looks like a pass in rc-based gates.
#
# Probe design notes (do not weaken):
#   * ONLY the pure preset channel (`--obfuscate aggressive`) is probed.
#     CLI-MERGE-1 taught that any granular flag silently drops passes from
#     the preset path, so flag-augmented runs are false-negative probes.
#   * The minimal REG-53 shape (`local a=7 print(a)` — top-level local into
#     print) is a built-in shape; it is the known smallest trigger.
#   * A shape FAILs as EMPTY_OUTPUT when: original output is non-empty AND
#     obfuscated rc = 0 AND obfuscated stdout is empty. RUN_FAIL and DIFF
#     are reported separately so the failure family stays diagnosable.
#
# Usage:
#   sh tools/verify-empty-output.sh              # probe, exit 0 = clean
#   SHAPES="minimal gcd" sh tools/verify-empty-output.sh
#   BIN=/path/to/lua-bytecode-obfuscator sh tools/verify-empty-output.sh
#
# Exit codes: 0 all shapes green, 1 at least one failure.

set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BIN=${BIN:-$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator}
LUA53=${LUA53:-/tmp/lua-5.3.6/src/lua}
LUA54=${LUA54:-/tmp/lua-5.4.6/src/lua}
LUA55=${LUA55:-/tmp/lua-5.5.1/src/lua}
LUAC53=${LUAC53:-/tmp/lua-5.3.6/src/luac}
LUAC54=${LUAC54:-/tmp/lua-5.4.6/src/luac}
LUAC55=${LUAC55:-/tmp/lua-5.5.1/src/luac}
WATCHDOG=${WATCHDOG:-60}

SHAPES=${SHAPES:-"minimal gcd whilecmp closure"}

# shape: name|lua source (one line, \n encoded as ;)|expected output (first line)
shape_source() {
    case "$1" in
        minimal)
            # REG-53-AGGR minimal trigger: top-level local flowing into print
            printf 'local a = 7\nprint(a)\n'
            ;;
        gcd)
            printf 'local function gcd(a, b) while b ~= 0 do a, b = b, a %% b end return a end print(gcd(48, 18))\n'
            ;;
        whilecmp)
            printf 'local i, j = 0, 5\nwhile i < j do i = i + 1 end print(i, j)\n'
            ;;
        closure)
            printf 'local function mk() local n = 0 return function() n = n + 1 return n end end local c = mk() c() c() print(c())\n'
            ;;
        *)
            return 1
            ;;
    esac
}

expected_of() {
    case "$1" in
        minimal) echo "7" ;;
        gcd) echo "6" ;;
        whilecmp) echo "5\t5" ;;
        closure) echo "3" ;;
    esac
}

lua_for() {
    case "$1" in
        53) echo "$LUA53" ;;
        54) echo "$LUA54" ;;
        55) echo "$LUA55" ;;
    esac
}

luac_for() {
    case "$1" in
        53) echo "$LUAC53" ;;
        54) echo "$LUAC54" ;;
        55) echo "$LUAC55" ;;
    esac
}

# run_with_watchdog <seconds> <cmd...>; prints output, sets RUN_RC global
RUN_RC=0
run_with_watchdog() {
    local limit="$1"; shift
    "$@" > "$TMPDIR_B6/out.txt" 2>&1 &
    local pid=$!
    ( sleep "$limit"; kill -9 "$pid" 2>/dev/null ) &
    local killer=$!
    wait "$pid" 2>/dev/null
    RUN_RC=$?
    kill "$killer" 2>/dev/null
    wait "$killer" 2>/dev/null
    cat "$TMPDIR_B6/out.txt"
}

pass=0
fail=0
TMPDIR_B6=$(mktemp -d /tmp/b6empty.XXXXXX)
trap 'rm -rf "$TMPDIR_B6"' EXIT

[ -x "$BIN" ] || { echo "BIN not executable: $BIN"; exit 1; }

for version in 53 54 55; do
    LUA=$(lua_for "$version")
    LUAC=$(luac_for "$version")
    [ -x "$LUA" ] && [ -x "$LUAC" ] || { echo "skip $version (interpreter missing)"; continue; }
    for shape in $SHAPES; do
        src=$(shape_source "$shape") || continue
        printf '%s' "$src" > "$TMPDIR_B6/s.lua"
        "$LUAC" -s -o "$TMPDIR_B6/s.luac" "$TMPDIR_B6/s.lua" 2>/dev/null || {
            echo "SMOKE skip $shape $version (luac failed)"; continue; }

        original=$(run_with_watchdog "$WATCHDOG" "$LUA" "$TMPDIR_B6/s.luac")
        if [ "$RUN_RC" -ne 0 ]; then
            echo "SMOKE skip $shape $version (original rc=$RUN_RC, fixture problem)"; continue
        fi
        if [ -z "$original" ]; then
            echo "SMOKE skip $shape $version (original output empty — shape invalid)"; continue
        fi

        run_with_watchdog "$WATCHDOG" "$BIN" "$TMPDIR_B6/s.luac" --obfuscate aggressive --dump "$TMPDIR_B6/o.luac" > /dev/null 2>&1
        if [ "$RUN_RC" -ne 0 ]; then
            echo "SMOKE FAIL $shape $version aggressive: obfuscator rc=$RUN_RC"
            fail=$((fail + 1)); continue
        fi

        got=$(run_with_watchdog "$WATCHDOG" "$LUA" "$TMPDIR_B6/o.luac")
        rc=$RUN_RC
        if [ "$rc" -ne 0 ]; then
            echo "SMOKE FAIL $shape $version aggressive: RUN_FAIL rc=$rc ($(echo "$got" | head -1))"
            fail=$((fail + 1)); continue
        fi
        if [ -z "$got" ] && [ -n "$original" ]; then
            # THE REG-53 DETECTOR: silent empty output with rc=0
            echo "SMOKE FAIL $shape $version aggressive: EMPTY_OUTPUT (expected [$original], rc=0, stdout empty)"
            fail=$((fail + 1)); continue
        fi
        if [ "$got" != "$original" ]; then
            echo "SMOKE FAIL $shape $version aggressive: DIFF want [$original] got [$got]"
            fail=$((fail + 1)); continue
        fi
        echo "SMOKE ok   $shape $version aggressive -> [$got]"
        pass=$((pass + 1))
    done
done

echo "empty-output probe: $pass ok / $fail fail"
[ "$fail" -eq 0 ]
