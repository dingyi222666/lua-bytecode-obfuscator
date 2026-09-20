#!/bin/sh
# P4 verification pillar: the VM layer (`--vm-out`) must stay semantically exact.
#
# For every fixture x version it emits a VM module and runs it on the *matching real
# interpreter*, comparing with the native chunk (normalised the same way the other pillars do).
#   * `refused` (exit 3) is a legitimate outcome: the translator refuses anything outside its
#     subset instead of emitting a wrong program.
#   * `failed` is never acceptable: a differing output, a runtime error, a missing module even
#     though the tool reported success, or any exit status that is neither 0 nor 3 (a crash was
#     previously counted as `refused` -- adversarial audit M6).
#   * the identical count must not drop below MIN_IDENTICAL, so a coverage regression fails too.
#
# The last block compiles shapes that *caught* a real wrong-output bug (numeric `for` printing
# `0 1 2 3` for `1 2 3`), so the gate covers them without a committed fixture.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOL="$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator"
MIN_IDENTICAL=${VM_MIN_IDENTICAL:-31}

TMPLOG=$(mktemp -t vm-log.XXXXXX)
TMPOUT=$(mktemp -t vm-out.XXXXXX)
norm() { sed -E 's/[^ ,]+\.lua:[0-9]+: //g'; }

identical=0; refused=0; failed=0

# one case: $1 version tag, $2 lua binary, $3 luac binary, $4 chunk, $5 source
check() {
  cv=$1; cL=$2; cLUAC=$3; cchunk=$4; csrc=$5; cname=$6
  module=$(mktemp -t vm-verify.XXXXXX).lua; rm -f "$module"
  "$TOOL" "$cchunk" --seed 0 --vm-out "$module" >"$TMPLOG" 2>&1
  rc=$?
  if [ $rc -eq 3 ]; then refused=$((refused + 1)); return; fi
  if [ $rc -ne 0 ] || [ ! -f "$module" ]; then
    failed=$((failed + 1)); echo "FAIL $cv $cname (tool rc=$rc, module=$([ -f "$module" ] && echo yes || echo no))"
    sed -n '1,3p' "$TMPLOG"
    return
  fi
  $cL "$module" >"$TMPOUT" 2>&1 &
  pid=$!
  ( ( sleep 20; kill -9 $pid 2>/dev/null ) >/dev/null 2>&1 & )
  wait $pid 2>/dev/null; run=$?
  if [ $run -ne 0 ]; then failed=$((failed + 1)); echo "FAIL $cv $cname (run rc=$run)"; head -3 "$TMPOUT"; return; fi
  want=$($cL "$csrc" 2>&1 | norm); have=$(norm < "$TMPOUT")
  if [ "$want" = "$have" ]; then identical=$((identical + 1)); else
    failed=$((failed + 1)); echo "FAIL $cv $cname (output differs)"; echo "   want: $(echo "$want" | head -3 | tr '\n' ' ')"; echo "   have: $(echo "$have" | head -3 | tr '\n' ' ')"
  fi
}

for v in 53 54 55; do
  case "$v" in
    53) L=/tmp/lua-5.3.6/src/lua; LUAC=/tmp/lua-5.3.6/src/luac ;;
    54) L=/tmp/lua-5.4.6/src/lua; LUAC=/tmp/lua-5.4.6/src/luac ;;
    55) L=/tmp/lua-5.5.1/src/lua; LUAC=/tmp/lua-5.5.1/src/luac ;;
  esac
  [ -x "$L" ] || { echo "SKIP 5.$v (no interpreter)"; continue; }
  for name in c01_numericfor c03_while c07_break c10_ifchain c05_generic_ipairs c14_numericfor_print hello features flow bank algo big_module; do
    chunk="$ROOT/src/test/resources/real/ctor/${name}_${v}.luac"
    [ -f "$chunk" ] || chunk="$ROOT/src/test/resources/bytecode/${name}_${v}.luac"
    [ -f "$chunk" ] || chunk="$ROOT/src/test/resources/real/${name}_${v}.luac"
    src=""
    for dir in real/ctor bytecode real lua; do
      candidate="$ROOT/src/test/resources/$dir/${name}.lua"
      if [ -z "$src" ] && [ -f "$candidate" ]; then src="$candidate"; fi
    done
    if [ -z "$src" ] || [ ! -f "$chunk" ]; then refused=$((refused + 1)); continue; fi
    check "5.$v" "$L" "$LUAC" "$chunk" "$src" "$name"
  done

  # self compiled differential shapes (adversarial audit C1):
  #   every iteration printed  -> an extra or missing iteration is visible
  #   negative step            -> FORLOOP must be sign aware
  #   float step               -> the shipped interpreter must use real Lua numbers
  #   bare register compare    -> VM-CMP-1: 5.4/5.5 moved the LT/LE/EQ operands to A/B
  #                               (polarity in k); wrong operand order silently inverted the
  #                               whole loop (native `5 5` vs VM `0 10`)
  #   bit ops                  -> VM-BIT-1: the translator emits BAND/BOR/BXOR/SHL/SHR words
  #                               but the generated interpreter had no handlers -- the module
  #                               shipped and then crashed (or a tree skeleton silently ran a
  #                               wrong leaf)
  if [ -x "$LUAC" ]; then
    for shape in "for i=1,3 do print(i) end|printfor" \
                 "for i=10,1,-3 do print(i) end|negstep" \
                 "for i=1,2,0.5 do print(i) end|floatstep" \
                 "for i=1,0 do print(i) end print('after')|zerotrip" \
                 "local i,j=0,10 while i<j do i=i+1 j=j-1 end print(i,j)|regcmp" \
                 "local a,b=6,3 print(a|b, a&b, a~b, a<<1, a>>1)|bitops"; do
      body=${shape%|*}; label=${shape#*|}
      tmp=$(mktemp -t vm-shape.XXXXXX).lua
      printf '%s\n' "$body" > "$tmp"
      "$LUAC" -o "$tmp.luac" "$tmp" 2>/dev/null
      check "5.$v" "$L" "$LUAC" "$tmp.luac" "$tmp" "$label"
    done
  fi
done

echo "VM matrix: $identical identical / $refused refused / $failed failed (minimum $MIN_IDENTICAL identical)"
if [ "$failed" -ne 0 ]; then echo "VM matrix FAILED ($failed wrong results)"; exit 1; fi
if [ "$identical" -lt "$MIN_IDENTICAL" ]; then echo "VM matrix FAILED (coverage dropped below $MIN_IDENTICAL)"; exit 1; fi
exit 0
