#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Snapchat Dynamic Media Extraction Engine (downapp/Snapchat/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional

# Add parent directory to path for downapp.common
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get, MOBILE_USER_AGENT


def resolve_snapchat_url(raw_url: str) -> str:
    """Follows 301/302 redirects to find the canonical Snapchat story/spotlight URL."""
    status, html, final_url = http_get(raw_url, headers={"User-Agent": MOBILE_USER_AGENT})
    if final_url and "snapchat.com" in final_url and "/t/" not in final_url:
        return final_url

    # Check meta refresh
    meta_match = re.search(r'<meta[^>]+http-equiv=["\']refresh["\'][^>]+content=["\']\d+;\s*url=([^"\']+)["\']', html, re.IGNORECASE)
    if meta_match:
        return meta_match.group(1).strip()

    return final_url or raw_url


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    canonical_url = resolve_snapchat_url(url)
    status, html, final_url = http_get(canonical_url, headers={"User-Agent": MOBILE_USER_AGENT})

    title = "Snapchat Spotlight"
    video_url = ""
    thumbnail_url = ""
    author = ""

    # 1. OpenGraph meta tags
    og_title = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)["\']', html)
    if og_title:
        title = og_title.group(1).replace("&amp;", "&").strip()

    og_image = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']', html)
    if og_image:
        thumbnail_url = og_image.group(1).replace("&amp;", "&").strip()

    og_video = re.search(r'<meta[^>]+property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)["\']', html)
    if og_video:
        video_url = og_video.group(1).replace("&amp;", "&").strip()

    # 2. Parse __NEXT_DATA__
    next_match = re.search(r'<script[^>]+id=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>', html, re.DOTALL)
    if next_match:
        try:
            data = json.loads(next_match.group(1))
            props = data.get("props", {}).get("pageProps", {})
            cur_snap = props.get("curSnap", {}) or props.get("spotlightStory", {})
            if not video_url:
                video_url = (
                    cur_snap.get("mediaUrl") or
                    cur_snap.get("videoUrl") or
                    cur_snap.get("downloadUrl") or
                    ""
                )
            if not thumbnail_url:
                thumbnail_url = cur_snap.get("thumbnailUrl") or cur_snap.get("posterUrl") or ""
            if not author:
                author = cur_snap.get("creatorUsername") or cur_snap.get("publisherName") or ""
        except Exception:
            pass

    # 3. CDN regex fallback
    if not video_url:
        cdn_match = re.search(r'(https://[^"\'<>\s]+?(?:sc-cdn\.net|bolt-gcdn\.sc-cdn\.net)[^"\'<>\s]+?\.mp4[^"\'<>\s]*)', html)
        if cdn_match:
            video_url = cdn_match.group(1).replace("&amp;", "&").strip()

    if not video_url:
        raise ValueError("Could not locate direct MP4 stream for Snapchat URL.")

    return {
        "title": title,
        "videoUrl": video_url,
        "thumbnailUrl": thumbnail_url,
        "platform": "Snapchat",
        "author": author,
        "sourceUrl": canonical_url,
        "qualityOptions": [
            {
                "label": "تحميل فيديو عالي الدقة (MP4)",
                "url": video_url,
                "format": "mp4",
                "isHd": True
            }
        ],
        "carouselItems": [
            {
                "url": video_url,
                "format": "mp4",
                "label": "Snapchat Video (MP4)",
                "isHd": True
            }
        ],
        "photoUrls": []
    }
