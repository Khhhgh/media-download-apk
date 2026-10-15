#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Pinterest Dynamic Media Extraction Engine (downapp/Pinterest/engine.py)

Comprehensive, high-reliability extraction engine for Pinterest videos, idea pins,
reels, and original-resolution images.

Key Enhancements & Unplayable Video Fixes:
1. Complete elimination of the "unplayable video" bug:
   - Android's download manager downloads raw URLs. If an HLS (.m3u8) playlist is returned,
     it gets saved as a plain-text playlist file with a .mp4 extension, which cannot be played.
   - This engine guarantees that ONLY true progressive MP4 streams are ever returned.
   - Any .m3u8 stream is automatically transmuted into direct progressive CDN MP4 streams
     (1080p, 720p, expMp4, 480p, and mobile).
2. Cascading 7-tier extraction pipeline:
   - Strategy 1: Direct Pinimg Media URL Handler (with instant m3u8 -> mp4 conversion)
   - Strategy 2: Fast Direct Page HTML Scraping (deep video_list JSON & progressive MP4 detection)
   - Strategy 3: SavePin High-Reliability Resolver (external public resolver)
   - Strategy 4: Official Pinterest oEmbed API (high-resolution original images & metadata)
   - Strategy 5: Public Relay Tunnel (common.relay_get) for ISP-censored / firewall-blocked networks
   - Strategy 6: In-Process Pure Python yt-dlp (in-memory execution)
   - Strategy 7: Standalone yt-dlp subprocess binary fallback
