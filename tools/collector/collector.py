"""Python 3.10+, standard library only. Never reads or exports auth.json."""
from __future__ import annotations

import argparse
import hmac
import json
import math
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_BODY = 16 * 1024


def positive_int(value):
    return type(value) is int and value > 0


def validate_snapshot(value, now=None):
    now = int(time.time()) if now is None else now
    if (not isinstance(value, dict)
            or set(value) != {"schemaVersion", "collectedAt", "expiresAt", "windows"}
            or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or not positive_int(value["collectedAt"]) or not positive_int(value["expiresAt"])
            or value["collectedAt"] > now + 60
            or not 0 < value["expiresAt"] - value["collectedAt"] <= 3600
            or not isinstance(value["windows"], dict)
            or set(value["windows"]) != {"fiveHour", "weekly"}):
        raise ValueError("invalid_snapshot")
    for name, minutes in (("fiveHour", 300), ("weekly", 10080)):
        window = value["windows"][name]
        if window is None:
            continue
        if (not isinstance(window, dict)
                or set(window) != {"usedPercent", "windowMinutes", "resetsAt"}
                or type(window["usedPercent"]) not in (int, float)
                or not math.isfinite(window["usedPercent"])
                or not 0 <= window["usedPercent"] <= 100
                or type(window["windowMinutes"]) is not int or window["windowMinutes"] != minutes
                or not (window["resetsAt"] is None or positive_int(window["resetsAt"]))):
            raise ValueError("invalid_window")
    return value


def normalize(result, now=None, ttl=900):
    now = int(time.time()) if now is None else now
    if not isinstance(result, dict):
        raise ValueError("invalid_rate_limits")
    buckets = result.get("rateLimitsByLimitId")
    if buckets is not None:
        # Do not silently display another product's bucket as Codex quota.
        bucket = buckets.get("codex") if isinstance(buckets, dict) else None
    else:
        bucket = result.get("rateLimits")
    if not isinstance(bucket, dict):
        raise ValueError("codex_bucket_unavailable")
    windows = {"fiveHour": None, "weekly": None}
    for key in ("primary", "secondary"):
        window = bucket.get(key)
        if not isinstance(window, dict):
            continue
        duration = window.get("windowDurationMins")
        name = {300: "fiveHour", 10080: "weekly"}.get(duration) if type(duration) is int else None
        if name:
            used = window.get("usedPercent")
            # Missing is unknown, never zero. Validation rejects malformed known values.
            if used is None:
                continue
            windows[name] = {"usedPercent": used, "windowMinutes": duration,
                             "resetsAt": window.get("resetsAt")}
    return validate_snapshot({"schemaVersion": 1, "collectedAt": now,
                              "expiresAt": now + ttl, "windows": windows}, now)


