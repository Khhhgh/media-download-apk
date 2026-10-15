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
    """تنظيف وتوحيد صيغة الرابط وفك الروابط المختصرة والتخلص من معلمات التتبع الضخمة"""
    if not url:
        return ""

    url = url.strip()

    # 1. توحيد البروتوكول وتجاوز حساسية الأحرف (مثل Https:// أو HTTP://)
    url = re.sub(r"^https?://", lambda m: m.group(0).lower(), url, flags=re.IGNORECASE)
    if not url.startswith(("http://", "https://")):
        url = "https://" + url

    # 2. فحص الروابط المباشرة الكاملة وقص معلمات التتبع فورا (@user/video/ID أو @user/photo/ID)
    direct_match = re.search(
        r"(https?://(?:www\.|m\.)?tiktok\.com/@[^/?#]+/(?:video|photo)/\d+)",
        url,
        flags=re.IGNORECASE,
    )
    if direct_match:
        return direct_match.group(1)

    # 3. دعم صيغة الروابط القديمة والمحمولة (m.tiktok.com/v/ID)
    v_match = re.search(r"tiktok\.com/v/(\d+)", url, flags=re.IGNORECASE)
    if v_match:
        return f"https://www.tiktok.com/@tiktok/video/{v_match.group(1)}"

    # 4. فك الروابط المختصرة (vt.tiktok.com, vm.tiktok.com, /t/) وتتبع التحويل
    lower_url = url.lower()
    if any(s in lower_url for s in ["vt.tiktok.com", "vm.tiktok.com", "/t/"]):
        try:
            status, html, final_url = http_get(url, allow_redirects=True)
            if final_url:
                target = final_url.strip()
                target = re.sub(
                    r"^https?://",
                    lambda m: m.group(0).lower(),
                    target,
                    flags=re.IGNORECASE,
                )
                redirect_match = re.search(
                    r"(https?://(?:www\.|m\.)?tiktok\.com/@[^/?#]+/(?:video|photo)/\d+)",
                    target,
                    flags=re.IGNORECASE,
                )
                if redirect_match:
                    return redirect_match.group(1)
                return target.split("?")[0].split("#")[0].rstrip("/")
        except Exception:
            pass

    # 5. تنظيف نهائي لأي زوائد أو بارامترات متبقية
    return url.split("?")[0].split("#")[0].rstrip("/")


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    resolved_url = resolve_tiktok_url(url)

    # استخراج معرف الفيديو تحسبا لاستخدامه كخيار بديل في حال وجود محارف خاصة في اسم المستخدم
    video_id_match = re.search(r"/(?:video|photo|v)/(\d+)", resolved_url)
    video_id = video_id_match.group(1) if video_id_match else None

    # قائمة الروابط المستهدفة للاستعلام (الرابط المنظف أولا، ثم الرابط القياسي بالمعرف كبديل)
    target_urls = [resolved_url]
    if video_id:
        canonical_fallback = f"https://www.tiktok.com/@tiktok/video/{video_id}"
        if canonical_fallback not in target_urls:
            target_urls.append(canonical_fallback)

    # نطاقات خوادم TikWM لتفادي تعطل أي نطاق فرعي
    api_endpoints = [
        "https://www.tikwm.com/api/",
        "https://tikwm.com/api/",
    ]

    last_error_msg = "Unknown error"

    for target in target_urls:
        for api_url in api_endpoints:
            try:
                payload = {
                    "url": target,
                    "count": 12,
                    "cursor": 0,
                    "web": 1,
                    "hd": 1,
                }
                status, text, _ = http_post(api_url, data=payload)
                if status != 200 or not text:
                    continue

                json_res = json.loads(text)
                if json_res.get("code") != 0:
                    last_error_msg = json_res.get("msg", "TikWM returned error")
                    continue

                data = json_res.get("data", {})
                if not data:
                    continue

                title = (
                    data.get("title", "TikTok Video").strip()[:60]
                    or "TikTok Video"
                )
                author = (
                    data.get("author", {}).get("unique_id", "")
                    or data.get("author", {}).get("nickname", "")
                )
                cover = data.get("cover") or data.get("origin_cover")
                if cover and not cover.startswith("http"):
                    cover = "https://www.tikwm.com" + cover

                music = data.get("music") or data.get("play_url")
                if music and not music.startswith("http"):
                    music = "https://www.tikwm.com" + music

                images = data.get("images", [])

                # ألبومات الصور والسلايدات (Photo Slides)
                if images:
                    carousel = []
                    fixed_images = []
                    for idx, img in enumerate(images):
                        if img and not img.startswith("http"):
                            img = "https://www.tikwm.com" + img
                        fixed_images.append(img)
                        carousel.append(
                            {
                                "url": img,
                                "format": "jpg",
                                "label": f"سلايد {idx + 1} (صورة JPG)",
                                "isHd": True,
                            }
                        )

                    qualities = [
                        {
                            "label": f"تحميل جميع الصور ({len(fixed_images)} صورة JPG)",
                            "url": fixed_images[0],
                            "format": "album",
                            "isHd": True,
                        }
                    ]
                    if music:
                        qualities.append(
                            {
                                "label": "تحميل الصوت الأصلي (MP3)",
                                "url": music,
                                "format": "mp3",
                                "isHd": False,
                            }
                        )

                    return {
                        "title": title,
                        "videoUrl": fixed_images[0],
                        "thumbnailUrl": cover,
                        "platform": "TikTok",
                        "author": author,
                        "sourceUrl": target,
                        "audioUrl": music,
                        "qualityOptions": qualities,
                        "carouselItems": carousel,
                        "photoUrls": fixed_images,
                    }

                # مقاطع الفيديو العادية
                else:
                    hd_url = data.get("hdplay")
                    sd_url = data.get("play") or data.get("wmplay")
                    main_video = hd_url or sd_url

                    if not main_video:
                        continue

                    if hd_url and not hd_url.startswith("http"):
                        hd_url = "https://www.tikwm.com" + hd_url
                    if sd_url and not sd_url.startswith("http"):
                        sd_url = "https://www.tikwm.com" + sd_url
                    if main_video and not main_video.startswith("http"):
                        main_video = "https://www.tikwm.com" + main_video

                    qualities = []
                    if hd_url:
                        qualities.append(
                            {
                                "label": "فيديو عالي الدقة بدون علامة مائية (HD MP4)",
                                "url": hd_url,
                                "format": "mp4",
                                "isHd": True,
                            }
                        )
                    if sd_url and sd_url != hd_url:
                        qualities.append(
                            {
                                "label": "فيديو دقة سريعة بدون علامة مائية (SD MP4)",
                                "url": sd_url,
                                "format": "mp4",
                                "isHd": False,
                            }
                        )
                    if not qualities:
                        qualities.append(
                            {
                                "label": "فيديو بدون علامة مائية (MP4)",
                                "url": main_video,
                                "format": "mp4",
                                "isHd": True,
                            }
                        )

                    if music:
                        qualities.append(
                            {
                                "label": "تحميل الصوت فقط (MP3)",
                                "url": music,
                                "format": "mp3",
                                "isHd": False,
                            }
                        )

                    return {
                        "title": title,
                        "videoUrl": main_video,
                        "thumbnailUrl": cover,
                        "platform": "TikTok",
                        "author": author,
                        "sourceUrl": target,
                        "audioUrl": music,
                        "qualityOptions": qualities,
                        "carouselItems": [
                            {
                                "url": main_video,
                                "format": "mp4",
                                "label": "TikTok No Watermark (MP4)",
                                "isHd": True,
                            }
                        ],
                        "photoUrls": [],
                    }
            except Exception as e:
                last_error_msg = str(e)
                continue

    raise ValueError(f"Failed to extract TikTok media: {last_error_msg}")
