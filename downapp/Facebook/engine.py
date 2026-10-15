#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Facebook Dynamic Media Extraction Engine (downapp/Facebook/engine.py)

High-reliability cascading engine for Facebook videos, reels, stories, and posts.
Operates seamlessly on device (Chaquopy / Android) and server-side.

Strategies in order:
1. Facebook Embed / Plugin API (plugins/video.php) - official embed endpoint, never login-walled.
2. SnapSave High-Performance Resolver (snapsave.app) - unpacks multi-resolution streams (1080p, 720p, SD).
3. FSave Resolver Mirror (fsave.io) - identical fallback for SnapSave.
4. Mobile Web (mbasic.facebook.com) - lightweight video redirects for feature phones.
5. Direct HTML & GraphQL Scraper - multiple browser profiles, regex and JSON parsing.
6. Public Relay Tunnel (common.relay_get) - bypasses ISP censorship and firewall blocking without VPN.
7. Pure Python yt-dlp - in-memory execution when yt_dlp is installed.
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

TIMEOUT = 14
RELAY_TIMEOUT = 22

FB_DOMAINS = (
    "facebook.com",
    "fb.watch",
    "fb.com",
    "fb.me",
    "m.facebook.com",
    "mbasic.facebook.com",
    "web.facebook.com",
)


def _clean_url(val: Optional[str]) -> Optional[str]:
    """Clean and unescape escaped Facebook JSON URLs."""
    if not val or not isinstance(val, str):
        return None

    s = val.strip(" \"'\t\r\n\\")
    s = s.replace(r"\/", "/").replace(r"\u0026", "&").replace("&amp;", "&")
    s = s.replace(r"\u0025", "%").replace(r"\u003D", "=").replace(r"\u002F", "/")
    s = s.replace(r"\u003A", ":")

    try:
        if r"\u" in s or r"\/" in s:
            s = json.loads('"' + s.replace('"', r'\"') + '"')
    except Exception:
        pass

    s = html.unescape(s).replace(r"\/", "/")

    if s.startswith("//"):
        s = "https:" + s

    if s.startswith(("http://", "https://")):
        return s

    return None


def resolve_facebook_url(url: str) -> Tuple[str, Optional[str]]:
    """
    Cleans URL parameters and resolves redirects (e.g., fb.watch or share links).
    Returns (canonical_url, video_id).
    """
    if not url:
        return "", None

    url = url.strip()
    if not re.match(r"^https?://", url, re.I):
        url = "https://" + url

    # Follow redirects for short links or share links
    is_shortlink = any(
        x in url.lower()
        for x in ("fb.watch", "fb.me", "/share/r/", "/share/v/", "/share/p/", "/share/")
    )

    final_url = url
    if is_shortlink:
        try:
            loc = http_get_location(url, timeout=8)
            if loc and loc.startswith("http"):
                final_url = loc
            else:
                st, _, redirected = http_get(url, timeout=10, allow_redirects=True)
                if redirected and redirected.startswith("http"):
                    final_url = redirected
        except Exception:
            pass

    # Extract Video ID if present
    video_id = None
    patterns = [
        r"/videos/(?:[^/?#]+/)?(\d+)",
        r"/reel/(\d+)",
        r"[?&]v=(\d+)",
        r"/watch/?\?v=(\d+)",
        r"story_fbid=(\d+)",
        r"/posts/(\d+)",
        r"/watch/(\d+)",
    ]
    for p in patterns:
        m = re.search(p, final_url)
        if m:
            video_id = m.group(1)
            break

    # Strip garbage parameters while retaining essential video identifiers
    parsed = urllib.parse.urlparse(final_url)
    qs = urllib.parse.parse_qs(parsed.query)
    keep_keys = {"v", "id", "story_fbid"}
    filtered_qs = {k: v for k, v in qs.items() if k in keep_keys}

    clean_path = parsed.path.rstrip("/")
    if not clean_path:
        clean_path = "/"

    canonical = f"{parsed.scheme}://{parsed.netloc}{clean_path}"
    if filtered_qs:
        canonical += "?" + urllib.parse.urlencode(filtered_qs, doseq=True)

    return canonical, video_id


