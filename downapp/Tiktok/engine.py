#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
TikTok Dynamic Media Extraction Engine (downapp/Tiktok/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get, http_post, UNIVERSAL_USER_AGENT


def resolve_tiktok_url(url: str) -> str:
    if "vt.tiktok.com" in url or "vm.tiktok.com" in url:
        status, html, final_url = http_get(url, allow_redirects=True)
        return final_url or url
    return url


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    resolved_url = resolve_tiktok_url(url)

    # Strategy 1: TikWM Public REST API
    try:
        api_url = "https://www.tikwm.com/api/"
        status, text, _ = http_post(api_url, data={"url": resolved_url, "count": 12, "cursor": 0, "web": 1, "hd": 1})
        if status == 200:
            json_res = json.loads(text)
            data = json_res.get("data", {})
            if data:
                title = data.get("title", "TikTok Video").strip()[:60] or "TikTok Video"
                author = data.get("author", {}).get("unique_id", "")
                cover = data.get("cover") or data.get("origin_cover")
                music = data.get("music")

                images = data.get("images", [])
                if images:
                    carousel = []
                    for idx, img in enumerate(images):
                        carousel.append({
                            "url": img,
                            "format": "jpg",
                            "label": f"سلايد {idx + 1} (صورة JPG)",
                            "isHd": True
                        })
                    qualities = [
                        {
                            "label": f"تحميل جميع الصور ({len(images)} صورة JPG)",
                            "url": images[0],
                            "format": "album",
                            "isHd": True
                        }
                    ]
                    if music:
                        qualities.append({
                            "label": "تحميل الصوت الأصلي (MP3)",
                            "url": music,
                            "format": "mp3",
                            "isHd": False
                        })
                    return {
                        "title": title,
                        "videoUrl": images[0],
                        "thumbnailUrl": cover,
                        "platform": "TikTok",
                        "author": author,
                        "sourceUrl": resolved_url,
                        "audioUrl": music,
                        "qualityOptions": qualities,
                        "carouselItems": carousel,
                        "photoUrls": images
                    }
                else:
                    video_url = data.get("hdplay") or data.get("play") or data.get("wmplay")
                    if video_url:
                        if not video_url.startswith("http"):
                            video_url = "https://www.tikwm.com" + video_url
                        qualities = [
                            {
                                "label": "فيديو عالي الدقة بدون علامة مائية (HD MP4)",
                                "url": video_url,
                                "format": "mp4",
                                "isHd": True
                            }
                        ]
                        if music:
                            qualities.append({
                                "label": "تحميل الصوت فقط (MP3)",
                                "url": music,
                                "format": "mp3",
                                "isHd": False
                            })
                        return {
                            "title": title,
                            "videoUrl": video_url,
                            "thumbnailUrl": cover,
                            "platform": "TikTok",
                            "author": author,
                            "sourceUrl": resolved_url,
                            "audioUrl": music,
                            "qualityOptions": qualities,
                            "carouselItems": [
                                {
                                    "url": video_url,
                                    "format": "mp4",
                                    "label": "TikTok No Watermark (MP4)",
                                    "isHd": True
                                }
                            ],
                            "photoUrls": []
                        }
    except Exception:
        pass

    raise ValueError("Failed to extract TikTok media.")
