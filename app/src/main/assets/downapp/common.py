#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
downapp/common.py: High-Reliability Dual-Engine HTTP Client & Auto-Installer
Operates seamlessly with `requests` when present, or purely with standard library `urllib`
when pip/external packages are not installed.
"""

import json
import os
import ssl
import subprocess
import sys
import urllib.parse
import urllib.request
from typing import Any, Dict, Optional, Tuple

UNIVERSAL_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

MOBILE_USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
)


def auto_install_requirements():
    """Attempts to install packages from requirements.txt if pip is available."""
    req_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "requirements.txt")
    if os.path.exists(req_file):
        try:
            subprocess.run([sys.executable, "-m", "pip", "install", "-r", req_file, "--quiet"],
                           check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass


def _create_ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_get(
    url: str,
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 12,
    allow_redirects: bool = True
) -> Tuple[int, str, str]:
    """
    Performs HTTP GET returning (status_code, body_string, final_url).
    Prefers `requests` if installed, falls back smoothly to `urllib.request`.
    """
    default_headers = {
        "User-Agent": UNIVERSAL_USER_AGENT,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9,ar;q=0.8",
    }
    if headers:
        default_headers.update(headers)

    # Try requests library if available
    try:
        import requests
        resp = requests.get(url, headers=default_headers, timeout=timeout, allow_redirects=allow_redirects)
        return resp.status_code, resp.text, resp.url
    except ImportError:
        pass
    except Exception:
        pass

    # Standard Library Fallback
    try:
        req = urllib.request.Request(url, headers=default_headers)
        handlers = [urllib.request.HTTPSHandler(context=_create_ssl_context())]
        if not allow_redirects:
            handlers.append(NoRedirectHandler())
        opener = urllib.request.build_opener(*handlers)
        with opener.open(req, timeout=timeout) as resp:
            content = resp.read().decode("utf-8", errors="replace")
            return resp.status, content, resp.geturl()
    except urllib.error.HTTPError as e:
        content = e.read().decode("utf-8", errors="replace") if e.fp else ""
        return e.code, content, e.geturl()
    except Exception as e:
        return 0, str(e), url


def http_post(
    url: str,
    data: Optional[Dict[str, Any]] = None,
    json_data: Optional[Dict[str, Any]] = None,
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 12
) -> Tuple[int, str, str]:
    """
    Performs HTTP POST returning (status_code, body_string, final_url).
    """
    default_headers = {
        "User-Agent": UNIVERSAL_USER_AGENT,
        "Accept": "application/json, text/plain, */*",
    }
    if headers:
        default_headers.update(headers)

    # Try requests library
    try:
        import requests
        resp = requests.post(url, data=data, json=json_data, headers=default_headers, timeout=timeout)
        return resp.status_code, resp.text, resp.url
    except ImportError:
        pass
    except Exception:
        pass

    # Standard Library Fallback
    try:
        body_bytes = b""
        if json_data is not None:
            default_headers["Content-Type"] = "application/json; charset=utf-8"
            body_bytes = json.dumps(json_data).encode("utf-8")
        elif data is not None:
            default_headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8"
            body_bytes = urllib.parse.urlencode(data).encode("utf-8")

        req = urllib.request.Request(url, data=body_bytes, headers=default_headers)
        opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=_create_ssl_context()))
        with opener.open(req, timeout=timeout) as resp:
            content = resp.read().decode("utf-8", errors="replace")
            return resp.status, content, resp.geturl()
    except urllib.error.HTTPError as e:
        content = e.read().decode("utf-8", errors="replace") if e.fp else ""
        return e.code, content, e.geturl()
    except Exception as e:
        return 0, str(e), url
