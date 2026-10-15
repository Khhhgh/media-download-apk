#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Reddit Dynamic Media Extraction Engine (downapp/Reddit/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    clean_url = url.split("?")[0].rstrip("/") + ".json"
    status, json_text, _ = http_get(clean_url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})

    if status == 200:
        try:
            data = json.loads(json_text)
            post_data = data[0]["data"]["children"][0]["data"]
            title = post_data.get("title", "Reddit Media")[:60]
            thumbnail = post_data.get("thumbnail")

            # Check video
            media = post_data.get("media") or post_data.get("secure_media")
            if media and "reddit_video" in media:
                rv = media["reddit_video"]
                fallback_url = rv.get("fallback_url")
                if fallback_url:
                    video_url = fallback_url.split("?")[0]
                    return {
                        "title": title,
                        "videoUrl": video_url,
                        "thumbnailUrl": thumbnail if thumbnail and thumbnail.startswith("http") else None,
                        "platform": "Reddit",
                        "sourceUrl": url,
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
                                "label": "Reddit Video (MP4)",
                                "isHd": True
                            }
                        ],
                        "photoUrls": []
                    }

            # Check single image
            url_overridden = post_data.get("url_overridden_by_dest", "")
            if url_overridden.endswith((".jpg", ".jpeg", ".png", ".webp")):
                return {
                    "title": title,
                    "videoUrl": url_overridden,
                    "thumbnailUrl": url_overridden,
                    "platform": "Reddit",
                    "sourceUrl": url,
                    "qualityOptions": [
                        {
                            "label": "صورة عالية الجودة (JPG)",
                            "url": url_overridden,
                            "format": "jpg",
                            "isHd": True
                        }
                    ],
                    "carouselItems": [
                        {
                            "url": url_overridden,
                            "format": "jpg",
                            "label": "Reddit Photo (JPG)",
                            "isHd": True
                        }
                    ],
                    "photoUrls": [url_overridden]
                }
        except Exception:
            pass

    raise ValueError("Failed to extract Reddit media.")
