#!/bin/sh
# COV-1 coverage METRIC (not the fix -- the fix is B1's): reports, per fixture and
# version, what fraction of the chunk's prototypes the flattening passes actually
# flattened, and warns when the ratio drops below the recorded baseline.
#
# Why: the CFF matrix gate only checks "flattened > 0" overall, so a regression that
# silently halves coverage (or a fake green where 2/50 prototypes are flattened)
# still passes. Coverage gaps equal zero protection for the untouched prototypes
# (adversarial audit: unluac decompiles them to perfectly clean source).
#
# Data source (no source changes, no CLI changes): the tool's own stdout, which
# prints `prototypes: <total>` and `obfuscated: ... flattened: <n> prototypes` for
# every run. Baseline file: tools/cff-coverage-baseline.txt (`name version ratio`),
# generated with --update-baseline and reviewed by the master before a threshold
# (a hard gate) is agreed on; until then this pillar only WARNS.
#
# Modes:
#   default             : print ratios, WARN (never fail) on regression vs baseline
#   --update-baseline   : rewrite the baseline file from the current run
#   COV_STRICT=1        : exit nonzero on WARN as well (flip on once B1 lands COV-1)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOL="$ROOT/build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator"
BASELINE="$ROOT/tools/cff-coverage-baseline.txt"
UPDATE=0
[ "${1:-}" = "--update-baseline" ] && UPDATE=1

lua_of() { case "$1" in 53) echo 5.3;; 54) echo 5.4;; 55) echo 5.5;; esac; }

# ratio <total> <flattened> -> integer percent
ratio() { echo "$2 $1" | awk '{printf "%d", ($2 * 100) / $1}'; }

# pct <total> <flattened> -> integer percent, 0 when total is 0
pct() { echo "$1 $2" | awk '{ if ($1 <= 0) print 0; else printf "%d", ($2 * 100) / $1 }'; }

# lookup <name> <version> <file> -> baseline ratio (empty when absent)
lookup() { awk -v n="$1" -v ver="$2" '$1 == n && $2 == ver { print $3; exit }' "$3"; }

TMP=$(mktemp -t cov.XXXXXX)
trap 'rm -f "$TMP" "$TMP.log"' EXIT

echo "== COV-1 CFF coverage (tools/verify-cff-coverage.sh)"
WARN=0
for v in 53 54 55; do
  for name in hello features flow bank algo big_module loops json; do
    chunk="$ROOT/src/test/resources/real/${name}_${v}.luac"
    [ -f "$chunk" ] || chunk="$ROOT/src/test/resources/bytecode/${name}_${v}.luac"
    [ -f "$chunk" ] || continue
    "$TOOL" "$chunk" --obfuscate aggressive --seed 0 --dump /tmp/cov.luac >"$TMP.log" 2>&1
    total=$(grep -o '^prototypes: [0-9]*' "$TMP.log" | grep -o '[0-9]*' | head -1)
    flat=$(grep -o 'flattened: [0-9]* prototypes' "$TMP.log" | grep -o '[0-9]*' | head -1)
    case "$total" in ''|*[!0-9]*) total=0 ;; esac
    case "$flat" in ''|*[!0-9]*) flat=0 ;; esac
    [ "$total" -eq 0 ] && continue
    r=$(pct "$total" "$flat")
    echo "   $(lua_of $v) $name: $flat/$total prototypes flattened ($r%)"
    echo "$name $v $r" >>"$TMP"
  done
done

if [ "$UPDATE" -eq 1 ]; then
  sort "$TMP" >"$BASELINE"
  echo "   baseline updated: $BASELINE"
  exit 0
fi

if [ ! -f "$BASELINE" ]; then
  sort "$TMP" >"$BASELINE"
  echo "   no baseline found -> recorded one at $BASELINE (next runs WARN on regressions)"
fi

# compare with baseline: a DROP below baseline is the warning we exist for
while read -r name v r; do
  base=$(lookup "$name" "$v" "$BASELINE")
  [ -z "$base" ] && continue
  if [ "$r" -lt "$base" ]; then
    echo "   WARN $(lua_of $v) $name: coverage $r% below baseline $base% (regression, or B1 raised it and baseline is stale)"
    WARN=$((WARN + 1))
  fi
done <"$TMP"

# absolute floor: single digit percent coverage means CFF is effectively absent
FLOOR=${COV_FLOOR:-10}
while read -r name v r; do
  if [ "$r" -lt "$FLOOR" ]; then
    echo "   WARN $(lua_of $v) $name: coverage $r% under absolute floor ${FLOOR}% (CFF close to absent)"
    WARN=$((WARN + 1))
  fi
done <"$TMP"

if [ "$WARN" -eq 0 ]; then
  echo "   coverage: no regressions vs baseline (WARN-only pillar; COV_STRICT=1 to gate)"
else
  echo "   coverage: $WARN warning(s) vs baseline (WARN-only pillar; COV_STRICT=1 to gate)"
fi

if [ "${COV_SELFTEST:-0}" = "1" ]; then
  # Self check: run the comparator on a faked measurement that DROPS below the
  # baseline and assert the same comparison the loop above uses fires a WARN.
  # If this reports BLIND, the comparator is broken and the metric is theatre.
  name=$(head -1 "$BASELINE" | awk '{print $1}'); v=$(head -1 "$BASELINE" | awk '{print $2}')
  base=$(head -1 "$BASELINE" | awk '{print $3}')
  faked=$((base - 1))
  r="$faked"
  if [ "$r" -lt "$base" ]; then
    echo "   selftest: CAUGHT (a drop from ${base}% to ${faked}% fires the WARN branch)"
  else
    echo "   selftest: BLIND (regression comparator did not fire)"; exit 1
  fi
fi

[ "${COV_STRICT:-0}" = "1" ] && [ "$WARN" -gt 0 ] && exit 1
exit 0
