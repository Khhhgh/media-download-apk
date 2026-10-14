#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Pinterest Dynamic Media Extraction Engine (downapp/Pinterest/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    status, html, final_url = http_get(url, allow_redirects=True)

    video_match = re.search(r'https?://[^"\'<>\s]+?(?:pinimg\.com|v\.pinimg\.com)[^"\'<>\s]+?\.mp4[^"\'<>\s]*', html)
    img_match = re.search(r'https?://[^"\'<>\s]+?pinimg\.com/originals/[^"\'<>\s]+', html)

    if video_match:
        video_url = video_match.group(0).replace(r'\/', '/').replace('&amp;', '&')
        return {
            "title": "Pinterest Video",
            "videoUrl": video_url,
            "thumbnailUrl": img_match.group(0) if img_match else None,
            "platform": "Pinterest",
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
                    "label": "Pinterest Video (MP4)",
                    "isHd": True
                }
            ],
            "photoUrls": []
        }
    elif img_match:
        img_url = img_match.group(0).replace(r'\/', '/').replace('&amp;', '&')
        return {
            "title": "Pinterest Image",
            "videoUrl": img_url,
            "thumbnailUrl": img_url,
            "platform": "Pinterest",
            "sourceUrl": final_url,
            "qualityOptions": [
                {
                    "label": "صورة أصلية عالية الدقة (JPG)",
                    "url": img_url,
                    "format": "jpg",
                    "isHd": True
                }
            ],
            "carouselItems": [
                {
                    "url": img_url,
                    "format": "jpg",
                    "label": "Pinterest Image (JPG)",
                    "isHd": True
                }
            ],
            "photoUrls": [img_url]
        }

    raise ValueError("Failed to extract Pinterest media.")
