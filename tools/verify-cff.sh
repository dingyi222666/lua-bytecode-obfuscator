#!/bin/sh
# Bounded native differential matrix with complete output and feature reports.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$ROOT/tools/verify-cff.py" "$@"
