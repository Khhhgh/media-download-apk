#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Pinterest Dynamic Media Extraction Engine (downapp/Pinterest/engine.py)

Extraction runs on the device, so when Pinterest is blocked without a VPN the
direct page/API requests fail. The engine therefore tries, in order:

1. Direct pinimg file link          - nothing to resolve.
2. PinResource JSON (auth_web_main_pin) - the small authoritative payload that
   carries every rendition of a video pin.
3. Pin page HTML (direct)           - the Relay payload embedded in the page.
4. Pin page HTML (via public relay) - same page fetched from an unblocked host.
5. oEmbed                           - last resort for an image pin.

Every strategy keeps the same return schema.
"""

import json
import os
import re
import sys
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlparse, urljoin, quote

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get, http_get_location, relay_get, fetch, UNIVERSAL_USER_AGENT


TIMEOUT = 15
RELAY_TIMEOUT = 25

PIN_ID_RE = re.compile(r"/pin/(?:[\w-]+--)?(\d{5,})")
SLUG_RE = re.compile(r"^[A-Za-z0-9]{4,}$")
MEDIA_EXT = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp4", ".m3u8", ".webm")

PAGE_HEADERS = {
    "User-Agent": UNIVERSAL_USER_AGENT,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}

PIN_API_HEADERS = {
    "User-Agent": UNIVERSAL_USER_AGENT,
    "X-Pinterest-PWS-Handler": "www/index.js",
    "Accept": "application/json, text/javascript, */*; q=0.01",
    "X-Requested-With": "XMLHttpRequest",
}


def canonical_pin_url(pin_id: str) -> str:
    return f"https://www.pinterest.com/pin/{pin_id}/"


def pin_id_from_url(url: str) -> Optional[str]:
    match = PIN_ID_RE.search(url or "")
    return match.group(1) if match else None


def _is_direct_media_url(url: str) -> bool:
    if not url or not url.startswith("http"):
        return False
    try:
        host = (urlparse(url).hostname or "").lower()
    except Exception:
        return False
    return host == "pinimg.com" or host.endswith(".pinimg.com")


def _shortener_slug(url: str) -> Optional[str]:
    parsed = urlparse(url or "")
    host = (parsed.hostname or "").lower()
    if host == "pin.it" or host.endswith(".pin.it"):
        parts = [p for p in parsed.path.split("/") if p]
        if parts and SLUG_RE.match(parts[0]):
            return parts[0]
    match = re.search(r"/url_shortener/([A-Za-z0-9]+)", parsed.path or "")
    if match:
        return match.group(1)
    return None


def _pin_id_from_html(html: str, allow_bare: bool = False) -> Optional[str]:
    if not html:
        return None
    for pattern in (
        r'<link[^>]+rel="canonical"[^>]+href="([^"]+)"',
        r'<link[^>]+href="([^"]+)"[^>]+rel="canonical"',
        r'property="og:url"\s+content="([^"]+)"',
        r'content="([^"]+)"\s+property="og:url"',
    ):
        match = re.search(pattern, html, re.I | re.S)
        if match:
            pid = pin_id_from_url(match.group(1))
            if pid:
                return pid
    if allow_bare:
        match = PIN_ID_RE.search(html)
        if match:
            return match.group(1)
    return None


def resolve_pin_id(url: str) -> Optional[str]:
    """Reduces any shared Pinterest link to its numeric pin id."""
    if _is_direct_media_url(url):
        return None

    pid = pin_id_from_url(url)
    if pid:
        return pid

    current = url
    for _ in range(6):
        pid = pin_id_from_url(current)
        if pid:
            return pid

        slug = _shortener_slug(current)
        if slug and "api.pinterest.com" not in current:
            current = f"https://api.pinterest.com/url_shortener/{slug}/redirect/"
            continue

        location = http_get_location(current, headers=PAGE_HEADERS, timeout=8)
        if location:
            current = urljoin(current, location)
            continue

        status, body, _ = http_get(current, headers=PAGE_HEADERS, timeout=8, allow_redirects=True)
        if status == 200:
            pid = _pin_id_from_html(body, allow_bare=bool(pin_id_from_url(current)))
            if pid:
                return pid
        break

    pid = pin_id_from_url(current)
    if pid:
        return pid

    # Last resort on a blocked network: ask a relay for the page and read the id.
    for _ in range(4):
        relay_status, relay_body, _ = relay_get(current, headers=PAGE_HEADERS, timeout=RELAY_TIMEOUT, min_length=100)
        if relay_status != 200:
            break
        pid = _pin_id_from_html(relay_body, allow_bare=False)
        if pid:
            return pid
        slug = _shortener_slug(current)
        if not slug:
            break
        current = f"https://api.pinterest.com/url_shortener/{slug}/redirect/"

    return None


# ---------------------------------------------------------------------------
# Media collection
# ---------------------------------------------------------------------------
def _dedupe(items: List[str]) -> List[str]:
    return list(dict.fromkeys(i for i in items if i))


def _video_score(url: str) -> int:
    score = 0
    if "expMp4" in url:
        score += 100
    if "h265" in url or "av1" in url or "av1Mp4" in url:
        score -= 50
    width = re.search(r"_(\d{3,4})w?\.mp4", url)
    if width:
        score += int(width.group(1)) // 100
    return score


def _rank_videos(urls: List[str]) -> List[str]:
    unique = _dedupe([u for u in urls if ".mp4" in u.lower() and "thumbnail" not in u.lower()])
    return sorted(unique, key=lambda u: -_video_score(u))


def _video_urls_from_pin(pin: Dict[str, Any]) -> List[str]:
    urls: List[str] = []

    videos = pin.get("videos")
    if isinstance(videos, dict):
        section = videos.get("video_list") or videos.get("videoList") or {}
        if isinstance(section, dict):
            for entry in section.values():
                if isinstance(entry, dict):
                    url = entry.get("url")
                    if isinstance(url, str) and url.startswith("http") and not url.endswith(".vtt"):
                        urls.append(url)

    story = pin.get("storyPinData") or pin.get("story_pin_data")
    if isinstance(story, dict):
        for page in story.get("pages") or []:
            if not isinstance(page, dict):
                continue
            for block in page.get("blocks") or []:
                if not isinstance(block, dict):
                    continue
                data = block.get("videoDataV2") or block.get("videoData") or {}
                for key in ("videoList", "v_hlsv4_video_list", "videoList720P", "videoListMobile"):
                    section = data.get(key)
                    if not isinstance(section, dict):
                        continue
                    for entry in section.values():
                        if isinstance(entry, dict):
                            url = entry.get("url")
                            if isinstance(url, str) and "/videos/" in url and not url.endswith(".vtt"):
                                urls.append(url)
                                break

    return urls


def _image_urls_from_pin(pin: Dict[str, Any]) -> List[str]:
    urls: List[str] = []

    def _entry_url(entry: Any) -> Optional[str]:
        if isinstance(entry, dict) and isinstance(entry.get("url"), str):
            return entry["url"]
        if isinstance(entry, str) and entry.startswith("http"):
            return entry
        return None

    for key in ("images_orig", "imageSpec_orig", "imageLargeUrl"):
        value = _entry_url(pin.get(key))
        if value:
            urls.append(value)

    images = pin.get("images")
    if isinstance(images, dict):
        for key, entry in images.items():
            value = _entry_url(entry)
            if value:
                urls.append(value)

    # Prefer the true /originals/ twin over derived sizes.
    expanded: List[str] = []
    for url in urls:
        if "/originals/" not in url:
            twin = re.sub(r"/(?:\d+x(?:\d+)?(?:_RS)?)/", "/originals/", url, count=1)
            if twin != url:
                expanded.append(twin)
        expanded.append(url)

    expanded.sort(key=lambda u: 0 if "/originals/" in u else 1)
    return _dedupe(expanded)


def _urls_from_html(html: str) -> Tuple[List[str], List[str]]:
    """Best-effort media URLs from a pin page when the payload is not parsed."""
    text = html.replace(r"\/", "/").replace(r"\u002F", "/").replace("&amp;", "&")

    videos = re.findall(r"https?://v1\.pinimg\.com/videos/[^\"'\\\s<>]+?\.mp4[^\"'\\\s<>]*", text)
    videos += re.findall(r"https?://v1\.pinimg\.com/videos/[^\"'\\\s<>]+\.mp4", text)

    images = re.findall(r'"images_orig":\{"url":"(https?://[^"]+)"', text)
    if not images:
        images = re.findall(r'https?://i\.pinimg\.com/originals/[0-9a-fA-F]{2}/[0-9a-fA-F]{2}/[0-9a-fA-F]{2}/[0-9a-fA-F]+\.(?:jpg|jpeg|png|gif)', text)

    return _dedupe(videos), _dedupe(images)


def _find_pin_object(html: str, pin_id: str) -> Optional[Dict[str, Any]]:
    """Locates the pin's own JSON object inside the page scripts."""
    decoder = json.JSONDecoder()

    candidates: List[str] = []
    candidates += re.findall(r"<script[^>]*data-relay-[a-z-]*request[^>]*>(.*?)</script>", html, re.S)
    candidates += re.findall(r'<script[^>]*id="(?:__PWS_[A-Z_]+)"[^>]*>(.*?)</script>', html, re.S)

    def walk(node: Any, depth: int = 0) -> Optional[Dict[str, Any]]:
        if depth > 40:
            return None
        if isinstance(node, dict):
            ident = str(node.get("entityId") or node.get("id") or "")
            if ident == pin_id and any(
                k in node for k in ("images", "images_orig", "videos", "storyPinData", "isVideo", "imageLargeUrl")
            ):
                return node
            for value in node.values():
                found = walk(value, depth + 1)
                if found:
                    return found
        elif isinstance(node, list):
            for value in node:
                found = walk(value, depth + 1)
                if found:
                    return found
        return None

    for body in candidates:
        if not body:
            continue
        for start in (m.start() for m in re.finditer(r"\{", body)):
            try:
                obj, _ = decoder.raw_decode(body, start)
            except Exception:
                continue
            found = walk(obj)
            if found:
                return found
    return None


