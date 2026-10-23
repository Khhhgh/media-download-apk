#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
On-device dynamic engine runner (Chaquopy entry point).

This module is intentionally thin: it prepares sys.path so that the active
(on-disk, OTA-updatable) engine tree takes priority over the bundled copy, then
dispatches extraction to downapp/runner.py which performs platform routing.
"""

import importlib
import json
import os
import sys
from typing import Any, Dict


def _purge_engine_modules(active_dir: str = "") -> None:
    """Drop cached engine modules so an OTA-updated tree is always re-read from disk.

    ``runner``/``common`` are imported normally (and therefore cached in ``sys.modules``), while the
    per-platform engines are executed through ``importlib`` (registered as ``"<Platform>.engine"``).
    Besides those well-known names, any module whose file lives under the active engine directory
    is dropped too, so nothing from a previous OTA version can survive in ``sys.modules``.
    """
    for name in ("runner", "common"):
        sys.modules.pop(name, None)
    root = os.path.abspath(active_dir) + os.sep if active_dir else ""
    for name, module in list(sys.modules.items()):
        if name.endswith(".engine"):
            sys.modules.pop(name, None)
            continue
        if root:
            file = getattr(module, "__file__", None)
            if file and os.path.abspath(file).startswith(root):
                sys.modules.pop(name, None)


def _prepare_paths(active_dir: str, site_packages_dir: str) -> None:
    """Make the OTA engines sys.path[0] and the dynamic site-packages sys.path[1].

    Existing entries are removed first and re-inserted, so the priority is correct even when the
    paths were already present at some other position (``if path not in sys.path`` would keep a
    stale position and let an older/bundled copy win).
    """
    _purge_engine_modules(active_dir)
    if site_packages_dir and os.path.isdir(site_packages_dir):
        while site_packages_dir in sys.path:
            sys.path.remove(site_packages_dir)
    if active_dir and os.path.isdir(active_dir):
        while active_dir in sys.path:
            sys.path.remove(active_dir)
        sys.path.insert(0, active_dir)
    if site_packages_dir and os.path.isdir(site_packages_dir):
        sys.path.insert(1 if active_dir and os.path.isdir(active_dir) else 0, site_packages_dir)
    # Files were (re)written on disk after Python started: make the import system re-scan them.
    importlib.invalidate_caches()


def _format_error(exc: BaseException) -> str:
    """The exception's own text (with its type, so empty messages like KeyError stay readable)."""
    text = str(exc).strip()
    name = type(exc).__name__
    return "%s: %s" % (name, text) if text else name


def execute(active_dir: str, site_packages_dir: str, platform: str, url: str) -> str:
    """Runs extraction for a single URL and returns a JSON string.

    Never raises: failures are reported as {"success": false, "error": "..."}.
    """
    try:
        _prepare_paths(active_dir, site_packages_dir)

        import runner  # noqa: E402  (downapp/runner.py, resolved from active_dir)

        result: Dict[str, Any] = runner.run(url)
        if not isinstance(result, dict):
            raise ValueError("Engine returned a non-dict result")
        result.setdefault("success", True)
        result.setdefault("platform", platform)
        return json.dumps(result, ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001 - must never crash the app
        return json.dumps(
            {"success": False, "error": _format_error(exc), "platform": platform},
            ensure_ascii=False,
        )


def missing_modules(names_json: str) -> str:
    """Returns a JSON array of import names from names_json that cannot be imported."""
    import importlib

    try:
        names = json.loads(names_json)
    except Exception:
        return json.dumps([])
    missing = []
    for name in names:
        try:
            importlib.import_module(name)
        except Exception:
            missing.append(name)
    return json.dumps(missing, ensure_ascii=False)


def verify(active_dir: str, site_packages_dir: str) -> str:
    """Imports the runner and every bundled platform engine to validate the tree.

    Returns a JSON string {"success": bool, "imported": [...], "error": "..."}.
    """
    imported = []
    try:
        _prepare_paths(active_dir, site_packages_dir)
        import importlib.util

        import runner  # noqa: F401,E402

        engine_dirs = [
            "Snapchat", "Instagram", "Tiktok", "Threads",
            "Facebook", "Pinterest", "Reddit", "Twitter",
        ]
        for name in engine_dirs:
            path = os.path.join(active_dir, name, "engine.py")
            if not os.path.isfile(path):
                continue
            spec = importlib.util.spec_from_file_location("verify_%s" % name, path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            imported.append(name)
        return json.dumps({"success": True, "imported": imported})
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"success": False, "imported": imported, "error": _format_error(exc)})
