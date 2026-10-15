#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Instagram Dynamic Media Extraction Engine (downapp/Instagram/engine.py)
"""

import json
import os
import re
import sys
from typing import Any, Dict, List, Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common import http_get, MOBILE_USER_AGENT


def clean_instagram_url(url: str) -> str:
    url = re.sub(r'[?&](psln|cplk|igsh|igshid|utm_[^&=]+|fbclid|locale|src)=[^&#]*', '', url, flags=re.IGNORECASE)
    url = url.replace('?&', '?').rstrip('?&/')
    return url


def extract_shortcode(url: str) -> Optional[str]:
    match = re.search(r'/(?:share/reel|share/p|share/tv|p|reel|reels|share|tv)/([A-Za-z0-9_-]+)', url, re.IGNORECASE)
    if match:
        return match.group(1)
    direct = re.search(r'^[A-Za-z0-9_-]{9,15}$', url.strip())
    if direct:
        return direct.group(0)
    return None


def clean_escaped(raw: str) -> str:
    s = raw.strip(' "\'\\')
    s = s.replace(r'\/', '/').replace(r'\u0026', '&').replace('&amp;', '&')
    return s


def extract(url: str, config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    cleaned_url = clean_instagram_url(url)
    shortcode = extract_shortcode(cleaned_url)
    if not shortcode:
        raise ValueError("Invalid Instagram URL. Could not extract post shortcode.")

    is_reel = "/reel" in cleaned_url.lower() or "/tv" in cleaned_url.lower()

    embed_urls = [
        f"https://www.instagram.com/reel/{shortcode}/embed/captioned/" if is_reel else f"https://www.instagram.com/p/{shortcode}/embed/captioned/",
        f"https://www.instagram.com/reel/{shortcode}/embed/" if is_reel else f"https://www.instagram.com/p/{shortcode}/embed/"
    ]

    for embed_url in embed_urls:
        status, html, final_url = http_get(embed_url, headers={"User-Agent": MOBILE_USER_AGENT})
        if status != 200:
            continue

        # Try parsing contextJSON
        context_match = re.search(r'"contextJSON":\s*"({.*?})"', html)
        if context_match:
            try:
                unescaped = context_match.group(1).encode().decode('unicode-escape')
                ctx_data = json.loads(unescaped)
                media = ctx_data.get("gql_data", {}).get("shortcode_media") or ctx_data.get("shortcode_media")
                if media:
                    return parse_instagram_node(media, cleaned_url, is_reel)
            except Exception:
                pass

        # Search video regexes
        video_matches = re.findall(r'(?:video_url|videoUrl)\\*":\\*"([^\\]+?)\\*"', html)
        if not video_matches:
            video_matches = re.findall(r'https?://[^"\'<>\s]+?(?:cdninstagram\.com|fbcdn\.net)[^"\'<>\s]+?\.mp4[^"\'<>\s]*', html)

        image_matches = re.findall(r'(?:display_url|displayUrl)\\*":\\*"([^\\]+?)\\*"', html)

        video_urls = [clean_escaped(v) for v in video_matches if v]
        image_urls = [clean_escaped(img) for img in image_matches if img]

        if video_urls:
            primary_video = video_urls[0]
            thumb = image_urls[0] if image_urls else None
            return {
                "title": "Instagram Reel" if is_reel else "Instagram Video",
                "videoUrl": primary_video,
                "thumbnailUrl": thumb,
                "platform": "Instagram",
                "sourceUrl": cleaned_url,
                "qualityOptions": [
                    {
                        "label": "تحميل ريلز فيديو عالي الدقة (MP4)" if is_reel else "تحميل فيديو عالي الدقة (MP4)",
                        "url": primary_video,
                        "format": "mp4",
                        "isHd": True
                    }
                ],
                "carouselItems": [
                    {
                        "url": primary_video,
                        "format": "mp4",
                        "label": "Instagram Reel (MP4)",
                        "isHd": True
                    }
                ],
                "photoUrls": []
            }
        elif image_urls and not is_reel:
            primary_image = image_urls[0]
            return {
                "title": "Instagram Photo",
                "videoUrl": primary_image,
                "thumbnailUrl": primary_image,
                "platform": "Instagram",
                "sourceUrl": cleaned_url,
                "qualityOptions": [
                    {
                        "label": "تحميل صورة عالية الجودة (JPG)",
                        "url": primary_image,
                        "format": "jpg",
                        "isHd": True
                    }
                ],
                "carouselItems": [
                    {
                        "url": primary_image,
                        "format": "jpg",
                        "label": "Instagram Photo (JPG)",
                        "isHd": True
                    }
                ],
                "photoUrls": [primary_image]
            }

    raise ValueError("Unable to extract Instagram media stream. Verify post is public.")


def parse_instagram_node(node: Dict[str, Any], source_url: str, is_reel: bool) -> Dict[str, Any]:
    caption = node.get("caption", {}).get("text", "") if isinstance(node.get("caption"), dict) else node.get("caption", "")
    title = caption.split('\n')[0][:60] if caption else ("Instagram Reel" if is_reel else "Instagram Media")

    carousel_items = []
    video_urls = []
    photo_urls = []

    edges = node.get("edge_sidecar_to_children", {}).get("edges", [])
    carousel_media = node.get("carousel_media", [])

    if edges:
        for idx, edge in enumerate(edges):
            child = edge.get("node", {})
            is_vid = child.get("is_video", False)
            if is_vid:
                v_url = child.get("video_url") or (child.get("video_versions", [{}])[0].get("url") if child.get("video_versions") else "")
                if v_url:
                    video_urls.append(v_url)
                    carousel_items.append({
                        "url": v_url,
                        "format": "mp4",
                        "label": f"سلايد {idx + 1} (فيديو MP4)",
                        "isHd": True
                    })
            else:
                img_url = child.get("display_url") or child.get("image_url")
                if img_url:
                    photo_urls.append(img_url)
                    carousel_items.append({
                        "url": img_url,
                        "format": "jpg",
                        "label": f"سلايد {idx + 1} (صورة JPG)",
                        "isHd": True
                    })
    elif carousel_media:
        for idx, child in enumerate(carousel_media):
            is_vid = child.get("media_type") == 2 or child.get("is_video", False)
            if is_vid:
                v_url = child.get("video_url") or (child.get("video_versions", [{}])[0].get("url") if child.get("video_versions") else "")
                if v_url:
                    video_urls.append(v_url)
                    carousel_items.append({
                        "url": v_url,
                        "format": "mp4",
                        "label": f"سلايد {idx + 1} (فيديو MP4)",
                        "isHd": True
                    })
            else:
                candidates = child.get("image_versions2", {}).get("candidates", [])
                img_url = candidates[0].get("url") if candidates else child.get("display_url")
                if img_url:
                    photo_urls.append(img_url)
                    carousel_items.append({
                        "url": img_url,
                        "format": "jpg",
                        "label": f"سلايد {idx + 1} (صورة JPG)",
                        "isHd": True
                    })
    else:
        is_vid = node.get("is_video", False) or node.get("media_type") == 2 or is_reel
        if is_vid:
            v_url = node.get("video_url") or (node.get("video_versions", [{}])[0].get("url") if node.get("video_versions") else "")
            if v_url:
                video_urls.append(v_url)
                carousel_items.append({
                    "url": v_url,
                    "format": "mp4",
                    "label": "ريلز فيديو (MP4)",
                    "isHd": True
                })
        else:
            img_url = node.get("display_url") or node.get("image_url")
            if img_url:
                photo_urls.append(img_url)
                carousel_items.append({
                    "url": img_url,
                    "format": "jpg",
                    "label": "صورة (JPG)",
                    "isHd": True
                })

    thumbnail = node.get("display_url") or (photo_urls[0] if photo_urls else (video_urls[0] if video_urls else None))

    qualities = []
    if len(carousel_items) > 1:
        v_count = len(video_urls)
        p_count = len(photo_urls)
        qualities.append({
            "label": f"تحميل جميع عناصر المنشور ({len(carousel_items)}) [{v_count} فيديو • {p_count} صورة]",
            "url": carousel_items[0]["url"],
            "format": "carousel",
            "isHd": True
        })
        if v_count > 0:
            qualities.append({
                "label": f"تحميل جميع الفيديوهات ({v_count} فيديو MP4)",
                "url": video_urls[0],
                "format": "mp4",
                "isHd": True
            })
        if p_count > 0:
            qualities.append({
                "label": f"تحميل جميع الصور ({p_count} صورة JPG)",
                "url": photo_urls[0],
                "format": "album",
                "isHd": True
            })
        for idx, item in enumerate(carousel_items):
            type_name = "فيديو MP4" if item["format"] == "mp4" else "صورة JPG"
            qualities.append({
                "label": f"سلايد {idx + 1} ({type_name})",
                "url": item["url"],
                "format": item["format"],
                "isHd": True
            })
    elif video_urls:
        qualities.append({
            "label": "تحميل ريلز فيديو عالي الدقة (MP4)" if is_reel else "تحميل فيديو عالي الدقة (MP4)",
            "url": video_urls[0],
            "format": "mp4",
            "isHd": True
        })
    elif photo_urls:
        qualities.append({
            "label": "تحميل صورة عالية الجودة (JPG)",
            "url": photo_urls[0],
            "format": "jpg",
            "isHd": True
        })

    primary_url = video_urls[0] if video_urls else (photo_urls[0] if photo_urls else "")

    return {
        "title": title,
        "videoUrl": primary_url,
        "thumbnailUrl": thumbnail,
        "platform": "Instagram",
        "sourceUrl": source_url,
        "qualityOptions": qualities,
        "carouselItems": carousel_items,
        "photoUrls": photo_urls if len(carousel_items) > 1 or not video_urls else []
    }
