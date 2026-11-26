#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
hermesc=${HERMESC:-hermesc}
out="$root/app/build/generated/settingsAssets"
mkdir -p "$out"
"$hermesc" -O -emit-binary -out "$out/discordru-settings.hbc" "$root/app/src/main/js/proxy-settings.js"
python3 - "$out/discordru-settings.hbc" <<'PY'
from pathlib import Path
import struct
import sys

with Path(sys.argv[1]).open("rb") as artifact:
    magic, version = struct.unpack("<QI", artifact.read(12))
    if (magic, version) != (0x1F1903C103BC1FC6, 98):
        raise SystemExit("Settings requires Hermes bytecode version 98")
PY
