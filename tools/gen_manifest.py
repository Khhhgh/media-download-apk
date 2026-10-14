#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Regenerates downapp/manifest.json from the files on disk.

Run this after EVERY change inside downapp/ (after bumping downapp/version.txt):

    python3 tools/gen_manifest.py

The manifest's "version" is read from version.txt, so the two can never disagree again
(a v5 manifest next to a v6 version.txt is what silently blocked every OTA update before).
version.txt itself stays listed for backward compatibility with already-installed app builds.
"""
import hashlib
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "downapp")
ROOT = os.path.abspath(ROOT)


def main() -> int:
    with open(os.path.join(ROOT, "version.txt"), encoding="utf-8") as fh:
        version = int(fh.read().strip().splitlines()[0])

    files = {}
    for base, dirs, names in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d != "__pycache__")
        for name in sorted(names):
            if name == "manifest.json" or name.endswith(".pyc"):
                continue
            path = os.path.join(base, name)
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            with open(path, "rb") as fh:
                data = fh.read()
            files[rel] = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}

    manifest = {"version": version, "files": dict(sorted(files.items()))}
    with open(os.path.join(ROOT, "manifest.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print("manifest.json -> version %d, %d files" % (version, len(files)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