def _page_title(html: str) -> Optional[str]:
    for pattern in (
        r'<meta[^>]+property="og:title"[^>]+content="([^"]+)"',
        r'<meta[^>]+name="twitter:title"[^>]+content="([^"]+)"',
        r"<title>([^<]+)</title>",
    ):
        match = re.search(pattern, html, re.I)
        if match:
            title = match.group(1).strip()
            title = re.sub(r"\s*[|\-–]\s*Pinterest\s*$", "", title).strip()
            if title:
                return title
    return None


def _build_result(
    title: str, source: str, video: Optional[str], image: Optional[str], thumbnail: Optional[str]
) -> Dict[str, Any]:
    if video:
        return {
            "title": (title or "Pinterest Video").strip()[:60],
            "videoUrl": video,
            "thumbnailUrl": thumbnail or image,
            "platform": "Pinterest",
            "sourceUrl": source,
            "qualityOptions": [
                {"label": "فيديو عالي الدقة (MP4)", "url": video, "format": "mp4", "isHd": True},
            ],
            "carouselItems": [
                {"url": video, "format": "mp4", "label": "Pinterest Video (MP4)", "isHd": True},
            ],
            "photoUrls": [],
        }

    ext = "jpg"
    match = re.search(r"\.(jpe?g|png|webp|gif)(?:\?|$)", image or "", re.I)
    if match:
        ext = match.group(1).lower()

    return {
        "title": (title or "Pinterest Image").strip()[:60],
        "videoUrl": image,
        "thumbnailUrl": image,
        "platform": "Pinterest",
        "sourceUrl": source,
        "qualityOptions": [
            {"label": f"صورة أصلية عالية الدقة ({ext.upper()})", "url": image, "format": ext, "isHd": True},
        ],
        "carouselItems": [
            {"url": image, "format": ext, "label": f"Pinterest Image ({ext.upper()})", "isHd": True},
        ],
        "photoUrls": [image],
    }


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------
def _extract_direct_media(url: str) -> Dict[str, Any]:
    if ".mp4" in url.lower() or ".m3u8" in url.lower() or ".webm" in url.lower():
        return _build_result("Pinterest Video", url, url, None, None)
    return _build_result("Pinterest Image", url, None, url, url)