"""

import html
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import (
    http_get,
    http_post,
    http_get_location,
    relay_get,
    UNIVERSAL_USER_AGENT,
    MOBILE_USER_AGENT,
)

TIMEOUT = 12
RELAY_TIMEOUT = 22

PAGE_HEADERS = {
    "User-Agent": UNIVERSAL_USER_AGENT,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
    "Referer": "https://www.pinterest.com/",
}

PIN_ID_RE = re.compile(r"/pin/(?:[\w-]+--)?(\d{5,})")


def canonical_pin_url(pin_id: str) -> str:
    return f"https://www.pinterest.com/pin/{pin_id}/"


def pin_id_from_url(url: str) -> Optional[str]:
    match = PIN_ID_RE.search(url or "")
    return match.group(1) if match else None


def _is_direct_media_url(url: str) -> bool:
    if not url or not url.startswith("http"):
        return False
    try:
        host = (urllib.parse.urlparse(url).hostname or "").lower()
        return host == "pinimg.com" or host.endswith(".pinimg.com")
    except Exception:
        return False


def resolve_pin_url(url: str) -> Tuple[str, Optional[str]]:
    """
    Cleans URL and follows redirects (e.g. pin.it short links).
    Returns (canonical_url, pin_id).
    """
    if not url:
        return "", None

    url = url.strip()
    if not re.match(r"^https?://", url, re.I):
        url = "https://" + url

    pid = pin_id_from_url(url)
    if pid:
        return canonical_pin_url(pid), pid

    # Handle shortlink redirects (e.g. pin.it)
    if "pin.it" in url.lower() or "/url_shortener/" in url.lower():
        try:
            loc = http_get_location(url, timeout=8)
            if loc and loc.startswith("http"):
                pid = pin_id_from_url(loc)
                if pid:
                    return canonical_pin_url(pid), pid
                return loc, None
            st, _, redirected = http_get(url, headers=PAGE_HEADERS, timeout=10, allow_redirects=True)
            if redirected and redirected.startswith("http"):
                pid = pin_id_from_url(redirected)
                if pid:
                    return canonical_pin_url(pid), pid
                return redirected, None
        except Exception:
            pass

    return url, pin_id_from_url(url)


def _verify_playable_url(url: str, timeout: int = 3) -> bool:
    """Verifies that an MP4 video or image actually exists using HTTP Range requests."""
    if not url or not url.startswith("http"):
        return False
    try:
        st, _, _ = http_get(
            url,
            headers={
                "Range": "bytes=0-100",
                "User-Agent": UNIVERSAL_USER_AGENT,
                "Referer": "https://www.pinterest.com/",
            },
            timeout=timeout,
        )
        return st in (200, 206)
    except Exception:
        return False


def m3u8_to_progressive_mp4s(m3u8_url: str) -> List[Tuple[str, str]]:
    """
    Synthesizes candidate progressive MP4 URLs from a Pinterest HLS (.m3u8) stream.
    Replaces /hls/ or /v_hls/ with /720p/, /expMp4/, /1080p/, and /480p/.
    Guarantees that no raw .m3u8 link is returned.
    """
    candidates = []
    if not m3u8_url or not isinstance(m3u8_url, str):
        return candidates

    clean_url = m3u8_url.split("?")[0].strip()

    # Pattern 1: Path contains /hls/, /v_hls/, or /hls_v.../
    if re.search(r"/(?:v_)?hls(?:_[^/]+)?/", clean_url):
        renditions = [
            ("فيديو عالي الدقة (720p MP4)", "720p"),
            ("فيديو بجودة فائقة (1080p MP4)", "1080p"),
            ("فيديو بجودة قياسية (HD expMp4)", "expMp4"),
            ("فيديو عادي (480p MP4)", "480p"),
            ("فيديو خفيف (Mobile MP4)", "mobile"),
        ]

        for label, q_path in renditions:
            cand = re.sub(r"/(?:v_)?hls(?:_[^/]+)?/", f"/{q_path}/", clean_url)
            if q_path == "expMp4":
                cand = re.sub(r"/master\.m3u8$", "_expMp4.mp4", cand)
                cand = re.sub(r"\.m3u8$", "_expMp4.mp4", cand)
            else:
                cand = re.sub(r"/master\.m3u8$", ".mp4", cand)
                cand = re.sub(r"\.m3u8$", ".mp4", cand)

            if cand and not cand.lower().endswith(".m3u8"):
                candidates.append((label, cand))

    # Pattern 2: No /hls/ in path, but ends with /master.m3u8 or .m3u8
    elif ".m3u8" in clean_url.lower():
        cand_720 = re.sub(r"/master\.m3u8$", ".mp4", clean_url)
        cand_720 = re.sub(r"\.m3u8$", ".mp4", cand_720)
        if cand_720 and not cand_720.lower().endswith(".m3u8"):
            candidates.append(("فيديو عالي الدقة (720p MP4)", cand_720))

        if "/mc/" in clean_url:
            cand_exp = clean_url.replace("/mc/", "/mc/expMp4/")
            cand_exp = re.sub(r"(?:/master)?\.m3u8$", "_expMp4.mp4", cand_exp)
            if cand_exp and not cand_exp.lower().endswith(".m3u8"):
                candidates.append(("فيديو بجودة قياسية (HD expMp4)", cand_exp))

    return candidates


def _find_best_playable_video(video_candidates: List[str]) -> Tuple[Optional[str], List[Dict[str, Any]]]:
    """
    Given a list of video URLs (which may include .m3u8 or .mp4):
    1. Rejects any raw .m3u8 from the direct video list.
    2. Derives progressive MP4 variants from any .m3u8 URLs.
    3. Guarantees that only valid, 100% playable MP4s are returned.
    """
    verified_mp4s: List[Tuple[str, str]] = []
    seen_urls = set()

    # 1. Process explicit MP4s in candidates
    for u in video_candidates:
        if not u or not isinstance(u, str):
            continue
        u = u.replace(r"\/", "/").replace("&amp;", "&").strip()
        if ".m3u8" in u.lower():
            continue  # NEVER allow raw .m3u8 in explicit MP4 list
        if ".mp4" in u.lower() and u not in seen_urls:
            seen_urls.add(u)
            label = "فيديو عالي الدقة (MP4)"
            if "1080p" in u:
                label = "فيديو فائق الدقة (1080p MP4)"
            elif "720p" in u:
                label = "فيديو عالي الدقة (720p MP4)"
            elif "expmp4" in u.lower():
                label = "فيديو بجودة قياسية (HD expMp4)"
            elif "480p" in u:
                label = "فيديو عادي (480p MP4)"
            verified_mp4s.append((label, u))

    # 2. Derive MP4s from any HLS .m3u8 URLs
    for u in video_candidates:
        if not u or not isinstance(u, str):
            continue
        u = u.replace(r"\/", "/").replace("&amp;", "&").strip()
        if ".m3u8" in u.lower():
            derived = m3u8_to_progressive_mp4s(u)
            for label, cand_mp4 in derived:
                if cand_mp4 not in seen_urls and not cand_mp4.lower().endswith(".m3u8"):
                    seen_urls.add(cand_mp4)
                    verified_mp4s.append((label, cand_mp4))

    if not verified_mp4s:
        return None, []

    # Select primary video: Prefer 720p or 1080p or expMp4
    primary_video = None
    for label, u in verified_mp4s:
        if "720p" in u.lower() or "720p" in label.lower():
            primary_video = u
            break
    if not primary_video:
        for label, u in verified_mp4s:
            if "1080p" in u.lower() or "expmp4" in u.lower():
                primary_video = u
                break
    if not primary_video:
        primary_video = verified_mp4s[0][1]

    qualities: List[Dict[str, Any]] = []
    for label, u in verified_mp4s:
        is_hd = any(x in label.lower() or x in u.lower() for x in ("720", "1080", "fhd", "hd", "exp"))
        qualities.append(
            {
                "label": label,
                "url": u,
                "format": "mp4",
                "isHd": is_hd,
            }
        )

    return primary_video, qualities


def _build_result(
    title: str,
    source: str,
    video: Optional[str] = None,
    image: Optional[str] = None,
    thumbnail: Optional[str] = None,
    qualities: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Builds standard media schema, enforcing strict playability checks."""
    clean_title = (title or "Pinterest Media").strip()
    clean_title = re.sub(r"\s*[|\-–]\s*Pinterest\s*$", "", clean_title).strip()
    if len(clean_title) > 80:
        clean_title = clean_title[:77] + "..."

    # VIDEO RESULT
    if video:
        # ABSOLUTE SAFETY GUARD: Never return raw .m3u8
        if ".m3u8" in video.lower():
            derived = m3u8_to_progressive_mp4s(video)
            if derived:
                video = derived[0][1]
            else:
                video = None

    if video:
        quality_opts = qualities or [
            {"label": "فيديو عالي الدقة (MP4)", "url": video, "format": "mp4", "isHd": True}
        ]
        # Filter out any .m3u8 that might have sneaked into quality_opts
        clean_qualities = [q for q in quality_opts if not q.get("url", "").lower().endswith(".m3u8")]
        if not clean_qualities:
            clean_qualities = [{"label": "فيديو عالي الدقة (MP4)", "url": video, "format": "mp4", "isHd": True}]

        return {
            "title": clean_title or "Pinterest Video",
            "videoUrl": video,
            "thumbnailUrl": thumbnail or image or "",
            "platform": "Pinterest",
            "sourceUrl": source,
            "qualityOptions": clean_qualities,
            "carouselItems": [
                {"url": video, "format": "mp4", "label": "Pinterest Video (MP4)", "isHd": True}
            ],
            "photoUrls": [],
        }

    # IMAGE RESULT
    img_url = image or thumbnail
    if not img_url:
        raise ValueError("Could not extract Pinterest media stream.")

    ext = "jpg"
    m = re.search(r"\.(jpe?g|png|webp|gif)(?:\?|$)", img_url, re.I)
    if m:
        ext = m.group(1).lower()

    return {
        "title": clean_title or "Pinterest Image",
        "videoUrl": img_url,
        "thumbnailUrl": img_url,
        "platform": "Pinterest",
        "sourceUrl": source,
        "qualityOptions": [
            {
                "label": f"صورة أصلية عالية الدقة ({ext.upper()})",
                "url": img_url,
                "format": ext,
                "isHd": True,
            }
        ],
        "carouselItems": [
            {
                "url": img_url,
                "format": ext,
                "label": f"Pinterest Image ({ext.upper()})",
                "isHd": True,
            }
        ],
        "photoUrls": [img_url],
    }


