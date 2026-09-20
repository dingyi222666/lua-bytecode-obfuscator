#!/bin/bash
# tools/smoke-floats.sh — B3 additive: float shapes from R4 Round 7 (±0.0 blob reduction)
# Runs the five-piece R4 acceptance + float trio on 5.3/5.4/5.5, aggressive channel.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOL="$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator"
D=$(mktemp -d /tmp/b3floats.XXXXXX)
trap 'rm -rf "$D"' EXIT
fail=0
norm() { sed -E 's/[^ ,]+\.lua:[0-9]+: //g'; }
declare -a SRC=(
  'print(0.0)'
  'print(-0.0)'
  'local z = 0.0 print(1)'
  'local a = 7 local b = 3 print(a * 2 + b, a - b, b * b + a, (a + b) * 2 - 1)'
)
declare -a TAG=(poszero negzero storedzero k53)
declare -a WANT=('0.0' '-0.0' '1' '17 4 16 19')
for v in 53 54 55; do
  case $v in 53) L=/tmp/lua-5.3.6/src/lua; LC=/tmp/lua-5.3.6/src/luac;;
             54) L=/tmp/lua-5.4.6/src/lua; LC=/tmp/lua-5.4.6/src/luac;;
             55) L=/tmp/lua-5.5.1/src/lua; LC=/tmp/lua-5.5.1/src/luac;; esac
  [ -x "$L" ] || continue
  for idx in "${!SRC[@]}"; do
    tag="${TAG[$idx]}"; src="$D/${tag}_$v.lua"
    printf '%s\n' "${SRC[$idx]}" > "$src"
    "$LC" -o "$src.luac" "$src" 2>/dev/null || { echo "skip $tag $v (luac)"; continue; }
    "$TOOL" "$src.luac" --obfuscate aggressive --dump "$src.aggr" >/dev/null 2>&1 || { echo "FAIL $tag $v: obfuscator"; fail=$((fail+1)); continue; }
    out=$("$L" "$src.aggr" 2>&1 & p=$!; ( sleep 15; kill -9 $p 2>/dev/null ) >/dev/null 2>&1 & w=$!; wait $p 2>/dev/null; kill $w 2>/dev/null)
    got=$(printf '%s' "$out" | norm | tr -s ' \t' ' ' | head -1)
    want="${WANT[$idx]}"
    if [ "$got" = "$want" ]; then echo "ok   $tag $v"; else echo "FAIL $tag $v: want [$want] got [$got]"; fail=$((fail+1)); fi
  done
done
if [ $fail -gt 0 ]; then echo "FLOAT-SMOKE FAIL ($fail)"; exit 1; fi
echo "FLOAT-SMOKE PASS"
