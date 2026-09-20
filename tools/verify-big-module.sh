#!/bin/sh
# End to end semantics check for the big_module fixture on all three chunk
# versions, exactly as required by docs/obfuscation-algorithms.md §8 P0.1.
#
# For every version x configuration we
#   1. obfuscate + dump with the CLI,
#   2. reload the dumped chunk with the CLI itself (`--dump` already re-parses),
#   3. execute the dump with the *real* interpreter of the matching version,
# and compare its combined output with the interpreter running the original
# big_module.lua.
#
# The only tolerated difference is the `file.lua:line:` prefix that Lua prepends
# to `error()` messages: the source run reports the long absolute path (truncated
# by luaO_chunkid) while a non stripped dump embeds the short `@big_module.lua`
# name, and a `--strip-debug` dump has no name at all.
#
# Usage:
#   tools/verify-big-module.sh [lua-5.3 | lua-5.4 | lua-5.5 ...]
# Environment overrides:
#   TOOL   path to the lua-bytecode-obfuscator launcher
#          (default build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator)
#   LUA53 / LUA54 / LUA55   interpreter binaries
#   WORK   scratch directory (default: a fresh mktemp -d)
set -u
cd "$(dirname "$0")/.."

TOOL="${TOOL:-build/install/lua-bytecode-obfuscator/bin/lua-bytecode-obfuscator}"
LUA53="${LUA53:-/tmp/lua-5.3.6/src/lua}"
LUA54="${LUA54:-/tmp/lua-5.4.6/src/lua}"
LUA55="${LUA55:-/tmp/lua-5.5.1/src/lua}"
SOURCE="src/test/resources/real/big_module.lua"

if [ ! -x "$TOOL" ]; then
  echo "error: $TOOL not found; run: sh gradlew installDist" >&2
  exit 2
fi

WORK="${WORK:-$(mktemp -d)}"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/out"

# Strip only the `...lua:123: ` / `big_module.lua:123: ` prefix from error messages.
normalise() {
  sed -E 's/[^ ,]+\.lua:[0-9]+: //g' "$1"
}

pass=0
fail=0

run_version() {
  version="$1"
  lua="$2"
  case "$version" in
    lua-5.3) chunk="src/test/resources/real/big_module_53.luac" ;;
    lua-5.4) chunk="src/test/resources/real/big_module_54.luac" ;;
    lua-5.5) chunk="src/test/resources/real/big_module_55.luac" ;;
    *) echo "unknown version $version" >&2; return 1 ;;
  esac

  if [ ! -x "$lua" ]; then
    echo "SKIP $version: interpreter $lua not found"
    return 0
  fi

  "$lua" "$SOURCE" > "$WORK/ref.out" 2> "$WORK/ref.err"
  ref_status=$?
  if [ "$ref_status" -ne 0 ]; then
    echo "FAIL $version: the reference run failed ($ref_status)" >&2
    fail=$((fail + 1))
    return 0
  fi

  for config in \
    "jmp|--jmp 3 --seed 7" \
    "super-seed7-stripped|--strings 4 --jmp 3 --junk 4 --rings 4:3 --seed 7 --predicate MIXED --strip-debug" \
    "super-seed29-stripped|--strings 4 --jmp 3 --junk 4 --rings 4:3 --seed 29 --predicate MIXED --strip-debug" \
    "super-seed29|--strings 4 --jmp 3 --junk 4 --rings 4:3 --seed 29 --predicate MIXED"
  do
    name="${config%%|*}"
    flags="${config#*|}"
    out="$WORK/out/${version}_${name}.luac"

    # shellcheck disable=SC2086
    # Pin distinct seeds so both randomized layouts remain reproducible.
    if ! "$TOOL" "$chunk" $flags --dump "$out" > "$WORK/out/${version}_${name}.log" 2>&1; then
      echo "FAIL $version $name: CLI failed: $(tail -n 1 "$WORK/out/${version}_${name}.log")"
      fail=$((fail + 1))
      continue
    fi
    if ! grep -q "re-parsed the dumped chunk" "$WORK/out/${version}_${name}.log"; then
      echo "FAIL $version $name: the dumped chunk did not reload"
      fail=$((fail + 1))
      continue
    fi

    "$lua" "$out" > "$WORK/out/${version}_${name}.out" 2> "$WORK/out/${version}_${name}.err"
    status=$?
    if [ "$status" -ne 0 ]; then
      echo "FAIL $version $name: interpreter exited $status: $(head -n 1 "$WORK/out/${version}_${name}.err")"
      fail=$((fail + 1))
      continue
    fi

    normalise "$WORK/ref.out" > "$WORK/ref.norm"
    normalise "$WORK/out/${version}_${name}.out" > "$WORK/out.norm"
    normalise "$WORK/out/${version}_${name}.err" > "$WORK/err.norm"
    if ! diff -u "$WORK/ref.norm" "$WORK/out.norm" > "$WORK/diff.txt"; then
      echo "FAIL $version $name: stdout differs"
      sed -n '1,20p' "$WORK/diff.txt"
      fail=$((fail + 1))
      continue
    fi
    if [ -s "$WORK/err.norm" ]; then
      echo "FAIL $version $name: unexpected stderr: $(head -n 1 "$WORK/err.norm")"
      fail=$((fail + 1))
      continue
    fi
    echo "OK   $version $name ($(wc -c < "$out" | tr -d ' ') bytes)"
    pass=$((pass + 1))
  done
}

if [ "$#" -gt 0 ]; then
  for requested in "$@"; do
    case "$requested" in
      lua-5.3) run_version "$requested" "$LUA53" ;;
      lua-5.4) run_version "$requested" "$LUA54" ;;
      lua-5.5) run_version "$requested" "$LUA55" ;;
      *) echo "unknown version $requested" >&2; exit 2 ;;
    esac
  done
else
  run_version lua-5.3 "$LUA53"
  run_version lua-5.4 "$LUA54"
  run_version lua-5.5 "$LUA55"
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]