#!/usr/bin/env python3
"""
Build and Deployment Automation Script: media download - تحميل الميديا
Compiles the Flutter application and delivers the release APK to your Telegram chat.
"""

import os
import sys
import subprocess
import requests

# Configuration
BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "8924731332:AAG3nTlbNLHnAwmDBgJYAAk4TSmonmfVEIU")
ADMIN_CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "8466040187")
MAX_TELEGRAM_SIZE_BYTES = 50 * 1024 * 1024  # 50 MB Telegram Bot API limit


def check_prerequisites():
    """Verify flutter and requests dependencies are available."""
    try:
        subprocess.run(["flutter", "--version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("[!] Error: 'flutter' CLI is not installed or not in PATH.")
        sys.exit(1)


def build_flutter_apk():
    """Runs flutter build apk --release --split-per-abi to generate optimized APKs."""
    print("[*] Starting Flutter release build (split-per-abi)...")
    cmd = ["flutter", "build", "apk", "--release", "--split-per-abi"]
    
    result = subprocess.run(cmd, stdout=sys.stdout, stderr=sys.stderr)
    if result.returncode != 0:
        print("[!] Build failed. Attempting fallback: flutter build apk --release...")
        fallback = subprocess.run(["flutter", "build", "apk", "--release"], stdout=sys.stdout, stderr=sys.stderr)
        if fallback.returncode != 0:
            print("[!] Flutter build failed completely.")
            sys.exit(1)

    # Search for output APK
    candidate_paths = [
        "build/app/outputs/flutter-apk/app-arm64-v8a-release.apk",
        "build/app/outputs/flutter-apk/app-release.apk",
        "flutter_source/build/app/outputs/flutter-apk/app-arm64-v8a-release.apk",
        "flutter_source/build/app/outputs/flutter-apk/app-release.apk"
    ]

    for path in candidate_paths:
        if os.path.isfile(path):
            print(f"[+] Found release APK: {path}")
            return path

    print("[!] Could not locate generated APK file.")
    sys.exit(1)


def send_telegram_message(message: str):
    """Sends a notification message to the admin chat."""
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage"
    payload = {
        "chat_id": ADMIN_CHAT_ID,
        "text": message,
        "parse_mode": "HTML"
    }
    try:
        resp = requests.post(url, json=payload, timeout=20)
        return resp.json()
    except Exception as e:
        print(f"[!] Failed to send message: {e}")
        return None


def send_apk_direct(apk_path: str, file_size_mb: float):
    """Sends APK directly via Telegram Bot API sendDocument."""
    print(f"[*] Sending APK directly to Telegram ({file_size_mb:.2f} MB)...")
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendDocument"
    caption = (
        "🚀 <b>media download - تحميل الميديا</b>\n"
        "📦 <b>Release APK:</b> <code>app-arm64-v8a-release.apk</code>\n"
        f"📊 <b>Size:</b> {file_size_mb:.2f} MB\n"
        "⚡ <b>Architecture:</b> Remote Engine Gist (ba31c38a)\n"
        "✅ <b>Status:</b> Ready to install"
    )

    with open(apk_path, "rb") as apk_file:
        files = {"document": (os.path.basename(apk_path), apk_file, "application/vnd.android.package-archive")}
        data = {"chat_id": ADMIN_CHAT_ID, "caption": caption, "parse_mode": "HTML"}
        resp = requests.post(url, data=data, files=files, timeout=300)

    if resp.status_code == 200:
        print("[+] APK successfully sent to Telegram chat!")
    else:
        print(f"[!] Telegram API error ({resp.status_code}): {resp.text}")


def upload_and_notify_fallback(apk_path: str, file_size_mb: float):
    """Uploads APK to temporary hosting when > 50MB and sends direct link."""
    print(f"[*] File exceeds 50MB ({file_size_mb:.2f} MB). Uploading to fast temporary host...")
    filename = os.path.basename(apk_path)
    download_url = None

    # Try transfer.sh
    try:
        with open(apk_path, "rb") as f:
            resp = requests.put(f"https://transfer.sh/{filename}", data=f, timeout=120)
            if resp.status_code == 200:
                download_url = resp.text.strip()
    except Exception as e:
        print(f"[!] transfer.sh error: {e}")

    # Fallback to file.io if needed
    if not download_url:
        try:
            with open(apk_path, "rb") as f:
                resp = requests.post("https://file.io", files={"file": f}, timeout=120)
                if resp.status_code == 200:
                    download_url = resp.json().get("link")
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


def main():
    check_prerequisites()
    apk_path = build_flutter_apk()
    size_bytes = os.path.getsize(apk_path)
    size_mb = size_bytes / (1024 * 1024)

    print(f"[*] File size: {size_mb:.2f} MB ({size_bytes} bytes)")

    if size_bytes <= MAX_TELEGRAM_SIZE_BYTES:
        send_apk_direct(apk_path, size_mb)
    else:
        upload_and_notify_fallback(apk_path, size_mb)


if __name__ == "__main__":
    main()
