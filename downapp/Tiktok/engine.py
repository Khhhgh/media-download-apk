#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json

def extract(url: str, config=None):
    return {
        "title": "🔥🔥 المحركات تعمل بنجاح من بايثون 🔥🔥",
        "videoUrl": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        "thumbnailUrl": "https://via.placeholder.com/300",
        "platform": "TikTok",
        "qualityOptions": [
            {
                "label": "فيديو تجريبي للاختبار",
                "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                "format": "mp4",
                "isHd": True
            }
        ],
        "carouselItems": [],
        "photoUrls": []
    }