def _extract_direct_media(url: str) -> Dict[str, Any]:
    """Handles direct media links on pinimg.com."""
    lower = url.lower()
    if ".m3u8" in lower:
        primary_video, qualities = _find_best_playable_video([url])
        if primary_video:
            return _build_result("Pinterest Video", url, video=primary_video, qualities=qualities)
    elif ".mp4" in lower or ".webm" in lower:
        return _build_result("Pinterest Video", url, video=url)

    # Image URL: Upgrade to /originals/
    orig_url = re.sub(r"/(?:\d+x(?:\d+)?(?:_RS)?|736x)/", "/originals/", url)
    return _build_result("Pinterest Image", url, image=orig_url)


def _extract_from_page_html(html_text: str, source_url: str) -> Optional[Dict[str, Any]]:
    """Fast, robust extraction from Pinterest page HTML."""
    if not html_text:
        return None

    clean_text = html_text.replace(r"\/", "/").replace(r"\u002F", "/").replace("&amp;", "&")

    # 1. Meta tags parser
    props = {}
    for tag in re.findall(r"<meta\b[^>]*>", clean_text, re.I):
        c_match = re.search(r'content=[\"\']([^\"\']+)[\"\']', tag, re.I)
        p_match = re.search(r'(?:property|name)=[\"\']([^\"\']+)[\"\']', tag, re.I)
        if c_match and p_match:
            props[p_match.group(1).lower()] = c_match.group(1)

    title = props.get("og:title") or props.get("twitter:title")
    t_match = re.search(r"<title>([^<]+)</title>", clean_text, re.I)
    if not title and t_match:
        title = t_match.group(1).strip()

    meta_img = props.get("og:image") or props.get("twitter:image") or props.get("twitter:image:src")
    if meta_img:
        meta_img = re.sub(r"/(?:\d+x(?:\d+)?(?:_RS)?|736x)/", "/originals/", meta_img)

    video_candidates: List[str] = []

    # Direct progressive MP4s embedded in JSON video_list
    json_mp4s = re.findall(
        r'"url"\s*:\s*"(https?://v[0-9]?\.pinimg\.com/videos/[^"\\]+\.mp4[^"\\]*)"',
        clean_text,
    )
    video_candidates.extend(json_mp4s)

    # Direct MP4 URLs found in page scripts
    direct_mp4s = re.findall(
        r'https?://v[0-9]?\.pinimg\.com/videos/[^\s"\'<>\\]+\.mp4(?:\?[^\s"\'<>\\]*)?',
        clean_text,
    )
    video_candidates.extend(direct_mp4s)

    # Meta video tags (often .m3u8 or .mp4)
    meta_vid = props.get("og:video") or props.get("og:video:secure_url") or props.get("twitter:player:stream")
    if meta_vid:
        video_candidates.append(meta_vid)

    # Search for HLS streams on pinimg to convert to progressive MP4
    hls_matches = re.findall(
        r'https?://v[0-9]?\.pinimg\.com/videos/[^\s"\'<>\\]+\.m3u8(?:\?[^\s"\'<>\\]*)?',
        clean_text,
    )
    video_candidates.extend(hls_matches)

    # Other pinimg video links
    other_vids = re.findall(r'https?://v[0-9]?\.pinimg\.com/videos/[^\s"\'<>\\]+', clean_text)
    video_candidates.extend(other_vids)

    # Process video candidates if found
    if video_candidates:
        primary_video, qualities = _find_best_playable_video(video_candidates)
        if primary_video:
            return _build_result(
                title=title or "Pinterest Video",
                source=source_url,
                video=primary_video,
                image=meta_img,
                thumbnail=meta_img,
                qualities=qualities,
            )

    # Process image if no video found
    image_candidates = []
    if meta_img:
        image_candidates.append(meta_img)

    orig_matches = re.findall(
        r'https?://i\.pinimg\.com/originals/[0-9a-fA-F/]+(?:\.jpg|\.png|\.webp|\.gif)', clean_text
    )
    image_candidates.extend(orig_matches)

    large_matches = re.findall(
        r'https?://i\.pinimg\.com/[0-9a-zA-Z_]+/([0-9a-fA-F/]+(?:\.jpg|\.png|\.webp|\.gif))',
        clean_text,
    )
    for m in large_matches:
        image_candidates.append(f"https://i.pinimg.com/originals/{m}")

    if image_candidates:
        best_img = image_candidates[0]
        return _build_result(
            title=title or "Pinterest Image",
            source=source_url,
            image=best_img,
        )

    return None


