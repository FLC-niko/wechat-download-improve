"""Loopback-only optional text fusion worker, reached through a private SSH tunnel."""
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
import re
import threading

from PIL import Image
from text_runtime import TextPredictor


def handler(predictor):
    slots = threading.BoundedSemaphore(1)

    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, value):
            body = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            if self.path != "/health":
                self.reply(404, {"error": "not found"})
                return
            self.reply(200, {"status": "ok", "text_loaded": True,
                             "text_version": predictor.version, "device": str(predictor.device)})

        def do_POST(self):
            if self.path != "/pick":
                self.reply(404, {"error": "not found"})
                return
            if not slots.acquire(blocking=False):
                self.reply(503, {"ok": False, "error": "busy"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 4 * 1024 * 1024:
                    raise ValueError("invalid request size")
                payload = json.loads(self.rfile.read(size))
                match = re.search(r"包含文字\s*[:：]?\s*(.+)", payload["instruction"])
                if not match:
                    raise ValueError("text requests only")
                target = "".join(c for c in match[1] if "\u4e00" <= c <= "\u9fff" or c.isalnum())
                image = Image.open(io.BytesIO(base64.b64decode(payload["image_b64"], validate=True))).convert("RGB")
                self.reply(200, predictor.pick(image, payload["regions"], target))
            except Exception as exc:
                self.reply(500, {"ok": False, "error": type(exc).__name__})
            finally:
                slots.release()

        def log_message(self, *args):
            pass
    return Handler


if __name__ == "__main__":
    predictor = TextPredictor(os.environ["SOLVER_TEXT_BUNDLE"],
                              threads=int(os.environ.get("CLIP_THREADS", "2")),
                              device=os.environ.get("TEXT_DEVICE", "cuda:0"),
                              gpu_memory_fraction=float(os.environ.get("TEXT_GPU_MEMORY_FRACTION", ".1")))
    server = ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("TEXT_GPU_PORT", "9997"))), handler(predictor))
    server.serve_forever()
