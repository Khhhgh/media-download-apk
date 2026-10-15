#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Facebook Dynamic Media Extraction Engine (downapp/Facebook/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    status, html, _ = http_get(url)

    hd_match = re.search(r'hd_src:"([^"]+)"', html) or re.search(r'"playable_url_quality_hd":"([^"]+)"', html)
    sd_match = re.search(r'sd_src:"([^"]+)"', html) or re.search(r'"playable_url":"([^"]+)"', html)

    best_url = (hd_match.group(1) if hd_match else (sd_match.group(1) if sd_match else "")).replace(r'\/', '/')
    if not best_url:
        og_video = re.search(r'<meta[^>]+property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)["\']', html)
        if og_video:
            best_url = og_video.group(1).replace("&amp;", "&")

    if not best_url:
        raise ValueError("Could not extract Facebook video stream.")

    og_thumb = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']', html)
    thumb = og_thumb.group(1) if og_thumb else None

    return {
        "title": "Facebook Video",
        "videoUrl": best_url,
        "thumbnailUrl": thumb,
        "platform": "Facebook",
        "sourceUrl": url,
        "qualityOptions": [
            {
                "label": "فيديو عالي الدقة (HD MP4)",
                "url": best_url,
                "format": "mp4",
                "isHd": True
            }
        ],
        "carouselItems": [
            {
                "url": best_url,
                "format": "mp4",
                "label": "Facebook Video (MP4)",
                "isHd": True
            }
        ],
        "photoUrls": []
    }