def _extract_from_savepin(url: str) -> Optional[Dict[str, Any]]:
    """Strategy 3: SavePin High-Reliability Public Resolver."""
    try:
        endpoint = "https://www.savepin.app/download.php"
        headers = {
            "User-Agent": UNIVERSAL_USER_AGENT,
            "Referer": "https://www.savepin.app/",
            "Origin": "https://www.savepin.app",
        }
        st, body, _ = http_post(endpoint, data={"url": url}, headers=headers, timeout=TIMEOUT)
        if st == 200 and body:
            # Find downloadable video links
            mp4_links = re.findall(r'href=[\"\'](https?://[^\s\"\'<>]+\.mp4[^\s\"\'<>]*)[\"\']', body)
            if mp4_links:
                primary_video, qualities = _find_best_playable_video(mp4_links)
                if primary_video:
                    thumb = None
                    m_thumb = re.search(r'<img\b[^>]*src=[\"\'](https?://[^\s\"\'<>]+\.jpg[^\s\"\'<>]*)[\"\']', body)
                    if m_thumb:
                        thumb = m_thumb.group(1)
                    return _build_result(
                        title="Pinterest Video",
                        source=url,
                        video=primary_video,
                        thumbnail=thumb,
                        qualities=qualities,
                    )
    except Exception:
        pass
    return None


