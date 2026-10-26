"""Gradual text rollout with independent local inference and optional GPU fusion.

Only text picks participate. Existing scene and sequence requests use primary.
Config is read per request; setting percent=0 immediately restores old picks.
No external verification attempts are created by this process.
"""
from collections import Counter
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import threading
import time
import urllib.parse

from text_shadow_proxy import Shadow, post

DEFAULT_CONFIG = {"schema": 1, "phase": "text-v2-20261026", "percent": 0,
                  "expected_version": "6cc80d5cde1b81ec",
                  "remote_timeout": 2.5, "local_timeout": 4.0, "cooldown": 30.0}


def configuration(path):
    value = json.loads(Path(path).read_text())
    assert value.get("schema") == 1
    assert type(value.get("percent")) is int and 0 <= value["percent"] <= 100
    assert isinstance(value.get("phase"), str) and 0 < len(value["phase"]) <= 80
    assert isinstance(value.get("expected_version"), str) and len(value["expected_version"]) == 16
    value = {**DEFAULT_CONFIG, **value}
    assert .1 <= value["remote_timeout"] <= 5
    assert .1 <= value["local_timeout"] <= 8
    assert value["remote_timeout"] + value["local_timeout"] <= 8
    assert 1 <= value["cooldown"] <= 300
    return value


def cohort(payload, config):
    # The same image/target retains its assignment across retries and ramp stages.
    raw = hashlib.sha256(payload["image_b64"].encode()).hexdigest()
    key = hashlib.sha256((config["phase"] + raw + payload["instruction"]).encode()).hexdigest()
    bucket = int(key[:8], 16) % 10000
    return ("B" if bucket < config["percent"] * 100 else "A"), key


def validate_pick(value, payload, version):
    if not value.get("ok") or value.get("model_version") != version:
        raise ValueError("unhealthy or unexpected model version")
    selected = value.get("region_ids")
    valid = {r["id"] for r in payload["regions"]}
    if not isinstance(selected, list) or len(selected) != len(set(selected)):
        raise ValueError("invalid selected set")
    if any(type(i) is not int or i not in valid for i in selected):
        raise ValueError("selected region outside request")
    return value


