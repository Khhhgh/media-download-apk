
#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
TikTok Dynamic Media Extraction Engine
Keeps the existing extract() return schema and URL resolution.
Strategies:
1. TikWM
2. Public fallback API
3. yt-dlp fallback
Each strategy has a 12-second timeout.
"""

import json
import os
import re
import sys
import shutil
import subprocess
from typing import Any, Dict, Optional
from urllib.parse import urlencode

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get, http_post, UNIVERSAL_USER_AGENT


TIMEOUT = 12

TIKWM_ENDPOINTS = [
    "https://www.tikwm.com/api/",
    "https://tikwm.com/api/",
]

# Public third-party fallback API.
# Availability and rate limits are controlled by its operator.
FALLBACK_API = "https://tdownv4.sl-bjs.workers.dev/"


def resolve_tiktok_url(url: str) -> str:
    if not url:
        return ""

    url = url.strip()
    if not re.match(r"^https?://", url, re.I):
        url = "https://" + url

    direct = re.search(
        r"https?://(?:www\.|m\.)?tiktok\.com/"
        r"@[^/?#]+/(?:video|photo)/\d+",
        url,
        re.I,
    )
    if direct:
        return direct.group(0)

    if re.search(r"(?:vt|vm)\.tiktok\.com|/t/", url, re.I):
        try:
            status, body, final_url = http_get(
                url, timeout=TIMEOUT, allow_redirects=True
            )
            if final_url and final_url.startswith("http"):
                match = re.search(
                    r"https?://(?:www\.|m\.)?tiktok\.com/"
                    r"@[^/?#]+/(?:video|photo)/\d+",
                    final_url,
                    re.I,
                )
                if match:
                    return match.group(0)
                if "tiktok.com" in final_url.lower():
                    return final_url.split("?")[0].split("#")[0].rstrip("/")
        except Exception:
            pass

    return url.split("?")[0].split("#")[0].rstrip("/")


def _valid_media_url(url: Any) -> bool:
    return isinstance(url, str) and url.startswith(("https://", "http://"))


def _fix_url(url: Any) -> Optional[str]:
    if not url or not isinstance(url, str):
        return None
    if url.startswith("//"):
        return "https:" + url
    if url.startswith("/"):
        return "https://www.tikwm.com" + url
    if url.startswith(("https://", "http://")):
        return url
    return None


def _base_result(
    title: str,
    author: str,
    source: str,
    video: Optional[str] = None,
    cover: Optional[str] = None,
    audio: Optional[str] = None,
    photos: Optional[list] = None,
) -> Dict[str, Any]:
    photos = photos or []
    qualities = []
    carousel = []

    if photos:
        for i, photo in enumerate(photos):
            carousel.append({
                "url": photo,
                "format": "jpg",
                "label": f"سلايد {i + 1} (صورة JPG)",
                "isHd": True,
            })
        qualities.append({
            "label": f"تحميل جميع الصور ({len(photos)} صورة JPG)",
            "url": photos[0],
            "format": "album",
            "isHd": True,
        })
        video = photos[0]

    elif video:
        qualities.append({
            "label": "فيديو بدون علامة مائية (MP4)",
            "url": video,
            "format": "mp4",
            "isHd": True,
        })
        carousel.append({
            "url": video,
            "format": "mp4",
            "label": "TikTok No Watermark (MP4)",
            "isHd": True,
        })

    if audio:
        qualities.append({
            "label": "تحميل الصوت الأصلي",
            "url": audio,
            "format": "mp3",
            "isHd": False,
        })

    return {
        "title": (title or "TikTok Media").strip()[:60],
        "videoUrl": video,
        "thumbnailUrl": cover,
        "platform": "TikTok",
        "author": author or "",
        "sourceUrl": source,
        "audioUrl": audio,
        "qualityOptions": qualities,
        "carouselItems": carousel,
        "photoUrls": photos,
    }


def _extract_tikwm(target: str) -> Dict[str, Any]:
    errors = []

    for endpoint in TIKWM_ENDPOINTS:
        try:
            status, body, _ = http_post(
                endpoint,
                data={
                    "url": target,
                    "count": 12,
                    "cursor": 0,
                    "web": 1,
                    "hd": 1,
                },
                headers={
                    "User-Agent": UNIVERSAL_USER_AGENT,
                    "Referer": "https://www.tikwm.com/",
                    "Accept": "application/json, text/plain, */*",
                },
                timeout=TIMEOUT,
            )

            if status != 200:
                errors.append(f"TikWM HTTP {status}")
                continue

            result = json.loads(body)
            if result.get("code") != 0:
                errors.append(str(result.get("msg") or "TikWM rejected request"))
                continue

            data = result.get("data") or {}
            if not data:
                errors.append("TikWM returned empty data")
                continue

            author_data = data.get("author") or {}
            author = (
                author_data.get("unique_id")
                or author_data.get("nickname")
                or ""
            )

            title = data.get("title") or "TikTok Media"
            cover = _fix_url(data.get("cover") or data.get("origin_cover"))
            audio = _fix_url(data.get("music") or data.get("play_url"))

            images = data.get("images") or data.get("imagePost") or []
            if isinstance(images, list):
                photos = [
                    fixed for item in images
                    if (fixed := _fix_url(item))
                ]
            else:
                photos = []

            if photos:
                return _base_result(
                    title, author, target,
                    cover=cover, audio=audio, photos=photos
                )

            hd = _fix_url(data.get("hdplay"))
            sd = _fix_url(data.get("play") or data.get("wmplay"))
            video = hd or sd

            if video and _valid_media_url(video):
                result = _base_result(
                    title, author, target,
                    video=video, cover=cover, audio=audio
                )

                # Preserve HD and SD choices when both are available.
                options = []
                if hd:
                    options.append({
                        "label": "فيديو عالي الدقة (HD MP4)",
                        "url": hd,
                        "format": "mp4",
                        "isHd": True,
                    })
                if sd and sd != hd:
                    options.append({
                        "label": "فيديو سريع (SD MP4)",
                        "url": sd,
                        "format": "mp4",
                        "isHd": False,
                    })
                if audio:
                    options.append({
                        "label": "تحميل الصوت الأصلي",
                        "url": audio,
                        "format": "mp3",
                        "isHd": False,
                    })
                if options:
                    result["qualityOptions"] = options
                return result

            errors.append("TikWM response has no usable media URL")

        except Exception as exc:
            errors.append(f"{type(exc).__name__}: {exc}")

    raise ValueError("; ".join(errors[-4:]) or "TikWM failed")


def _extract_fallback_api(target: str) -> Dict[str, Any]:
    query = urlencode({"down": target})
    endpoint = FALLBACK_API + "?" + query

    status, body, _ = http_get(
        endpoint,
        headers={
            "User-Agent": UNIVERSAL_USER_AGENT,
            "Accept": "application/json",
        },
        timeout=TIMEOUT,
    )

    if status != 200:
        raise ValueError(f"Fallback API HTTP {status}")

    try:
        payload = json.loads(body)
    except Exception:
        raise ValueError("Fallback API returned invalid JSON")

    # Accept common response wrappers.
    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, dict):
        data = payload if isinstance(payload, dict) else {}

    video = (
        data.get("download_url")
        or data.get("video_url")
        or data.get("play")
        or data.get("url")
    )
    audio = (
        data.get("audio_url")
        or data.get("music")
    )

    author_data = data.get("author") or {}
    if isinstance(author_data, dict):
        author = (
            author_data.get("username")
            or author_data.get("unique_id")
            or author_data.get("nickname")
            or ""
        )
    else:
        author = str(author_data or "")

    title = data.get("title") or "TikTok Video"
    cover = _fix_url(data.get("cover") or data.get("thumbnail"))

    images = data.get("images") or data.get("image_urls") or []
    if isinstance(images, list):
        photos = [
            fixed for item in images
            if (fixed := _fix_url(item))
        ]
    else:
        photos = []

    if photos:
        return _base_result(
            title, author, target,
            cover=cover, audio=_fix_url(audio), photos=photos
        )

    video = _fix_url(video)
    if not video:
        raise ValueError("Fallback API returned no usable video URL")

    return _base_result(
        title, author, target,
        video=video, cover=cover, audio=_fix_url(audio)
    )


def _extract_ytdlp(target: str) -> Dict[str, Any]:
    executable = shutil.which("yt-dlp")

    # Common Pydroid installation path used by this project.
    if not executable:
        candidate = "/storage/emulated/0/Download/bin/yt-dlp"
        if os.path.isfile(candidate):
            executable = candidate

    if not executable:
        raise ValueError("yt-dlp executable not found")

    command = [
        executable,
        "--dump-single-json",
        "--no-warnings",
        "--no-playlist",
        "--no-check-certificates",
        "--socket-timeout", str(TIMEOUT),
        target,
    ]

    try:
        proc = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=TIMEOUT,
            check=False,
        )
    except subprocess.TimeoutExpired:
        raise ValueError("yt-dlp timed out after 12 seconds")

    if proc.returncode != 0:
        detail = (proc.stderr or "yt-dlp failed").strip()
        raise ValueError(detail[-500:])

    try:
        info = json.loads(proc.stdout)
    except Exception:
        raise ValueError("yt-dlp returned invalid JSON")

    # yt-dlp may expose carousel images in thumbnails.
    thumbnails = info.get("thumbnails") or []
    photos = []
    for item in thumbnails:
        if not isinstance(item, dict):
            continue
        image_url = _fix_url(item.get("url"))
        if image_url and item.get("id") in ("image", "photo"):
            photos.append(image_url)

    if info.get("_type") == "multi_video":
        entries = info.get("entries") or []
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            image_url = _fix_url(entry.get("url"))
            if image_url:
                photos.append(image_url)

    # Do not misclassify ordinary video thumbnails as photo posts.
    if photos and info.get("album") is True:
        return _base_result(
            info.get("title") or "TikTok Photos",
            info.get("uploader") or "",
            target,
            cover=_fix_url(info.get("thumbnail")),
            photos=list(dict.fromkeys(photos)),
        )

    video = _fix_url(info.get("url"))
    if not video:
        for fmt in reversed(info.get("formats") or []):
            if isinstance(fmt, dict) and fmt.get("vcodec") != "none":
                video = _fix_url(fmt.get("url"))
                if video:
                    break

    if not video:
        raise ValueError("yt-dlp found no direct video URL")

    return _base_result(
        info.get("title") or "TikTok Video",
        info.get("uploader") or info.get("creator") or "",
        target,
        video=video,
        cover=_fix_url(info.get("thumbnail")),
        audio=_fix_url(info.get("audio_url")),
    )


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    resolved_url = resolve_tiktok_url(url)

    if not resolved_url or "tiktok.com" not in resolved_url.lower():
        raise ValueError("Invalid TikTok URL")

    errors = []

    strategies = [
        ("TikWM", _extract_tikwm),
        ("Fallback API", _extract_fallback_api),
        ("yt-dlp", _extract_ytdlp),
    ]

    for name, strategy in strategies:
        try:
            result = strategy(resolved_url)

            if not isinstance(result, dict):
                raise ValueError("Invalid extraction result")

            has_photos = bool(result.get("photoUrls"))
            has_video = _valid_media_url(result.get("videoUrl"))

            if not has_photos and not has_video:
                raise ValueError("No valid media URL in result")

            return result

        except Exception as exc:
            message = f"{name}: {type(exc).__name__}: {exc}"
            errors.append(message)
            try:
                print(f"[TikTok Engine] {message}", flush=True)
            except Exception:
                pass

    raise ValueError(
        "All TikTok extraction strategies failed | "
        + " | ".join(errors)
    )
