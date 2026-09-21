#!/bin/sh
# Differential matrix CI runner (roadmap step 7 — see docs/roadmap-kmp-modules.md
# and docs/vm-status-report.md).
#
# Probes the machine for the real-interpreter differential environment that the
# tools/verify-*.sh pillars expect and, ONLY when it is complete, runs
# tools/verify-all.sh end to end. When pieces are missing it prints an explicit
# skip reason per piece and exits 0: the differential matrix is a
# server-provisioned pillar — a machine without the interpreters yields a
# *skipped* job, never a silent pass and never a red gate.
#
# Availability contract (exact paths the scripts use, no discovery):
#   /tmp/lua-5.3.6/src/{lua,luac}   (source build of Lua 5.3.6)
#   /tmp/lua-5.4.6/src/{lua,luac}   (source build of Lua 5.4.6)
#   /tmp/lua-5.5.1/src/{lua,luac}   (source build of Lua 5.5.1)
#   python3                         (tools/verify-cff.py, tools/audit-predicates.py)
#   java on PATH                    (the tool shim below; provided by CI)
#   /tmp/unluac.jar                 OPTIONAL: the decompiler pillar reports
#                                   without gating when it is absent.
#
# Tool resolution: the verify scripts honour OBFUSCATOR_TOOL. The application
# plugin is gone (the native CLI is the only default CLI), so this runner
# shims the historical `lua-bytecode-obfuscator` command with the JVM library
# entry (io.github.dingyi222666.luabytecode.cli.MainKt) on the root module's
# runtime classpath, exported by :exportCliRuntimeClasspath. The full flag
# surface the scripts use (--summary/--no-debug/--cff/...) lives in the JVM
# entry; verify-cff.py additionally sha256-verifies the interpreter sources.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)

missing=""
for v in 5.3.6 5.4.6 5.5.1; do
  for b in lua luac; do
    [ -x "/tmp/lua-$v/src/$b" ] || missing="$missing /tmp/lua-$v/src/$b"
  done
done
command -v python3 >/dev/null 2>&1 || missing="$missing python3"
if [ -n "$missing" ]; then
  echo "differential matrix SKIPPED — the runner lacks the real-interpreter environment:"
  for item in $missing; do echo "  missing: $item"; done
  echo "  provision: build lua 5.3.6 / 5.4.6 / 5.5.1 from source under /tmp/lua-<ver>/"
  echo "  (make linux will leave src/lua and src/luac) and install python3."
  echo "  Until then this pillar stays skipped BY DESIGN (exit 0) — it is not a"
  echo "  semantic pass; see docs/vm-status-report.md for the evidence chain."
  exit 0
fi
[ -f /tmp/unluac.jar ] || echo "note: /tmp/unluac.jar absent — the decompiler pillar will report but not gate"

# Build the tool shim: JVM CLI entry on the root module's runtime classpath.
CPFILE="$ROOT/build/cli-runtime-classpath.txt"
if [ ! -s "$CPFILE" ]; then
  echo "differential matrix: exporting the JVM CLI runtime classpath (:exportCliRuntimeClasspath)"
  ( cd "$ROOT" && sh gradlew :exportCliRuntimeClasspath --console=plain ) || exit 1
fi
if [ ! -s "$CPFILE" ]; then
  echo "differential matrix FAILED: classpath export missing at $CPFILE"
  exit 1
fi

SHIM=$(mktemp -d -t diff-tool.XXXXXX) || exit 1
trap 'rm -rf "$SHIM"' EXIT
# shellcheck disable=SC2046  # the classpath is one long single-line value
cat >"$SHIM/lua-bytecode-obfuscator" <<EOF
#!/bin/sh
exec java -cp "$(cat "$CPFILE")" io.github.dingyi222666.luabytecode.cli.MainKt "\$@"
EOF
chmod +x "$SHIM/lua-bytecode-obfuscator"
"$SHIM/lua-bytecode-obfuscator" --help >/dev/null 2>&1 || {
  echo "differential matrix FAILED: the JVM CLI tool shim does not run"
  exit 1
}

OBFUSCATOR_TOOL="$SHIM/lua-bytecode-obfuscator"
export OBFUSCATOR_TOOL
echo "differential matrix: environment complete — running tools/verify-all.sh"
echo "  tool: $OBFUSCATOR_TOOL (JVM CLI shim)"
sh "$ROOT/tools/verify-all.sh"