def _extract_pin_resource(pin_id: str) -> Dict[str, Any]:
    data = json.dumps({
        "options": {"id": pin_id, "field_set_key": "auth_web_main_pin"},
        "context": {},
    })
    api = "https://www.pinterest.com/resource/PinResource/get/?data=" + quote(data)
    headers = dict(PIN_API_HEADERS)
    headers["Referer"] = canonical_pin_url(pin_id)

    status, body, _ = fetch(api, headers=headers, timeout=TIMEOUT, use_relay=True, response_format="text")
    if status != 200 or not body:
        raise ValueError(f"PinResource HTTP {status}")

    # Relays may wrap the JSON payload; keep only the object.
    start = body.find("{")
    if start > 0:
        body = body[start:]

    payload = json.loads(body)
    pin = (payload.get("resource_response") or {}).get("data")
    if not isinstance(pin, dict):
        raise ValueError("PinResource returned no pin data")
    return _result_from_pin(pin, canonical_pin_url(pin_id))


def _extract_pin_page(pin_id: str, url: str, use_relay: bool) -> Dict[str, Any]:
    page = canonical_pin_url(pin_id)
    if use_relay:
        status, html, _ = relay_get(page, headers=PAGE_HEADERS, timeout=RELAY_TIMEOUT, min_length=1000)
    else:
        status, html, _ = http_get(page, headers=PAGE_HEADERS, timeout=TIMEOUT, allow_redirects=True)

    if status != 200 or not html:
        raise ValueError(f"Pin page HTTP {status}")

    page_title = _page_title(html)
    canonical = _pin_id_from_html(html)

    pin = _find_pin_object(html, pin_id)
    if pin:
        return _result_from_pin(pin, url, fallback_title=page_title)

    # Raw page scraping is only trusted when the page really is a pin page
    # (a missing pin lands on a generic page full of unrelated media).
    if canonical:
        videos, images = _urls_from_html(html)
        if videos:
            return _build_result(page_title or "Pinterest Video", url, _rank_videos(videos)[0], None, None)
        if images:
            return _build_result(page_title or "Pinterest Image", url, None, images[0], images[0])

    raise ValueError("No media found in the pin page")