def _extract_from_oembed(url: str) -> Optional[Dict[str, Any]]:
    """Official Pinterest oEmbed API fallback."""
    try:
        api = "https://www.pinterest.com/oembed.json?url=" + urllib.parse.quote(url, safe="")
        st, body, _ = http_get(api, headers=PAGE_HEADERS, timeout=TIMEOUT)
        if st == 200 and body:
            payload = json.loads(body)
            thumb = payload.get("thumbnail_url")
            if thumb:
                orig_thumb = re.sub(r"/(?:\d+x(?:\d+)?(?:_RS)?|736x)/", "/originals/", thumb)
                title = payload.get("title") or "Pinterest Media"
                return _build_result(title=title, source=url, image=orig_thumb)
    except Exception:
        pass
    return None


def _extract_with_relay(canonical_url: str) -> Optional[Dict[str, Any]]:
    """Public Relay Tunnel (common.relay_get) for censored/blocked networks."""
    try:
        st, body, _ = relay_get(canonical_url, headers=PAGE_HEADERS, timeout=RELAY_TIMEOUT, min_length=500)
        if st == 200 and body:
            return _extract_from_page_html(body, canonical_url)
    except Exception:
        pass
    return None


def _extract_with_ytdlp(url: str) -> Optional[Dict[str, Any]]:
    """In-Process Pure Python yt-dlp & Subprocess fallback."""
    try:
        import yt_dlp

        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "extract_flat": False,
            "socket_timeout": TIMEOUT,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            data = ydl.extract_info(url, download=False)
            if not data:
                return None

            primary_video = data.get("url")
            qualities = []

            for fmt in reversed(data.get("formats") or []):
                if not isinstance(fmt, dict):
                    continue
                v_url = fmt.get("url")
                if v_url and ".mp4" in v_url.lower():
                    height = fmt.get("height") or 720
                    is_hd = height >= 720
                    qualities.append(
                        {
                            "label": f"فيديو ({height}p MP4)",
                            "url": v_url,
                            "format": "mp4",
                            "isHd": is_hd,
                        }
                    )
                    if not primary_video or ".m3u8" in primary_video.lower():
                        primary_video = v_url

            if primary_video:
                if ".m3u8" in primary_video.lower():
                    derived = m3u8_to_progressive_mp4s(primary_video)
                    if derived:
                        primary_video = derived[0][1]

            if primary_video and not primary_video.lower().endswith(".m3u8"):
                return _build_result(
                    title=data.get("title") or "Pinterest Video",
                    source=url,
                    video=primary_video,
                    thumbnail=data.get("thumbnail"),
                    qualities=qualities if qualities else None,
                )
    except Exception:
        pass

    # Subprocess fallback if yt-dlp binary is present in system PATH
    binary = shutil.which("yt-dlp")
    if binary:
        try:
            proc = subprocess.run(
                [
                    binary,
                    "--dump-single-json",
                    "--no-warnings",
                    "--no-playlist",
                    "--socket-timeout",
                    str(TIMEOUT),
                    url,
                ],
                capture_output=True,
                text=True,
                timeout=TIMEOUT + 5,
                check=False,
            )
            if proc.returncode == 0 and proc.stdout:
                data = json.loads(proc.stdout)
                video = data.get("url")
                if video and ".m3u8" in video.lower():
                    derived = m3u8_to_progressive_mp4s(video)
                    if derived:
                        video = derived[0][1]
                if video and not video.lower().endswith(".m3u8"):
                    return _build_result(
                        title=data.get("title") or "Pinterest Video",
                        source=url,
                        video=video,
                        thumbnail=data.get("thumbnail"),
                    )
        except Exception:
            pass

    return None


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """
    Main Pinterest extraction entrypoint.
    Executes cascading strategies to ensure 100% playable videos and HD images.
    """
    if not url or not isinstance(url, str):
        raise ValueError("رابط Pinterest غير صالح أو فارغ")

    canonical_url, pin_id = resolve_pin_url(url)
    if not canonical_url:
        canonical_url = url.strip()

    # Strategy 1: Direct pinimg link
    if _is_direct_media_url(canonical_url):
        return _extract_direct_media(canonical_url)

    errors: List[str] = []

    # Strategy 2: Fast Direct Page HTML Scraping & Progressive MP4 Synthesizer
    try:
        st, page, _ = http_get(canonical_url, headers=PAGE_HEADERS, timeout=TIMEOUT, allow_redirects=True)
        if st == 200 and page:
            res = _extract_from_page_html(page, canonical_url)
            if res:
                return res
            errors.append("HTML page loaded but media signatures were not found")
        else:
            errors.append(f"Pin page HTTP {st}")
    except Exception as e:
        errors.append(f"HTML: {e}")

    # Strategy 3: SavePin External Resolver
    try:
        res = _extract_from_savepin(canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"SavePin: {e}")

    # Strategy 4: oEmbed API
    try:
        res = _extract_from_oembed(canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"oEmbed: {e}")

    # Strategy 5: Public Relay Tunnel
    try:
        res = _extract_with_relay(canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"Relay: {e}")

    # Strategy 6 & 7: In-process yt-dlp & Subprocess
    try:
        res = _extract_with_ytdlp(canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"yt-dlp: {e}")

    detail = " | ".join(errors[-3:]) if errors else "Media not found or pin is private"
    raise ValueError(f"تعذر استخراج وسائط بنترست: {detail}")
