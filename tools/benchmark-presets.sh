#!/bin/sh
# Speed / strength balance of the presets (docs §8 P3 review).
# Measures wall clock runtime overhead and output size for each tier on the
# compute heavy big_module fixture, executed by the real interpreter.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOL="$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator"
LUA=/tmp/lua-5.4.6/src/lua
FIXTURE="$ROOT/src/test/resources/real/big_module.lua"
CHUNK="$ROOT/src/test/resources/real/big_module_54.luac"
best() { # best of 5 wall clock milliseconds
  local f="$1" out=999999
  for _ in 1 2 3 4 5; do
    local s=$(date +%s%N); "$LUA" "$f" >/dev/null 2>&1; local e=$(date +%s%N)
    local ms=$(( (e - s) / 1000000 ))
    [ "$ms" -lt "$out" ] && out=$ms
  done
  echo "$out"
}
base=$(best "$FIXTURE"); base_size=$(wc -c < "$CHUNK")
echo "preset      size(bytes)  x-size   runtime(ms)  x-time"
echo "original    $base_size         1.00     $base          1.00"
for preset in light balanced aggressive; do
  out=/tmp/bench-$preset.luac; rm -f "$out"
  s=$(date +%s%N); "$TOOL" "$CHUNK" --obfuscate "$preset" --seed 0 --dump "$out" >/dev/null 2>&1; rc=$?; e=$(date +%s%N)
  obf_ms=$(( (e - s) / 1000000 ))
  # GATE-4 (d): a swallowed tool crash used to print `size=0` and exit 0 "success".
  # The dump must exist and be non-empty, otherwise the row is invalid and the
  # script fails loudly instead of benchmarking nothing.
  if [ "$rc" -ne 0 ] || [ ! -s "$out" ]; then
    echo "ERROR: tool rc=$rc, no/empty dump for preset $preset ($out) -- benchmark row invalid" >&2
    exit 1
  fi
  size=$(wc -c < "$out")
  ms=$(best "$out")
  xsize=$(awk -v a="$size" -v b="$base_size" 'BEGIN{printf "%.2f", a/b}')
  xtime=$(awk -v a="$ms" -v b="$base" 'BEGIN{printf "%.2f", a/b}')
  printf "%-11s %-12s %-8s %-12s %-6s (obfuscate %sms)\n" "$preset" "$size" "$xsize" "$ms" "$xtime" "$obf_ms"
done
