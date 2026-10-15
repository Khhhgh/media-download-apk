#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
downapp/common.py: High-Reliability Dual-Engine HTTP Client & Auto-Installer
Operates seamlessly with `requests` when present, or purely with standard library `urllib`
when pip/external packages are not installed.

It also ships a small public-relay helper (`relay_get` / `fetch`) used by the
engines to keep working on networks where a platform domain is blocked (the
extraction runs locally on the device, so when TikTok/Pinterest are blocked the
only way around it is to ask an unblocked relay to fetch the page for us).
"""

import gzip
import json
import os
import shutil
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zlib
from typing import Any, Dict, Optional, Tuple

UNIVERSAL_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

MOBILE_USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
)

# Public read-only relays used when a platform domain is unreachable without a
# VPN. They are tried in order and the first useful body wins. `jina` honours
# x-respond-with and is the most reliable of them, so it is tried first.
PUBLIC_RELAYS = (
    "jina",         # https://r.jina.ai/<url>  (x-respond-with: html|text)
    "allorigins",   # https://api.allorigins.win/raw?url=...
    "codetabs",     # https://api.codetabs.com/v1/proxy?quest=...
    "isomorphic",   # https://cors.isomorphic-git.org/<url>
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


def _decompress(raw: bytes, encoding: str) -> bytes:
    """Decode gzip/deflate bodies the stdlib urllib fallback does not touch."""
    enc = (encoding or "").lower()
    try:
        if "gzip" in enc:
            return gzip.decompress(raw)
        if "deflate" in enc:
            try:
                return zlib.decompress(raw)
            except zlib.error:
                return zlib.decompress(raw, -zlib.MAX_WBITS)
    except Exception:
        return raw
    return raw


def _normalize_headers(headers: Optional[Dict[str, str]], base: Dict[str, str]) -> Dict[str, str]:
    merged = dict(base)
    if headers:
        merged.update(headers)
    return merged


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
    request_headers = _normalize_headers(headers, default_headers)

    # Try requests library if available
    try:
        import requests
        resp = requests.get(url, headers=request_headers, timeout=timeout, allow_redirects=allow_redirects)
        return resp.status_code, resp.text, resp.url
    except ImportError:
        pass
    except Exception:
        pass

    # Standard Library Fallback
    try:
        req = urllib.request.Request(url, headers=request_headers)
        handlers = [urllib.request.HTTPSHandler(context=_create_ssl_context())]
        if not allow_redirects:
            handlers.append(NoRedirectHandler())
        opener = urllib.request.build_opener(*handlers)
        with opener.open(req, timeout=timeout) as resp:
            raw = _decompress(resp.read(), resp.headers.get("Content-Encoding", ""))
            content = raw.decode("utf-8", errors="replace")
            return resp.status, content, resp.geturl()
    except urllib.error.HTTPError as e:
        raw = e.read() if e.fp else b""
        raw = _decompress(raw, e.headers.get("Content-Encoding", "") if e.headers else "")
        content = raw.decode("utf-8", errors="replace")
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
    request_headers = _normalize_headers(headers, default_headers)

    # Try requests library
    try:
        import requests
        resp = requests.post(url, data=data, json=json_data, headers=request_headers, timeout=timeout)
        return resp.status_code, resp.text, resp.url
    except ImportError:
        pass
    except Exception:
        pass

    # Standard Library Fallback
    try:
        body_bytes = b""
        if json_data is not None:
            request_headers["Content-Type"] = "application/json; charset=utf-8"
            body_bytes = json.dumps(json_data).encode("utf-8")
        elif data is not None:
            request_headers["Content-Type"] = "application/x-www-form-urlencoded; charset=utf-8"
            body_bytes = urllib.parse.urlencode(data).encode("utf-8")

        req = urllib.request.Request(url, data=body_bytes, headers=request_headers)
        opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=_create_ssl_context()))
        with opener.open(req, timeout=timeout) as resp:
            raw = _decompress(resp.read(), resp.headers.get("Content-Encoding", ""))
            content = raw.decode("utf-8", errors="replace")
            return resp.status, content, resp.geturl()
    except urllib.error.HTTPError as e:
        raw = e.read() if e.fp else b""
        raw = _decompress(raw, e.headers.get("Content-Encoding", "") if e.headers else "")
        content = raw.decode("utf-8", errors="replace")
        return e.code, content, e.geturl()
    except Exception as e:
        return 0, str(e), url


def http_get_location(
    url: str,
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 12
) -> Optional[str]:
    """Returns the Location header of a non-redirecting GET (None on failure)."""
    default_headers = {
        "User-Agent": UNIVERSAL_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }
    request_headers = _normalize_headers(headers, default_headers)

    try:
        import requests
        resp = requests.get(url, headers=request_headers, timeout=timeout, allow_redirects=False)
        return resp.headers.get("Location")
    except ImportError:
        pass
    except Exception:
        return None

    try:
        req = urllib.request.Request(url, headers=request_headers)
        opener = urllib.request.build_opener(
            NoRedirectHandler,
            urllib.request.HTTPSHandler(context=_create_ssl_context()),
        )
        try:
            with opener.open(req, timeout=timeout) as resp:
                return resp.headers.get("Location") or resp.geturl()
        except urllib.error.HTTPError as e:
            return e.headers.get("Location") if e.headers else None
    except Exception:
        return None


def _relay_url(provider: str, target: str, response_format: str = "html") -> Tuple[str, Dict[str, str]]:
    """Builds the relay request URL + extra headers for a provider."""
    if provider == "allorigins":
        return "https://api.allorigins.win/raw?url=" + urllib.parse.quote(target, safe=""), {}
    if provider == "jina":
        return "https://r.jina.ai/" + target, {"x-respond-with": response_format, "Accept": "*/*"}
    if provider == "codetabs":
        return "https://api.codetabs.com/v1/proxy?quest=" + urllib.parse.quote(target, safe=""), {}
    if provider == "isomorphic":
        return "https://cors.isomorphic-git.org/" + target, {}
    return target, {}


def relay_get(
    url: str,
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 20,
    min_length: int = 200,
    response_format: str = "html"
) -> Tuple[int, str, str]:
    """
    Fetches `url` through public relays so a blocked platform domain can still
    be read. `response_format` is "html" for pages and "text" for JSON
    endpoints. Returns (status, body, final_url); status 0 when all fail.
    """
    for provider in PUBLIC_RELAYS:
        attempts = 2 if provider == "jina" else 1
        for _ in range(attempts):
            relay_url, extra = _relay_url(provider, url, response_format)
            status, body, _ = http_get(relay_url, headers=extra, timeout=timeout)
            if status == 200 and body and len(body) >= min_length:
                return status, body, url
    return 0, "", url


def fetch(
    url: str,
    headers: Optional[Dict[str, str]] = None,
    timeout: int = 15,
    use_relay: bool = True,
    response_format: str = "html"
) -> Tuple[int, str, str]:
    """
    Direct GET, falling back to public relays when the direct request fails or
    comes back empty (typical when the ISP blocks the domain without a VPN).
    """
    status, body, final = http_get(url, headers=headers, timeout=timeout)
    if status == 200 and body:
        return status, body, final
    if use_relay:
        relay_status, relay_body, _ = relay_get(
            url, headers=headers, timeout=max(timeout, 20), response_format=response_format
        )
        if relay_status == 200 and relay_body:
            return relay_status, relay_body, url
    return status, body, final


def cleanup_temp(base_dir: Optional[str] = None) -> None:
    """
    Removes temporary artifacts left behind by the engines (the ``__pycache__``
    folders Python creates on import and any ``temp``/``.tmp`` scratch dir).
    Safe to call after every extraction.
    """
    if base_dir is None:
        base_dir = os.path.dirname(os.path.abspath(__file__))
    try:
        for root, dirs, _files in os.walk(base_dir):
            for name in list(dirs):
                if name == "__pycache__" or name in ("temp", ".tmp", "tmp"):
                    shutil.rmtree(os.path.join(root, name), ignore_errors=True)
                    dirs.remove(name)
    except Exception:
        pass
