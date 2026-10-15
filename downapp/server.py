#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
downapp/server.py: Standalone Lightweight Cloud Microservice for Dynamic OTA Runner
Can be run on any cloud host, VPS, or server:
    python3 downapp/server.py [port]

Endpoints:
    GET  /version   -> returns {"version": int}
    POST /extract   -> body {"url": "..."} -> returns MediaResult JSON
"""

import json
import os
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

# Do not leave __pycache__ behind in the app package folder.
sys.dont_write_bytecode = True

from runner import run


class DynamicRunnerHandler(BaseHTTPRequestHandler):
    def _set_headers(self, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_OPTIONS(self):
        self._set_headers(200)

    def do_GET(self):
        if self.path == "/version":
            ver_file = os.path.join(BASE_DIR, "version.txt")
            version_val = 1
            if os.path.exists(ver_file):
                try:
                    with open(ver_file, "r") as f:
                        version_val = int(f.read().strip())
                except Exception:
                    pass
            self._set_headers(200)
            self.wfile.write(json.dumps({"version": version_val, "status": "online"}).encode("utf-8"))
        else:
            self._set_headers(200)
            self.wfile.write(json.dumps({"status": "running", "service": "downapp-dynamic-runner"}).encode("utf-8"))

    def do_POST(self):
        if self.path in ("/extract", "/api/extract"):
            content_length = int(self.headers.get("Content-Length", 0))
            post_data = self.rfile.read(content_length)
            try:
                req_json = json.loads(post_data.decode("utf-8"))
                target_url = req_json.get("url", "")
                if not target_url:
                    self._set_headers(400)
                    self.wfile.write(json.dumps({"error": "Missing 'url' parameter"}).encode("utf-8"))
                    return

                res = run(target_url)
                self._set_headers(200)
                self.wfile.write(json.dumps(res, ensure_ascii=False).encode("utf-8"))
            except Exception as e:
                self._set_headers(500)
                self.wfile.write(json.dumps({"error": str(e), "success": False}).encode("utf-8"))
        else:
            self._set_headers(404)
            self.wfile.write(json.dumps({"error": "Not Found"}).encode("utf-8"))


def start_server(port: int = 8080):
    server_address = ("", port)
    httpd = HTTPServer(server_address, DynamicRunnerHandler)
    print(f"[downapp] Dynamic Cloud Runner server listening on port {port}...")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[downapp] Stopping server.")
        httpd.server_close()


if __name__ == "__main__":
    port_arg = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    start_server(port_arg)