def _extract_oembed(url: str) -> Dict[str, Any]:
    import urllib.parse
    api = "https://www.pinterest.com/oembed.json?url=" + urllib.parse.quote(url, safe="")
    status, body, _ = fetch(
        api, headers={"User-Agent": UNIVERSAL_USER_AGENT}, timeout=TIMEOUT, response_format="text"
    )
    if status != 200 or not body:
        raise ValueError(f"oEmbed HTTP {status}")
    # Relays may wrap the JSON payload; keep only the object.
    start = body.find("{")
    if start > 0:
        body = body[start:]
    payload = json.loads(body)
    thumbnail = payload.get("thumbnail_url")
    if not thumbnail:
        raise ValueError("oEmbed returned no media")
    return _build_result(payload.get("title") or "Pinterest Image", url, None, thumbnail, thumbnail)


def _result_from_pin(pin: Dict[str, Any], source: str, fallback_title: Optional[str] = None) -> Dict[str, Any]:
    title = pin.get("gridTitle") or pin.get("grid_title") or pin.get("title") or fallback_title or "Pinterest Media"

    videos = _video_urls_from_pin(pin)
    if videos:
        ranked = _rank_videos(videos)
        if ranked:
            cover = (_image_urls_from_pin(pin) or [None])[0]
            return _build_result(title, source, ranked[0], None, cover)
        # A progressive MP4 could not be derived; still hand back a video source.
        return _build_result(title, source, videos[0], None, None)

    images = _image_urls_from_pin(pin)
    if images:
        return _build_result(title, source, None, images[0], images[0])

    raise ValueError("Pin payload carries no media")


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    url = (url or "").strip()
    if not re.match(r"^https?://", url, re.I):
        url = "https://" + url

    if _is_direct_media_url(url):
        return _extract_direct_media(url)

    pin_id = resolve_pin_id(url)

    errors = []

    if pin_id:
        strategies = [
            ("Pin page", lambda: _extract_pin_page(pin_id, url, False)),
            ("PinResource", lambda: _extract_pin_resource(pin_id)),
            ("Pin page (relay)", lambda: _extract_pin_page(pin_id, url, True)),
            ("oEmbed", lambda: _extract_oembed(url)),
        ]
    else:
        strategies = [
            ("oEmbed", lambda: _extract_oembed(url)),
        ]

    for name, strategy in strategies:
        try:
            result = strategy()
            if not isinstance(result, dict):
                raise ValueError("Invalid extraction result")
            if not (result.get("videoUrl") or result.get("photoUrls")):
                raise ValueError("No media URL in result")
            return result
        except Exception as exc:
            message = f"{name}: {type(exc).__name__}: {exc}"
            errors.append(message)
            try:
                print(f"[Pinterest Engine] {message}", flush=True)
            except Exception:
                pass

    raise ValueError("Failed to extract Pinterest media. " + " | ".join(errors[-3:]))
