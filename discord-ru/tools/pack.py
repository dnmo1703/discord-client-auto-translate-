#!/usr/bin/env python3
import re
import shutil
import sys
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

host_path, payload_path, patch_dir, output_path = map(Path, sys.argv[1:])
dex = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex$")
signature = re.compile(r"META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|MANIFEST\.MF)$", re.I)


def copy(source, info, output, name=None):
    info_copy = type(info)(name or info.filename, info.date_time)
    info_copy.compress_type = info.compress_type
    info_copy.external_attr = info.external_attr
    with source.open(info) as original, output.open(info_copy, "w") as dest:
        shutil.copyfileobj(original, dest)


with ZipFile(host_path) as host, ZipFile(payload_path) as payload, ZipFile(output_path, "w") as output:
    host_entries = set(host.namelist())
    for info in host.infolist():
        if info.filename in ("AndroidManifest.xml", "resources.arsc") or signature.fullmatch(info.filename):
            continue
        if info.filename == "classes.dex":
            output.write(patch_dir / info.filename, info.filename, ZIP_STORED)
        elif not info.filename.startswith("assets/dexopt/"):
            copy(host, info, output)
    for name in ("AndroidManifest.xml", "resources.arsc"):
        output.write(patch_dir / name, name, ZIP_STORED)
    next_dex = len([name for name in host_entries if dex.fullmatch(name)]) + 1
    output.write(patch_dir / "payload.dex", f"classes{next_dex}.dex", ZIP_STORED)
    for info in payload.infolist():
        name = info.filename
        if name.startswith("assets/dexopt/"):
            continue
        if name.startswith("res/") and not info.is_dir():
            copy(payload, info, output, "res/discordru/" + name[4:])
            continue
        if not info.is_dir() and (
            name.startswith(("assets/", "lib/arm64-v8a/"))
            or ("/" not in name and name.endswith(".properties"))
        ):
            if name in host_entries:
                if not name.endswith(".properties") and host.read(name) != payload.read(name):
                    raise ValueError(f"Host and payload have different content at {name}")
                continue
            copy(payload, info, output)