class Router:
    def __init__(self, config, primary, local, remote, shadow, events):
        self.config_path, self.primary, self.local, self.remote = config, primary, local, remote
        for url in (primary, local, remote):
            if urllib.parse.urlparse(url).hostname not in ("127.0.0.1", "localhost", "::1"):
                raise ValueError("upstreams must be loopback; remote requires private tunnel")
        self.shadow = shadow
        self.lock = threading.Lock()
        self.remote_slot = threading.BoundedSemaphore(1)
        self.open_until = 0.0
        self.counters = Counter()
        path = Path(events)
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.logger = logging.getLogger("text-ab." + str(id(self)))
        self.logger.setLevel(logging.INFO)
        self.logger.propagate = False
        writer = RotatingFileHandler(path, maxBytes=10 * 1024 * 1024, backupCount=5)
        writer.setFormatter(logging.Formatter("%(message)s"))
        self.logger.addHandler(writer)

    def config(self):
        try:
            return configuration(self.config_path), None
        except Exception:
            # A malformed rollout file must never enable treatment or break primary.
            return dict(DEFAULT_CONFIG), "invalid_config"

    def health(self):
        config, error = self.config()
        with self.lock:
            return {"status": "ok", "mode": "ab", "percent": config["percent"],
                    "phase": config["phase"], "expected_version": config["expected_version"],
                    "config_error": error, "remote_circuit_open": time.monotonic() < self.open_until,
                    "live_requests": dict(self.counters),
                    "dropped_shadow_requests": self.shadow.dropped}

    def pick(self, payload, source="live", force=None):
        started = time.monotonic()
        is_text = "包含文字" in payload.get("instruction", "")
        baseline = post(self.primary, payload, 20 if is_text else 30)
        if not baseline.get("ok"):
            raise ValueError("primary unavailable")
        if not is_text:
            return baseline
        safe = {k: payload[k] for k in ("image_b64", "instruction", "regions")}
        config, error = self.config()
        group, key = cohort(safe, config)
        if source == "replay" and force in ("A", "B") and not error:
            group = force
        selected, served, reason = baseline, "legacy", error or "control"
        candidate = None
        if group == "B":
            with self.lock:
                available = time.monotonic() >= self.open_until
            acquired = available and self.remote_slot.acquire(blocking=False)
            reason = "remote_circuit_open" if not available else "remote_busy"
            if acquired:
                try:
                    candidate = validate_pick(post(self.remote, safe, config["remote_timeout"]),
                                              safe, config["expected_version"])
                    selected, served, reason = candidate, "gpu_fusion", "remote_ok"
                    with self.lock:
                        self.open_until = 0.0
                except Exception as exc:
                    reason = "remote_" + type(exc).__name__
                    with self.lock:
                        self.open_until = time.monotonic() + config["cooldown"]
                finally:
                    self.remote_slot.release()
            if candidate is None:
                try:
                    candidate = validate_pick(post(self.local, safe, config["local_timeout"]),
                                              safe, config["expected_version"])
                    selected, served = candidate, "cpu_fusion"
                except Exception as exc:
                    reason += ";local_" + type(exc).__name__
        routing = {"phase": config["phase"], "percent": config["percent"], "cohort": group,
                   "served": served, "reason": reason, "request_id": key[:24],
                   "expected_version": config["expected_version"], "source": source,
                   "cost_ms": round((time.monotonic() - started) * 1000, 1)}
        self.logger.info(json.dumps(routing, ensure_ascii=False))
        if source == "live":
            with self.lock:
                self.counters[group + ":" + served] += 1
        try:
            self.shadow.submit(safe, baseline, source, candidate_result=candidate, routing=routing)
        except Exception:
            self.shadow.dropped += 1
        return {**selected, "ab": routing}


def handler(router):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, value):
            data = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            self.reply(200, router.health()) if self.path == "/health" else self.reply(404, {"error": "not found"})

        def do_POST(self):
            if self.path not in ("/pick", "/seq-pick"):
                self.reply(404, {"error": "not found"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 4 * 1024 * 1024:
                    self.reply(413, {"error": "invalid request size"})
                    return
                payload = json.loads(self.rfile.read(size))
                if self.path == "/seq-pick":
                    result = post(router.primary.removesuffix("/pick") + "/seq-pick", payload, 80)
                else:
                    source = "replay" if self.headers.get("X-Eink-Shadow-Source") == "replay" else "live"
                    result = router.pick(payload, source, self.headers.get("X-Eink-AB-Force"))
                self.reply(200, result)
            except Exception as exc:
                self.reply(502, {"ok": False, "error": type(exc).__name__})

        def log_message(self, *args):
            pass
    return Handler


if __name__ == "__main__":
    os.umask(0o077)
    primary = os.environ.get("TEXT_PRIMARY_URL", "http://127.0.0.1:9993/pick")
    local = os.environ.get("TEXT_CANDIDATE_URL", "http://127.0.0.1:9994/pick")
    shadow = Shadow(primary, local, os.environ.get("TEXT_SHADOW_DIR", "/home/topview/captcha/text-shadow"),
                    review_queue=os.environ.get("TEXT_REVIEW_QUEUE", "/home/topview/captcha/text-review-queue.jsonl"))
    router = Router(os.environ.get("TEXT_AB_CONFIG", "/home/topview/captcha/text-ab.json"), primary, local,
                    os.environ.get("TEXT_REMOTE_URL", "http://127.0.0.1:9996/pick"), shadow,
                    os.environ.get("TEXT_AB_EVENTS", "/home/topview/captcha/text-ab-events.jsonl"))
    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("TEXT_SHADOW_PORT", "9995"))), handler(router))
    try:
        server.serve_forever()
    finally:
        server.server_close()
        shadow.pool.shutdown(wait=True)
