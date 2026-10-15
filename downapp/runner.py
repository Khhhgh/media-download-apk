#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Dynamic Modular Runner (downapp/runner.py)
Unified CLI, Cloud Worker & Dynamic Dispatcher for all downapp platform engines.
"""

import importlib.util
import json
import os
import sys
from typing import Any, Dict

# Ensure downapp is in path
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

# Do not leave __pycache__ behind in the app package folder.
sys.dont_write_bytecode = True

from common import auto_install_requirements, cleanup_temp, http_get


def detect_platform(url: str) -> str:
    lower = url.lower()
    if "snapchat.com" in lower or "sc-cdn" in lower:
        return "Snapchat"
    elif "instagram.com" in lower or "instagr.am" in lower or "cdninstagram.com" in lower:
        return "Instagram"
    elif "tiktok.com" in lower or "douyin.com" in lower:
        return "Tiktok"
    elif "twitter.com" in lower or "x.com" in lower:
        return "Twitter"
    elif "facebook.com" in lower or "fb.watch" in lower or "fb.com" in lower:
        return "Facebook"
    elif "pinterest.com" in lower or "pin.it" in lower or "pinimg.com" in lower:
        return "Pinterest"
    elif "threads.net" in lower:
        return "Threads"
    elif "reddit.com" in lower or "redd.it" in lower:
        return "Reddit"
    return "Universal"


def extract_universal(url: str) -> Dict[str, Any]:
    """Universal OpenGraph and HTML5 fallback extractor."""
    status, html, final_url = http_get(url)
    import re
    og_video = re.search(r'<meta[^>]+property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)["\']', html)
    og_image = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']', html)
    og_title = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)["\']', html)
    title = og_title.group(1).replace("&amp;", "&").strip()[:60] if og_title else "Web Media"

    if og_video:
        video_url = og_video.group(1).replace("&amp;", "&").strip()
        thumb = og_image.group(1).replace("&amp;", "&").strip() if og_image else None
        return {
            "title": title,
            "videoUrl": video_url,
            "thumbnailUrl": thumb,
            "platform": "Web",
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
                    "label": "Video (MP4)",
                    "isHd": True
                }
            ],
            "photoUrls": []
        }
    elif og_image:
        img_url = og_image.group(1).replace("&amp;", "&").strip()
        return {
            "title": title,
            "videoUrl": img_url,
            "thumbnailUrl": img_url,
            "platform": "Web",
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
                    "label": "Photo (JPG)",
                    "isHd": True
                }
            ],
            "photoUrls": [img_url]
        }

    raise ValueError("Could not extract media from URL.")


def run(url: str) -> Dict[str, Any]:
    platform = detect_platform(url)
    engine_path = os.path.join(BASE_DIR, platform, "engine.py")

    try:
        if os.path.exists(engine_path):
            spec = importlib.util.spec_from_file_location(f"{platform}.engine", engine_path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            return module.extract(url)
        return extract_universal(url)
    finally:
        # Cleaning the temporary leftovers is part of finishing the job.
        cleanup_temp(BASE_DIR)


if __name__ == "__main__":
    auto_install_requirements()
    if len(sys.argv) < 2:
        print(json.dumps({"error": "No URL provided", "usage": "python3 downapp/runner.py <url>"}))
        sys.exit(1)

    url_arg = sys.argv[1].strip()
    try:
        result = run(url_arg)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as e:
        print(json.dumps({"error": str(e), "success": False}, ensure_ascii=False, indent=2))
        sys.exit(1)
