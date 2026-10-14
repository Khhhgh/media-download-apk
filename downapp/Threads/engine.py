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
from common import http_get

# User-Agent مخصص يطلب الصفحة كبوت معاينة لتجاوز جدار تسجيل الدخول وسحب الوسائط كاملة
PREVIEW_USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"


def clean_meta_url(raw_url: str) -> str:
    """تنظيف الروابط من التشفير والرموز الزائدة"""
    if not raw_url:
        return ""
    return raw_url.replace('&amp;', '&').replace('\\u0026', '&').replace('\\/', '/').strip()


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    # 1. إزالة معاملات التتبع من الرابط
    clean_target_url = url.split("?")[0].strip()

    # 2. جلب الصفحة بترويسة تضمن تجاوز حظر تسجيل الدخول
    status, html, final_url = http_get(clean_target_url, headers={
        "User-Agent": PREVIEW_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.5"
    })

    if not html or status >= 400:
        raise ValueError(f"HTTP Error {status}: فشل الاتصال برابط ثريدز")

    # 3. استخراج العنوان (Title)
    title_match = re.search(r'<meta[^>]+(?:property|name)=["\']og:title["\'][^>]+content=["\']([^"\']+)["\']', html)
    if not title_match:
        title_match = re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\']og:title["\']', html)
    title = clean_meta_url(title_match.group(1))[:60] if title_match else "Threads Media"

    # 4. فحص وسوم og:video بجميع احتمالات ترتيب الخصائص (قبل أو بعد content)
    video_url = None
    og_video = re.search(r'<meta[^>]+property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)["\']', html)
    if not og_video:
        og_video = re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:video(?::secure_url)?["\']', html)

    if og_video:
        video_url = clean_meta_url(og_video.group(1))

    # 5. خطة استخراج احتياطية: فحص الـ JSON المدمج وروابط CDN المباشرة إذا لم يظهر وسم og:video
    if not video_url:
        json_video_match = re.search(r'["\'](?:playback_url|video_url)["\']\s*:\s*["\'](https:[^"\']+)["\']', html)
        if json_video_match:
            video_url = clean_meta_url(json_video_match.group(1))

    if not video_url:
        cdn_matches = re.findall(r'https://[^\s"\'<>]+\.cdninstagram\.com/[^\s"\'<>]+\.mp4[^\s"\'<>]*', html)
        if cdn_matches:
            video_url = clean_meta_url(cdn_matches[0])

    # 6. استخراج صورة الغلاف (Thumbnail أو الصورة الرئيسية للمنشور)
    thumb_url = None
    og_image = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']', html)
    if not og_image:
        og_image = re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:image["\']', html)

    if og_image:
        thumb_url = clean_meta_url(og_image.group(1))

    # 7. بناء الاستجابة المتوافقة
    if video_url:
        return {
            "title": title,
            "videoUrl": video_url,
            "thumbnailUrl": thumb_url or video_url,
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
    elif thumb_url:
        return {
            "title": title,
            "videoUrl": thumb_url,
            "thumbnailUrl": thumb_url,
            "platform": "Threads",
            "sourceUrl": final_url,
            "qualityOptions": [
                {
                    "label": "صورة عالية الجودة (JPG)",
                    "url": thumb_url,
                    "format": "jpg",
                    "isHd": True
                }
            ],
            "carouselItems": [
                {
                    "url": thumb_url,
                    "format": "jpg",
                    "label": "Threads Photo (JPG)",
                    "isHd": True
                }
            ],
            "photoUrls": [thumb_url]
        }

    raise ValueError("Failed to extract Threads media: تعذر العثور على وسائط في هذا الرابط.")