def _build_result(
    url: str,
    hd_url: Optional[str] = None,
    sd_url: Optional[str] = None,
    thumbnail: Optional[str] = None,
    title: Optional[str] = None,
    extra_qualities: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Builds standard media schema expected by Android app."""
    primary_video = hd_url or sd_url
    if not primary_video and extra_qualities:
        primary_video = extra_qualities[0].get("url")

    if not primary_video:
        raise ValueError("Could not extract Facebook video stream.")

    clean_title = (title or "Facebook Video").strip()
    if len(clean_title) > 80:
        clean_title = clean_title[:77] + "..."

    qualities = []
    seen_urls = set()

    if extra_qualities:
        for q in extra_qualities:
            u = q.get("url")
            if u and u not in seen_urls:
                qualities.append(q)
                seen_urls.add(u)

    if hd_url and hd_url not in seen_urls:
        qualities.insert(
            0,
            {
                "label": "فيديو عالي الدقة (HD MP4)",
                "url": hd_url,
                "format": "mp4",
                "isHd": True,
            },
        )
        seen_urls.add(hd_url)

    if sd_url and sd_url not in seen_urls:
        qualities.append(
            {
                "label": "فيديو عادي (SD MP4)",
                "url": sd_url,
                "format": "mp4",
                "isHd": False,
            }
        )
        seen_urls.add(sd_url)

    if not qualities:
        qualities.append(
            {
                "label": "فيديو Facebook (MP4)",
                "url": primary_video,
                "format": "mp4",
                "isHd": True,
            }
        )

    return {
        "title": clean_title,
        "videoUrl": primary_video,
        "thumbnailUrl": thumbnail or "",
        "platform": "Facebook",
        "sourceUrl": url,
        "qualityOptions": qualities,
        "carouselItems": [
            {
                "url": primary_video,
                "format": "mp4",
                "label": "Facebook Video (MP4)",
                "isHd": True,
            }
        ],
        "photoUrls": [],
    }


def _extract_from_plugin(url: str) -> Optional[Dict[str, Any]]:
    """
    Strategy 1: Facebook Video Plugin Embed (plugins/video.php).
    Never login-walled because it is Facebook's public embed iframe.
    """
    encoded_href = urllib.parse.quote(url, safe="")
    plugin_urls = [
        f"https://www.facebook.com/plugins/video.php?href={encoded_href}",
        f"https://m.facebook.com/plugins/video.php?href={encoded_href}",
    ]

    for p_url in plugin_urls:
        try:
            status, body, _ = http_get(
                p_url,
                headers={
                    "User-Agent": UNIVERSAL_USER_AGENT,
                    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
                    "Referer": "https://www.facebook.com/",
                },
                timeout=TIMEOUT,
            )
            if status != 200 or not body:
                continue

            hd_url = None
            sd_url = None

            # HD keys
            for key in (
                "hd_src_no_ratelimit",
                "hd_src",
                "playable_url_quality_hd",
                "browser_native_hd_url",
            ):
                m = re.search(r'"?' + key + r'"?\s*:\s*"([^"]+)"', body)
                if m:
                    cand = _clean_url(m.group(1))
                    if cand and "fbcdn.net" in cand:
                        hd_url = cand
                        break

            # SD keys
            for key in (
                "sd_src_no_ratelimit",
                "sd_src",
                "playable_url",
                "browser_native_sd_url",
            ):
                m = re.search(r'"?' + key + r'"?\s*:\s*"([^"]+)"', body)
                if m:
                    cand = _clean_url(m.group(1))
                    if cand and "fbcdn.net" in cand:
                        sd_url = cand
                        break

            # Check direct video tag inside embed
            if not hd_url and not sd_url:
                v_match = re.search(r'<video\b[^>]*src="([^"]+)"', body, re.I)
                if v_match:
                    cand = _clean_url(v_match.group(1))
                    if cand:
                        sd_url = cand

            if hd_url or sd_url:
                # Thumbnail extraction
                thumb = None
                for key in ("video_image", "thumbnailUrl", "poster", "cover_image"):
                    m = re.search(r'"?' + key + r'"?\s*:\s*"([^"]+)"', body)
                    if m:
                        cand = _clean_url(m.group(1))
                        if cand:
                            thumb = cand
                            break

                # Title extraction
                title = "Facebook Video"
                title_match = re.search(r"<title>([^<]+)</title>", body, re.I)
                if title_match:
                    clean_t = title_match.group(1).replace("Facebook", "").strip(" |-\t\n\r")
                    if clean_t:
                        title = clean_t

                return _build_result(
                    url=url,
                    hd_url=hd_url,
                    sd_url=sd_url,
                    thumbnail=thumb,
                    title=title,
                )
        except Exception:
            continue

    return None


def _decode_snapsave_payload(body: str) -> Optional[str]:
    """Decodes SnapSave / FSave obfuscated JavaScript unpacker."""
    m = re.search(
        r'\(\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)',
        body,
    )
    if not m:
        return None

    try:
        h = m.group(1)
        u = int(m.group(2))
        n = m.group(3)
        t = int(m.group(4))
        e = int(m.group(5))
        r = int(m.group(6))

        charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        base_chars = charset[:e]
        res = []
        i = 0
        delimiter = n[e]

        while i < len(h):
            s = ""
            while i < len(h) and h[i] != delimiter:
                s += h[i]
                i += 1
            i += 1
            if not s:
                continue

            for j, char in enumerate(n):
                s = s.replace(char, str(j))

            val = 0
            for char in s:
                val = val * e + base_chars.index(char)

            res.append(chr(val - t))

        raw_str = "".join(res)
        return raw_str.encode("latin1", errors="replace").decode("utf-8", errors="replace")
    except Exception:
        return None


def _extract_from_snapsave_endpoint(endpoint: str, url: str) -> Optional[Dict[str, Any]]:
    """Queries SnapSave or FSave resolver."""
    try:
        host = urllib.parse.urlparse(endpoint).netloc
        status, body, _ = http_post(
            endpoint,
            data={"url": url},
            headers={
                "User-Agent": UNIVERSAL_USER_AGENT,
                "Referer": f"https://{host}/",
                "Origin": f"https://{host}",
                "Accept": "*/*",
            },
            timeout=TIMEOUT,
        )

        if status != 200 or not body:
            return None

        html_text = _decode_snapsave_payload(body) or body
        clean_html = html_text.replace(r"\"", '"').replace(r"\/", "/")

        # Parse links and qualities
        qualities: List[Dict[str, Any]] = []
        primary_hd = None
        primary_sd = None

        for match in re.finditer(
            r"<tr\b[^>]*>.*?<td\b[^>]*class=[\"']video-quality[\"'][^>]*>(.*?)</td>.*?<a\b[^>]*href=[\"'](https?://[^\"'\\]+)[\"'][^>]*>(.*?)</a>",
            clean_html,
            re.DOTALL | re.I,
        ):
            quality_label = re.sub(r"<[^>]+>", "", match.group(1)).strip()
            download_url = _clean_url(match.group(2))
            if not download_url:
                continue

            is_hd = any(x in quality_label.lower() for x in ("hd", "1080", "720", "2k", "4k"))
            if is_hd and not primary_hd:
                primary_hd = download_url
            elif not is_hd and not primary_sd:
                primary_sd = download_url

            qualities.append(
                {
                    "label": f"فيديو {quality_label} (MP4)",
                    "url": download_url,
                    "format": "mp4",
                    "isHd": is_hd,
                }
            )

        # Fallback regex for download buttons if table structure differed
        if not qualities:
            for match in re.finditer(
                r"<a\b[^>]*href=[\"'](https?://[^\"'\\]+)[\"'][^>]*>(.*?)</a>",
                clean_html,
                re.DOTALL | re.I,
            ):
                download_url = _clean_url(match.group(1))
                btn_text = re.sub(r"<[^>]+>", "", match.group(2)).strip()
                if not download_url:
                    continue

                if "download" in btn_text.lower() or "button" in match.group(0).lower():
                    is_hd = "hd" in btn_text.lower()
                    if is_hd and not primary_hd:
                        primary_hd = download_url
                    elif not primary_sd:
                        primary_sd = download_url

                    qualities.append(
                        {
                            "label": f"تحميل ({btn_text or 'MP4'})",
                            "url": download_url,
                            "format": "mp4",
                            "isHd": is_hd,
                        }
                    )

        if not qualities and not primary_hd and not primary_sd:
            return None

        # Thumbnail
        thumb = None
        thumb_match = re.search(
            r"<img\b[^>]*src=[\"'](https?://[^\"'\\]+)[\"']", clean_html, re.I
        )
        if thumb_match:
            thumb = _clean_url(thumb_match.group(1))

        # Title
        title = "Facebook Video"
        title_match = re.search(r"<strong>([^<]+)</strong>", clean_html, re.I)
        if title_match:
            cand = title_match.group(1).strip()
            if cand:
                title = cand

        return _build_result(
            url=url,
            hd_url=primary_hd,
            sd_url=primary_sd,
            thumbnail=thumb,
            title=title,
            extra_qualities=qualities,
        )
    except Exception:
        return None


def _extract_from_mbasic(video_id: Optional[str], canonical_url: str) -> Optional[Dict[str, Any]]:
    """Strategy 4: Lightweight Mobile Web (mbasic.facebook.com)."""
    test_urls = []
    if video_id:
        test_urls.append(f"https://mbasic.facebook.com/watch/?v={video_id}")
        test_urls.append(f"https://mbasic.facebook.com/video.php?id={video_id}")
    test_urls.append(canonical_url.replace("www.facebook.com", "mbasic.facebook.com"))

    for m_url in test_urls:
        try:
            status, body, _ = http_get(
                m_url,
                headers={"User-Agent": MOBILE_USER_AGENT, "Accept-Language": "en-US,en;q=0.9"},
                timeout=TIMEOUT,
            )
            if status != 200 or not body:
                continue

            # Look for /video_redirect/?src=...
            match = re.search(r'/video_redirect/\?src=([^"\'&>]+)', body)
            if match:
                decoded_url = urllib.parse.unquote(match.group(1))
                cand = _clean_url(decoded_url)
                if cand:
                    thumb = None
                    t_match = re.search(
                        r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']',
                        body,
                        re.I,
                    )
                    if t_match:
                        thumb = _clean_url(t_match.group(1))

                    return _build_result(
                        url=canonical_url,
                        sd_url=cand,
                        thumbnail=thumb,
                        title="Facebook Video",
                    )
        except Exception:
            continue

    return None


def _extract_from_html_scraping(html_text: str, source_url: str) -> Optional[Dict[str, Any]]:
    """Strategy 5: Deep JSON & regex search in direct page HTML."""
    if not html_text:
        return None

    hd_url = None
    sd_url = None

    patterns_hd = [
        r'"playable_url_quality_hd"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"browser_native_hd_url"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"hd_src"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'hd_src\s*:\s*"((?:\\.|[^"\\])*)"',
    ]

    patterns_sd = [
        r'"playable_url"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"browser_native_sd_url"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'"sd_src"\s*:\s*"((?:\\.|[^"\\])*)"',
        r'sd_src\s*:\s*"((?:\\.|[^"\\])*)"',
    ]

    for p in patterns_hd:
        m = re.search(p, html_text, re.I)
        if m:
            cand = _clean_url(m.group(1))
            if cand and "fbcdn.net" in cand:
                hd_url = cand
                break

    for p in patterns_sd:
        m = re.search(p, html_text, re.I)
        if m:
            cand = _clean_url(m.group(1))
            if cand and "fbcdn.net" in cand:
                sd_url = cand
                break

    # OpenGraph meta tags
    if not hd_url and not sd_url:
        for tag in re.findall(r"<meta\b[^>]*>", html_text, re.I | re.S):
            prop = re.search(r"""(?:property|name)\s*=\s*["']([^"']+)["']""", tag, re.I)
            content = re.search(r"""content\s*=\s*["']([^"']+)["']""", tag, re.I)
            if prop and content:
                key = prop.group(1).lower()
                if key in ("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream"):
                    cand = _clean_url(content.group(1))
                    if cand:
                        sd_url = cand
                        break

    if hd_url or sd_url:
        thumb = None
        for tag in re.findall(r"<meta\b[^>]*>", html_text, re.I | re.S):
            prop = re.search(r"""(?:property|name)\s*=\s*["']([^"']+)["']""", tag, re.I)
            content = re.search(r"""content\s*=\s*["']([^"']+)["']""", tag, re.I)
            if prop and content and prop.group(1).lower() in ("og:image", "twitter:image"):
                thumb = _clean_url(content.group(1))
                break

        title = "Facebook Video"
        t_match = re.search(r"<title>([^<]+)</title>", html_text, re.I)
        if t_match:
            clean_t = t_match.group(1).replace("Facebook", "").strip(" |-\t\n\r")
            if clean_t:
                title = clean_t

        return _build_result(
            url=source_url,
            hd_url=hd_url,
            sd_url=sd_url,
            thumbnail=thumb,
            title=title,
        )

    return None


def _extract_with_relay(canonical_url: str) -> Optional[Dict[str, Any]]:
    """Strategy 6: Relay Fetch (common.relay_get) when ISP blocks Facebook."""
    encoded_href = urllib.parse.quote(canonical_url, safe="")
    plugin_target = f"https://www.facebook.com/plugins/video.php?href={encoded_href}"

    status, body, _ = relay_get(plugin_target, timeout=RELAY_TIMEOUT, min_length=500)
    if status == 200 and body:
        result = _extract_from_html_scraping(body, canonical_url)
        if result:
            return result

    # Also try direct page through relay
    status, body, _ = relay_get(canonical_url, timeout=RELAY_TIMEOUT, min_length=500)
    if status == 200 and body:
        return _extract_from_html_scraping(body, canonical_url)

    return None


def _extract_with_ytdlp_in_process(url: str) -> Optional[Dict[str, Any]]:
    """Strategy 7: Pure Python in-process yt-dlp execution."""
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

            primary_video = _clean_url(data.get("url"))
            qualities = []

            for fmt in reversed(data.get("formats") or []):
                if not isinstance(fmt, dict):
                    continue
                if fmt.get("vcodec") == "none":
                    continue
                v_url = _clean_url(fmt.get("url"))
                if v_url:
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
                    if not primary_video:
                        primary_video = v_url

            if primary_video:
                return _build_result(
                    url=url,
                    hd_url=primary_video,
                    thumbnail=_clean_url(data.get("thumbnail")),
                    title=data.get("title") or "Facebook Video",
                    extra_qualities=qualities if qualities else None,
                )
    except Exception:
        pass

    # Optional binary fallback if standalone binary exists in PATH
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
                video = _clean_url(data.get("url"))
                if video:
                    return _build_result(
                        url=url,
                        hd_url=video,
                        thumbnail=_clean_url(data.get("thumbnail")),
                        title=data.get("title") or "Facebook Video",
                    )
        except Exception:
            pass

    return None


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """
    Main extraction interface.
    Cascades through 7 robust extraction methods to guarantee success.
    """
    if not url or not isinstance(url, str):
        raise ValueError("رابط Facebook غير صالح أو فارغ")

    canonical_url, video_id = resolve_facebook_url(url)
    if not canonical_url:
        canonical_url = url.strip()

    errors: List[str] = []

    # 1. Official Video Embed Plugin (bypasses login walls)
    try:
        res = _extract_from_plugin(canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"Plugin: {e}")

    # 2. SnapSave High-Speed Resolver
    try:
        res = _extract_from_snapsave_endpoint("https://snapsave.app/action.php", canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"SnapSave: {e}")

    # 3. FSave Resolver Mirror
    try:
        res = _extract_from_snapsave_endpoint("https://fsave.io/action.php", canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"FSave: {e}")

    # 4. Mobile Web (mbasic)
    try:
        res = _extract_from_mbasic(video_id, canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"MobileBasic: {e}")

    # 5. Direct Page HTML Scraping
    try:
        status, page, _ = http_get(
            canonical_url,
            headers={
                "User-Agent": UNIVERSAL_USER_AGENT,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.9",
            },
            timeout=TIMEOUT,
        )
        if status == 200 and page:
            res = _extract_from_html_scraping(page, canonical_url)
            if res:
                return res
            errors.append("HTML page loaded but video stream was blocked or private")
        else:
            errors.append(f"Direct page HTTP {status}")
    except Exception as e:
        errors.append(f"HTML: {e}")

    # 6. Public Relay Tunnel (Anti-censorship / ISP unblocking)
    try:
        res = _extract_with_relay(canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"Relay: {e}")

    # 7. In-process yt-dlp
    try:
        res = _extract_with_ytdlp_in_process(canonical_url)
        if res:
            return res
    except Exception as e:
        errors.append(f"yt-dlp: {e}")

    # If all 7 methods fail, raise clear Arabic/English explanatory error
    detail = " | ".join(errors[-3:]) if errors else "Video is private or unavailable"
    raise ValueError(f"تعذر استخراج فيديو فيسبوك: {detail}")
