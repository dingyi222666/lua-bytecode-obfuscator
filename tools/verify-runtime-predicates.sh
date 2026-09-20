#!/bin/sh
# Runtime-parameter contract checks replacing the removed host/environment seed fixtures.
# Fail on missing native tools instead of silently skipping native differential tests.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
for version in 5.3.6 5.4.6 5.5.1; do
  for binary in lua luac; do
    if [ ! -x "/tmp/lua-$version/src/$binary" ]; then
      echo "runtime predicates: FAIL (missing /tmp/lua-$version/src/$binary)" >&2
      exit 2
    fi
  done
done
cd "$ROOT"
./gradlew test --console=plain \
  --tests '*LuaControlFlowNoiseTest.runtime predicates no longer observe host libraries or fall back to constants' \
  --tests '*LuaPredicateFamiliesNumericTest' \
  --tests '*LuaCffSemanticsTest.control state does not observe or modify the environment'
echo "runtime predicates: PASS"