def read_app_server(command=None, timeout=25):
    executable = shutil.which("codex")
    if command is None and not executable:
        raise RuntimeError("codex_not_found")
    process = subprocess.Popen(command or [executable, "app-server"], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               text=True, encoding="utf-8", bufsize=1)
    messages = queue.Queue(maxsize=256)

    def receive():
        try:
            while True:
                line = process.stdout.readline(65537)
                if not line:
                    break
                if len(line) > 65536:
                    break
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                try:
                    messages.put(message, timeout=1)
                except queue.Full:
                    break
        finally:
            try:
                messages.put(None, timeout=1)
            except queue.Full:
                pass

    threading.Thread(target=receive, daemon=True).start()

    def send(message):
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.flush()

    def request(identifier, method, params=None):
        message = {"id": identifier, "method": method}
        if params is not None:
            message["params"] = params
        send(message)
        deadline = time.monotonic() + timeout
        while True:
            try:
                reply = messages.get(timeout=max(0, deadline - time.monotonic()))
            except queue.Empty:
                raise RuntimeError("app_server_timeout") from None
            if reply is None:
                raise RuntimeError("app_server_closed")
            if not isinstance(reply, dict) or reply.get("id") != identifier:
                continue
            if "error" in reply:
                # Server messages may contain account details; never forward their text.
                raise RuntimeError("app_server_request_failed")
            return reply.get("result")

    try:
        request(1, "initialize", {"clientInfo": {"name": "codex_meter_cn", "version": "0.1.0"}})
        send({"method": "initialized"})
        return request(2, "account/rateLimits/read")
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
        process.stdin.close()
        process.stdout.close()


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_endpoint(url):
    parsed = urlsplit(url)
    if (parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path != "/v1/snapshot"
            or not parsed.hostname
            or not (parsed.scheme == "https" or
                    parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1"))):
        raise ValueError("https_snapshot_url_required")
    return url


def upload(snapshot, url, token):
    validate_endpoint(url)
    if len(token) < 32:
        raise ValueError("write_token_too_short")
    body = json.dumps(validate_snapshot(snapshot), allow_nan=False).encode()
    request = Request(url, data=body, method="PUT", headers={
        "Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with build_opener(NoRedirect).open(request, timeout=15) as response:
        if response.status != 200:
            raise RuntimeError("upload_failed")


class SnapshotStore:
    def __init__(self):
        self.snapshot = None
        self.lock = threading.Lock()

    def set(self, snapshot):
        with self.lock:
            self.snapshot = validate_snapshot(snapshot)

    def get(self):
        with self.lock:
            return self.snapshot


def make_server(host, port, store, token):
    if len(token) < 32:
        raise ValueError("read_token_too_short")

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/health":
                return self.reply(200, {"ok": True, "schemaVersion": 1})
            if self.path != "/v1/snapshot":
                return self.reply(404, {"error": "not_found"})
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + token).encode()):
                return self.reply(401, {"error": "unauthorized"})
            snapshot = store.get()
            self.reply(200 if snapshot else 503, snapshot or {"error": "no_snapshot"})

        def reply(self, status, value):
            body = json.dumps(value, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    return ThreadingHTTPServer((host, port), Handler)


def demo_snapshot():
    return normalize({"rateLimits": {
        "primary": {"usedPercent": 37, "windowDurationMins": 300, "resetsAt": int(time.time()) + 7200},
        "secondary": {"usedPercent": 68, "windowDurationMins": 10080, "resetsAt": int(time.time()) + 172800}}})


def main():
    parser = argparse.ArgumentParser(description="Codex Meter CN 只读额度采集器")
    parser.add_argument("--demo", action="store_true", help="仅生成模拟额度，不读取 Codex")
    parser.add_argument("--once", action="store_true", help="采集一次后退出")
    parser.add_argument("--serve", action="store_true", help="启用直连快照网关")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8788)
    parser.add_argument("--interval", type=int, default=300)
    parser.add_argument("--snapshot", type=Path, help="可选：写入已脱敏的快照文件（不要提交）")
    args = parser.parse_args()
    if args.interval < 60 or args.interval > 3600 or args.serve and args.once:
        parser.error("interval 须为 60–3600 秒；serve 不能与 once 同用")
    read_token = os.environ.get("METER_READ_TOKEN", "")
    write_token = os.environ.get("METER_WRITE_TOKEN", "")
    url = os.environ.get("METER_UPLOAD_URL", "")
    if args.serve and len(read_token) < 32:
        parser.error("直连需设置至少 32 字符的 METER_READ_TOKEN")
    if url:
        try:
            validate_endpoint(url)
        except ValueError:
            parser.error("METER_UPLOAD_URL 须为 HTTPS /v1/snapshot 地址")
        if len(write_token) < 32 or write_token == read_token:
            parser.error("需设置独立且至少 32 字符的 METER_WRITE_TOKEN")
    if args.snapshot and not args.snapshot.name.endswith(".private.json"):
        parser.error("快照文件名必须以 .private.json 结尾以防误提交")
    store = SnapshotStore()
    server = make_server(args.host, args.port, store, read_token) if args.serve else None
    if server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        while True:
            try:
                snapshot = demo_snapshot() if args.demo else normalize(read_app_server())
                store.set(snapshot)
                if args.snapshot:
                    temporary = args.snapshot.with_suffix(".private.json.tmp")
                    temporary.write_text(json.dumps(snapshot, allow_nan=False), encoding="utf-8")
                    temporary.replace(args.snapshot)
                if url:
                    upload(snapshot, url, write_token)
                print("模拟快照已更新" if args.demo else "额度快照已更新", flush=True)
                if args.once:
                    return 0
            except (RuntimeError, ValueError, OSError, HTTPError, URLError):
                # No raw errors, tokens, URLs, RPC responses, or credentials in logs.
                print("采集或上传失败；保留旧快照且不延长有效期。请检查本地登录、连接和配置。", flush=True)
                if args.once:
                    return 1
            time.sleep(args.interval)
    except KeyboardInterrupt:
        return 0
    finally:
        if server:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    raise SystemExit(main())
