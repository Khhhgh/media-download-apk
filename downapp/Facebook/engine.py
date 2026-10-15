
#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Facebook Dynamic Media Extraction Engine
Path: downapp/Facebook/engine.py
"""

import html
import json
import os
import re
import shutil
import subprocess
import sys
from typing import Any, Dict, Optional
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get, UNIVERSAL_USER_AGENT


TIMEOUT = 25

HEADERS = {
    "User-Agent": UNIVERSAL_USER_AGENT,
    "Accept": (
        "text/html,application/xhtml+xml,application/xml;q=0.9,"
        "image/avif,image/webp,*/*;q=0.8"
    ),
    "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
    "Referer": "https://www.facebook.com/",
}


def _clean_url(value: Optional[str]) -> Optional[str]:
    if not value:
        return None

    value = html.unescape(value.strip())
    value = value.replace(r"\/", "/")
    value = value.replace(r"\u0025", "%")
    value = value.replace(r"\u0026", "&")
    value = value.replace(r"\u003D", "=")
    value = value.replace(r"\u002F", "/")
    value = value.replace(r"\u003A", ":")
    value = value.replace(r"\u0026amp;", "&")

    # Decode JSON-style escaped characters when present.
    try:
        if r"\u" in value or r"\/" in value:
            value = json.loads('"' + value.replace('"', r'\"') + '"')
    except Exception:
        pass

    value = html.unescape(value).replace(r"\/", "/")

    if value.startswith("//"):
        value = "https:" + value

    if value.startswith(("https://", "http://")):
        return value

    return None


def _find_html_video(html_text: str) -> Optional[str]:
    # Common Facebook video fields, including escaped JSON values.
    patterns = [
        r'"playable_url_quality_hd"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"playable_url"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"browser_native_hd_url"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"browser_native_sd_url"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"hd_src"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"sd_src"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'hd_src\s*:\s*"((?:\\.|[^"\\])*)"',
        r'sd_src\s*:\s*"((?:\\.|[^"\\])*)"',
    ]

    for pattern in patterns:
        match = re.search(pattern, html_text, re.I)
        if match:
            candidate = _clean_url(match.group(1))
            if candidate:
                return candidate

    # Open Graph video tags, allowing either attribute order.
    meta_tags = re.findall(
        r"<meta\b[^>]*>",
        html_text,
        flags=re.I | re.S,
    )

    for tag in meta_tags:
        prop = re.search(
            r"""(?:property|name)\s*=\s*["']([^"']+)["']""",
            tag,
            re.I,
        )
        content = re.search(
            r"""content\s*=\s*["']([^"']+)["']""",
            tag,
            re.I,
        )

        if prop and content:
            key = prop.group(1).lower()
            if key in (
                "og:video",
                "og:video:url",
                "og:video:secure_url",
            ):
                candidate = _clean_url(content.group(1))
                if candidate:
                    return candidate

    return None


def _find_thumbnail(html_text: str) -> Optional[str]:
    for tag in re.findall(r"<meta\b[^>]*>", html_text, re.I | re.S):
        prop = re.search(
            r"""(?:property|name)\s*=\s*["']([^"']+)["']""",
            tag,
            re.I,
        )
        content = re.search(
            r"""content\s*=\s*["']([^"']+)["']""",
            tag,
            re.I,
        )

        if prop and content and prop.group(1).lower() == "og:image":
            return _clean_url(content.group(1))

    return None


def _make_result(
    url: str,
    video_url: str,
    thumbnail: Optional[str] = None,
    title: str = "Facebook Video",
) -> Dict[str, Any]:
    return {
        "title": title or "Facebook Video",
        "videoUrl": video_url,
        "thumbnailUrl": thumbnail,
        "platform": "Facebook",
        "sourceUrl": url,
        "qualityOptions": [
            {
                "label": "فيديو Facebook (MP4)",
                "url": video_url,
                "format": "mp4",
                "isHd": True,
            }
        ],
        "carouselItems": [
            {
                "url": video_url,
                "format": "mp4",
                "label": "Facebook Video (MP4)",
                "isHd": True,
            }
        ],
        "photoUrls": [],
    }


def _extract_with_ytdlp(url: str) -> Dict[str, Any]:
    executable = shutil.which("yt-dlp")

    # Common standalone location on Android/Pydroid.
    if not executable:
        candidate = "/storage/emulated/0/Download/bin/yt-dlp"
        if os.path.isfile(candidate):
            executable = candidate

    if executable:
        command = [
            executable,
            "--dump-single-json",
            "--no-warnings",
            "--no-playlist",
            "--socket-timeout", str(TIMEOUT),
            url,
        ]
    else:
        # Try the installed Python module.
        command = [
            sys.executable, "-m", "yt_dlp",
            "--dump-single-json",
            "--no-warnings",
            "--no-playlist",
            "--socket-timeout", str(TIMEOUT),
            url,
        ]

    try:
        proc = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=TIMEOUT + 5,
            check=False,
        )
    except subprocess.TimeoutExpired:
        raise ValueError("yt-dlp timed out")

    if proc.returncode != 0:
        detail = (proc.stderr or "yt-dlp extraction failed").strip()
        raise ValueError("yt-dlp: " + detail[-350:])

    try:
        data = json.loads(proc.stdout)
    except Exception:
        raise ValueError("yt-dlp returned invalid JSON")

    video = _clean_url(data.get("url"))

    if not video:
        for fmt in reversed(data.get("formats") or []):
            if not isinstance(fmt, dict):
                continue

            if fmt.get("vcodec") == "none":
                continue

            candidate = _clean_url(fmt.get("url"))
            if candidate:
                video = candidate
                break

    if not video:
        raise ValueError("yt-dlp returned no video URL")

    return _make_result(
        url=url,
        video_url=video,
        thumbnail=_clean_url(data.get("thumbnail")),
        title=data.get("title") or "Facebook Video",
    )


def extract(
    url: str,
    config: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:

    if not url or not isinstance(url, str):
        raise ValueError("Facebook URL is empty")

    url = url.strip()

    if not re.match(r"^https?://", url, re.I):
        url = "https://" + url

    host = (urlparse(url).hostname or "").lower()

    allowed_hosts = (
        "facebook.com",
        "fb.watch",
    )

    if not any(
        host == domain or host.endswith("." + domain)
        for domain in allowed_hosts
    ):
        raise ValueError("Invalid Facebook URL")

    errors = []
    final_url = url
    thumbnail = None

    # Strategy 1: Fetch the public page and extract media metadata.
    try:
        status, page, redirected_url = http_get(
            url,
            headers=HEADERS,
            timeout=TIMEOUT,
            allow_redirects=True,
        )

        if redirected_url and redirected_url.startswith("http"):
            final_url = redirected_url

        if status == 200 and page:
            lowered = page.lower()

            if "login_form" in lowered or "checkpoint" in lowered:
                errors.append(
                    "Facebook returned a login or checkpoint page"
                )
            else:
                thumbnail = _find_thumbnail(page)
                video = _find_html_video(page)

                if video:
                    return _make_result(
                        url=url,
                        video_url=video,
                        thumbnail=thumbnail,
                    )

                errors.append(
                    "Page loaded, but no public video URL was found"
                )
        else:
            errors.append(
                f"Facebook page HTTP {status}"
            )

    except Exception as exc:
        errors.append(
            f"HTML extraction: {type(exc).__name__}: {exc}"
        )

    # Strategy 2: yt-dlp fallback.
    try:
        result = _extract_with_ytdlp(final_url)
        if not result.get("thumbnailUrl"):
            result["thumbnailUrl"] = thumbnail
        result["sourceUrl"] = url
        return result

    except Exception as exc:
        errors.append(
            f"yt-dlp: {type(exc).__name__}: {exc}"
        )

    raise ValueError(
        "Facebook extraction failed | " + " | ".join(errors)
    )
