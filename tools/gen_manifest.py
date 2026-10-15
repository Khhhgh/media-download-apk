#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Regenerates downapp/manifest.json from the files on disk.

Run this after EVERY change inside downapp/ (after bumping downapp/version.txt):

    python3 tools/gen_manifest.py

The manifest's "version" is read from version.txt, so the two can never disagree again
(a v5 manifest next to a v6 version.txt is what silently blocked every OTA update before).
version.txt itself stays listed for backward compatibility with already-installed app builds.

app_version.json (the APK update announcement read by AppUpdateManager) is NOT an engine file:
it is excluded from the manifest so releasing a new APK never changes engine hashes. This script
never rewrites it (announcing a version before its APK exists would send users to a 404); it only
validates it, and creates a first copy from app/build.gradle.kts if the file is missing.
"""
import hashlib
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "downapp")
ROOT = os.path.abspath(ROOT)
GRADLE = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "build.gradle.kts"))
APP_VERSION_FILE = "app_version.json"
EXCLUDED_FROM_MANIFEST = {"manifest.json", APP_VERSION_FILE}
REQUIRED_APP_KEYS = {"versionCode": int, "versionName": str, "apkUrl": str, "forceUpdate": bool}


def ensure_app_version() -> None:
    path = os.path.join(ROOT, APP_VERSION_FILE)
    if not os.path.exists(path):
        with open(GRADLE, encoding="utf-8") as fh:
            gradle = fh.read()
        code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
        name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
        data = {
            "versionCode": code,
            "versionName": name,
            "apkUrl": "https://github.com/OWNER/REPO/releases/download/v%s/media-download-update.apk" % name,
            "releaseNotes": "",
            "forceUpdate": False,
        }
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(data, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        print("created %s (edit apkUrl before pushing!)" % APP_VERSION_FILE)

    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    for key, typ in REQUIRED_APP_KEYS.items():
        if not isinstance(data.get(key), typ):
            raise SystemExit("%s: key '%s' must be of type %s" % (APP_VERSION_FILE, key, typ.__name__))
    if not data["apkUrl"].startswith("https://"):
        raise SystemExit("%s: apkUrl must be an https URL" % APP_VERSION_FILE)
    print("%s ok -> versionCode %d (%s)" % (APP_VERSION_FILE, data["versionCode"], data["versionName"]))


def main() -> int:
    ensure_app_version()
    with open(os.path.join(ROOT, "version.txt"), encoding="utf-8") as fh:
        version = int(fh.read().strip().splitlines()[0])

    files = {}
    for base, dirs, names in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d != "__pycache__")
        for name in sorted(names):
            if name in EXCLUDED_FROM_MANIFEST or name.endswith(".pyc"):
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
