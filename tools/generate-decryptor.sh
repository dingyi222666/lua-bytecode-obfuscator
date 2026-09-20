#!/bin/sh
# Regenerates the bundled decoder prototypes used by LuaStringEncryptor.
# Requires luac for each supported version in PATH (luac53/luac54/luac55 or luac).
set -e
cd "$(dirname "$0")/.."
for spec in "53:luac53" "54:luac54" "55:luac55"; do
  version="${spec%%:*}"; luac="${spec##*:}"
  command -v "$luac" >/dev/null 2>&1 || { echo "skip 5.$version: $luac not found"; continue; }
  "$luac" -s -o "src/main/resources/obfuscator/decode_${version}.luac" tools/decode.lua
  echo "wrote src/main/resources/obfuscator/decode_${version}.luac"
done
