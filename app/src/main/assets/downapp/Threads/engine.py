#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Threads Dynamic Media Extraction Engine (downapp/Threads/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get, MOBILE_USER_AGENT


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    status, html, final_url = http_get(url, headers={"User-Agent": MOBILE_USER_AGENT})

    og_video = re.search(r'<meta[^>]+property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)["\']', html)
    og_image = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']', html)
    og_title = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)["\']', html)

    title = og_title.group(1).replace('&amp;', '&').strip()[:60] if og_title else "Threads Media"

    if og_video:
        video_url = og_video.group(1).replace('&amp;', '&').strip()
        thumb = og_image.group(1).replace('&amp;', '&').strip() if og_image else None
        return {
            "title": title,
            "videoUrl": video_url,
            "thumbnailUrl": thumb,
            "platform": "Threads",
            "sourceUrl": final_url,
            "qualityOptions": [
                {
                    "label": "فيديو عالي الدقة (MP4)",
                    "url": video_url,
                    "format": "mp4",
                    "isHd": True
                }
            ],
            "carouselItems": [
                {
                    "url": video_url,
                    "format": "mp4",
                    "label": "Threads Video (MP4)",
                    "isHd": True
                }
            ],
            "photoUrls": []
        }
    elif og_image:
        img_url = og_image.group(1).replace('&amp;', '&').strip()
        return {
            "title": title,
            "videoUrl": img_url,
            "thumbnailUrl": img_url,
            "platform": "Threads",
            "sourceUrl": final_url,
            "qualityOptions": [
                {
                    "label": "صورة عالية الجودة (JPG)",
                    "url": img_url,
                    "format": "jpg",
                    "isHd": True
                }
            ],
            "carouselItems": [
                {
                    "url": img_url,
                    "format": "jpg",
                    "label": "Threads Photo (JPG)",
                    "isHd": True
                }
            ],
            "photoUrls": [img_url]
        }

    raise ValueError("Failed to extract Threads media.")
