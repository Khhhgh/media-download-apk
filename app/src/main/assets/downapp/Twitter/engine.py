#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Twitter / X Dynamic Media Extraction Engine (downapp/Twitter/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    tweet_id_match = re.search(r'status/(\d+)', url)
    if not tweet_id_match:
        raise ValueError("Invalid Twitter URL. Could not find tweet status ID.")

    api_url = f"https://twitsave.com/info?url={url}"
    status, html, _ = http_get(api_url)
    if status == 200:
        dl_matches = re.findall(r'<a[^>]+href="([^"]+)"[^>]*>Download (?:UHD|HD|SD) Video</a>', html)
        if dl_matches:
            best_video = dl_matches[0]
            thumb_match = re.search(r'<div class="origin-top-left[^"]*"[^>]*>\s*<img[^>]+src="([^"]+)"', html)
            thumb = thumb_match.group(1) if thumb_match else None
            title_match = re.search(r'<p class="m-2 text-white[^"]*">([^<]+)</p>', html)
            title = title_match.group(1).strip()[:60] if title_match else "Twitter Video"

            return {
                "title": title,
                "videoUrl": best_video,
                "thumbnailUrl": thumb,
                "platform": "Twitter",
                "sourceUrl": url,
                "qualityOptions": [
                    {
                        "label": "فيديو عالي الدقة (HD MP4)",
                        "url": best_video,
                        "format": "mp4",
                        "isHd": True
                    }
                ],
                "carouselItems": [
                    {
                        "url": best_video,
                        "format": "mp4",
                        "label": "Twitter Video (MP4)",
                        "isHd": True
                    }
                ],
                "photoUrls": []
            }

    raise ValueError("Failed to extract Twitter/X video.")
