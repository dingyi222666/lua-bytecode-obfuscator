#!/usr/bin/env bash
#
# KMP purity gate (roadmap step 2 — see docs/kmp-java-dependency-map.md and
# docs/roadmap-kmp-progress.md).
#
# Fails when JVM-only references leak into the packages that must move into
# commonMain during the module split. Detected patterns (after comment
# stripping — the code deliberately documents "what replaced what" in KDoc):
#
#   * java.* / javax.* qualified names, including imports
#   * Kotlin expressions bound to JVM runtime: ::class.java, System.getenv /
#     System.err / System.out / System.getProperty, java.security /
#     java.nio / java.io usage of any spelling
#   * JVM-only annotations: @JvmStatic / @JvmOverloads / @JvmField /
#     kotlin.jvm.* imports
#
# Whitelisted (JVM by design, they are adapters and entrypoints):
#   cli/, legacy/, decompile/, B4Diag.kt, B4EffectRunner.kt and
#   platform/jvm/ (the JVM implementations of the platform seams).
#
set -euo pipefail
cd "$(dirname "$0")/.."

SCOPE=(
  src/main/kotlin/io/github/dingyi222666/luabytecode/bytecode
  src/main/kotlin/io/github/dingyi222666/luabytecode/obfuscate
  src/main/kotlin/io/github/dingyi222666/luabytecode/vm
  src/main/kotlin/io/github/dingyi222666/luabytecode/platform
)

strip_comments() {
  # Strips line comments, block comments (nested, like Kotlin), and keeps
  # line numbers. String literals are NOT stripped: our code has no java.*
  # inside them, and keeping them keeps the gate conservative.
  awk '
    BEGIN { depth = 0 }
    {
      line = $0
      out = ""
      i = 1
      n = length(line)
      while (i <= n) {
        two = substr(line, i, 2)
        three = substr(line, i, 3)
        if (depth == 0 && two == "/*") { depth = 1; i += 2; continue }
        if (depth > 0 && substr(line, i, 2) == "*/") { depth = (depth > 1 ? depth - 1 : 0); i += 2; continue }
        if (depth == 0 && two == "//") break
        if (depth == 0) out = out substr(line, i, 1)
        i += 1
      }
      print out
      if (depth > 0) print ""
    }
  ' "$1"
}

violations=0
for dir in "${SCOPE[@]}"; do
  [ -d "$dir" ] || continue
  while IFS= read -r -d '' file; do
    # platform/jvm is the whitelist: it is the JVM adapter, not common code.
    case "$file" in
      */platform/jvm/*) continue ;;
    esac
    hits=$(strip_comments "$file" | grep -nE \
      '(^|[^A-Za-z0-9_."])(java|javax)\.[a-zA-Z]|::class\.java|kotlin\.jvm\.|@JvmStatic|@JvmOverloads|@JvmField|System\.(getenv|err|out|getProperty)' \
      || true)
    if [ -n "$hits" ]; then
      if [ "$violations" -eq 0 ]; then
        echo "KMP purity gate FAILED — JVM-only references inside common-bound code:"
        echo "  scope: bytecode/ obfuscate/ vm/ platform (platform/jvm whitelisted)"
      fi
      while IFS= read -r hit; do
        echo "  $file:$hit"
        violations=$((violations + 1))
      done <<< "$hits"
    fi
  done < <(find "$dir" -type f -name '*.kt' -print0)
done

if [ "$violations" -gt 0 ]; then
  echo ""
  echo "$violations violation(s). Move the JVM dependency behind a platform/"
  echo "interface (see docs/roadmap-kmp-modules.md step 2) or move the file to"
  echo "platform/jvm, cli, legacy or decompile."
  exit 1
fi

echo "KMP purity gate OK: no JVM-only references in common-bound packages."
