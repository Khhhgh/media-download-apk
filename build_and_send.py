#!/usr/bin/env python3
"""
Build and Deployment Automation Script: media download - تحميل الميديا
Compiles the application (Flutter or Gradle) and delivers the APK directly to your Telegram chat.
Zero external dependencies: uses Python standard library (urllib, http.client).
"""

import os
import sys
import json
import uuid
import subprocess
import urllib.request
import urllib.parse
import urllib.error
import http.client

# Configuration
BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "8924731332:AAG3nTlbNLHnAwmDBgJYAAk4TSmonmfVEIU")
ADMIN_CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "8466040187")
MAX_TELEGRAM_SIZE_BYTES = 50 * 1024 * 1024  # 50 MB Telegram Bot API limit


def send_telegram_message(message: str):
    """Sends an HTML formatted message to the admin chat."""
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage"
    payload = json.dumps({
        "chat_id": ADMIN_CHAT_ID,
        "text": message,
        "parse_mode": "HTML"
    }).encode("utf-8")
    
    req = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data
    except Exception as e:
        print(f"[!] Failed to send Telegram message: {e}")
        return None


def send_apk_direct(apk_path: str, file_size_mb: float):
    """Sends APK directly via Telegram Bot API sendDocument using multipart/form-data."""
    print(f"[*] Sending APK directly to Telegram ({file_size_mb:.2f} MB)...")
    filename = os.path.basename(apk_path)
    caption = (
        "🚀 <b>media download - تحميل الميديا</b>\n"
        f"📦 <b>APK:</b> <code>{filename}</code>\n"
        f"📊 <b>Size:</b> {file_size_mb:.2f} MB\n"
        "⚡ <b>Architecture:</b> Remote Engine Gist & TikWM (ba31c38a)\n"
        "🎬 <b>Platforms:</b> TikTok, Instagram, Twitter/X, Facebook + 15 Platforms\n"
        "✅ <b>Status:</b> Ready to install"
    )

    boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
    body = bytearray()

    def add_field(name, value):
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8"))
        body.extend(f"{value}\r\n".encode("utf-8"))

    add_field("chat_id", ADMIN_CHAT_ID)
    add_field("caption", caption)
    add_field("parse_mode", "HTML")

    # Add file field
    file_header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="document"; filename="{filename}"\r\n'
        f"Content-Type: application/vnd.android.package-archive\r\n\r\n"
    ).encode("utf-8")
    body.extend(file_header)
    with open(apk_path, "rb") as f:
        body.extend(f.read())
    body.extend(f"\r\n--{boundary}--\r\n".encode("utf-8"))

    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendDocument"
    req = urllib.request.Request(
        url,
        data=bytes(body),
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(body)),
            "User-Agent": "MediaDownloadDeployer/1.0"
        }
    )

    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            resp_data = json.loads(resp.read().decode("utf-8"))
            if resp_data.get("ok"):
                print("[+] APK successfully sent to Telegram chat!")
                return True
            else:
                print(f"[!] Telegram API error: {resp_data}")
                return False
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8")
        print(f"[!] HTTP Error ({e.code}): {err_msg}")
        return False
    except Exception as e:
        print(f"[!] Network error sending APK: {e}")
        return False


def upload_and_notify_fallback(apk_path: str, file_size_mb: float):
    """Uploads APK to temporary hosting when > 50MB and sends direct link."""
    print(f"[*] File exceeds 50MB ({file_size_mb:.2f} MB). Uploading to fast temporary host...")
    filename = os.path.basename(apk_path)
    download_url = None

    # Try transfer.sh via PUT
    try:
        with open(apk_path, "rb") as f:
            data = f.read()
            req = urllib.request.Request(
                f"https://transfer.sh/{filename}",
                data=data,
                method="PUT"
            )
            with urllib.request.urlopen(req, timeout=180) as resp:
                if resp.status == 200:
                    download_url = resp.read().decode("utf-8").strip()
    except Exception as e:
        print(f"[!] transfer.sh error: {e}")

    # Fallback to file.io if transfer.sh is unavailable
    if not download_url:
        try:
            boundary = f"----FileIoBoundary{uuid.uuid4().hex}"
            file_header = (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                f"Content-Type: application/vnd.android.package-archive\r\n\r\n"
            ).encode("utf-8")
            body.extend(file_header)
            with open(apk_path, "rb") as f:
                body.extend(f.read())
            body.extend(f"\r\n--{boundary}--\r\n".encode("utf-8"))

            req = urllib.request.Request(
                "https://file.io",
                data=bytes(body),
                headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}
            )
            with urllib.request.urlopen(req, timeout=180) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("success"):
                    download_url = data.get("link")
        except Exception as e:
            print(f"[!] file.io error: {e}")

    if download_url:
        print(f"[+] Uploaded successfully: {download_url}")
        msg = (
            "🚀 <b>media download - تحميل الميديا</b>\n"
            f"📦 <b>Release APK ({file_size_mb:.2f} MB)</b>\n"
            "⚠️ <i>File exceeds Telegram 50MB bot upload limit.</i>\n\n"
            f"🔗 <b>Direct Download Link:</b>\n{download_url}\n\n"
            "⚡ <b>Architecture:</b> Remote Engine Gist (ba31c38a)"
        )
        send_telegram_message(msg)
    else:
        print("[!] Failed to upload to external hosts.")
        send_telegram_message(f"❌ Build completed ({file_size_mb:.2f} MB), but upload failed.")


def find_or_build_apk():
    """Locates existing APK or attempts build."""
    candidate_paths = [
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/release/app-release.apk",
        "build/app/outputs/flutter-apk/app-arm64-v8a-release.apk",
        "build/app/outputs/flutter-apk/app-release.apk",
        "flutter_source/build/app/outputs/flutter-apk/app-arm64-v8a-release.apk",
        "flutter_source/build/app/outputs/flutter-apk/app-release.apk"
    ]

    for path in candidate_paths:
        if os.path.isfile(path) and os.path.getsize(path) > 1000:
            print(f"[+] Found existing APK: {path}")
            return path

    # If Flutter CLI exists, run flutter build
    try:
        subprocess.run(["flutter", "--version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        print("[*] Flutter CLI found. Running: flutter build apk --release...")
        subprocess.run(["flutter", "build", "apk", "--release", "--split-per-abi"], check=True)
        for path in candidate_paths:
            if os.path.isfile(path):
                return path
    except Exception:
        pass

    # If Gradle exists, run gradle assembleDebug
    try:
        print("[*] Running gradle assembleDebug...")
        subprocess.run(["gradle", ":app:assembleDebug"], check=True)
        debug_apk = "app/build/outputs/apk/debug/app-debug.apk"
        if os.path.isfile(debug_apk):
            return debug_apk
    except Exception as e:
        print(f"[!] Gradle build error: {e}")

    return None


def main():
    apk_path = find_or_build_apk()
    if not apk_path:
        print("[!] No APK found and build could not complete.")
        sys.exit(1)

    size_bytes = os.path.getsize(apk_path)
    size_mb = size_bytes / (1024 * 1024)
    print(f"[*] Target APK: {apk_path} ({size_mb:.2f} MB)")

    if size_bytes <= MAX_TELEGRAM_SIZE_BYTES:
        success = send_apk_direct(apk_path, size_mb)
        if not success:
            upload_and_notify_fallback(apk_path, size_mb)
    else:
        upload_and_notify_fallback(apk_path, size_mb)


if __name__ == "__main__":
    main()
