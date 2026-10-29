import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from text_ab_router import DEFAULT_CONFIG, Router, cohort
from text_shadow_proxy import Shadow

VERSION = DEFAULT_CONFIG["expected_version"]


class ABTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.path = self.root / "ab.json"
        self.config(100)
        self.shadow = Shadow("http://127.0.0.1:1/pick", "http://127.0.0.1:2/pick", self.root / "samples")
        self.router = Router(self.path, "http://127.0.0.1:1/pick", "http://127.0.0.1:2/pick",
                             "http://127.0.0.1:3/pick", self.shadow, self.root / "events.jsonl")
        self.payload = {"image_b64": base64.b64encode(b"private-image").decode(),
                        "instruction": "包含文字：甲", "regions": [{"id": i} for i in range(1, 7)],
                        "ticket": "never-forward-to-fusion"}
        self.calls = []
        self.remote_failure = False
        self.local_failure = False
        self.version = VERSION

    def tearDown(self):
        self.shadow.pool.shutdown(wait=True)
        for writer in self.router.logger.handlers[:]:
            writer.close()
            self.router.logger.removeHandler(writer)
        self.temp.cleanup()

    def config(self, percent):
        self.path.write_text(json.dumps({**DEFAULT_CONFIG, "percent": percent}))

    def upstream(self, url, payload, timeout):
        self.calls.append(url)
        if url == self.router.primary:
            return {"ok": True, "region_ids": [1], "method": "old"}
        self.assertNotIn("ticket", payload)
        if url == self.router.remote and self.remote_failure:
            raise TimeoutError("remote unavailable")
        if url == self.router.local and self.local_failure:
            raise ConnectionError("local unavailable")
        return {"ok": True, "region_ids": [1, 3], "model_version": self.version}

    def run_pick(self, **kwargs):
        with patch("text_ab_router.post", side_effect=self.upstream), patch.object(self.shadow, "submit"):
            return self.router.pick(self.payload, **kwargs)

    def test_control_and_emergency_zero_do_not_call_fusion(self):
        self.config(0)
        result = self.run_pick()
        self.assertEqual(result["region_ids"], [1])
        self.assertEqual(self.calls, [self.router.primary])
        self.assertEqual(result["ab"]["cohort"], "A")

    def test_online_gpu_selected_and_no_secrets_in_event(self):
        result = self.run_pick()
        self.assertEqual(result["region_ids"], [1, 3])
        self.assertEqual(result["ab"]["served"], "gpu_fusion")
        self.assertNotIn("never-forward", (self.root / "events.jsonl").read_text())

    def test_offline_gpu_falls_back_locally_and_opens_circuit(self):
        self.remote_failure = True
        for _ in range(2):
            result = self.run_pick()
            self.assertEqual(result["ab"]["served"], "cpu_fusion")
        self.assertEqual(self.calls.count(self.router.remote), 1)
        self.assertTrue(self.router.health()["remote_circuit_open"])
        self.remote_failure = False
        self.router.open_until = time.monotonic() - 1
        self.assertEqual(self.run_pick()["ab"]["served"], "gpu_fusion")

    def test_both_fusion_workers_fail_returns_old(self):
        self.remote_failure = self.local_failure = True
        result = self.run_pick()
        self.assertEqual(result["ab"]["served"], "legacy")
        self.assertEqual(result["region_ids"], [1])

    def test_wrong_model_is_rejected(self):
        self.version = "wrong-version"
        self.assertEqual(self.run_pick()["ab"]["served"], "legacy")

    def test_remote_busy_does_not_queue_request(self):
        self.router.remote_slot.acquire()
        try:
            result = self.run_pick()
            self.assertEqual(result["ab"]["served"], "cpu_fusion")
            self.assertNotIn(self.router.remote, self.calls)
        finally:
            self.router.remote_slot.release()

    def test_invalid_config_fails_closed_even_with_replay_force(self):
        self.path.write_text('{"percent":100}')
        result = self.run_pick(source="replay", force="B")
        self.assertEqual(result["ab"]["cohort"], "A")
        self.assertEqual(result["ab"]["reason"], "invalid_config")

    def test_replay_excluded_from_live_counters_and_force_ignored_for_live(self):
        self.config(0)
        self.assertEqual(self.run_pick(source="live", force="B")["ab"]["cohort"], "A")
        self.assertEqual(self.run_pick(source="replay", force="B")["ab"]["served"], "gpu_fusion")
        self.assertEqual(self.router.health()["live_requests"], {"A:legacy": 1})

    def test_cohorts_are_stable_and_ramp_is_monotonic(self):
        assignments = []
        for i in range(1000):
            p = {**self.payload, "image_b64": base64.b64encode(str(i).encode()).decode()}
            small = cohort(p, {**DEFAULT_CONFIG, "percent": 10})
            large = cohort(p, {**DEFAULT_CONFIG, "percent": 25})
            self.assertEqual(small[1], large[1])
            if small[0] == "B":
                self.assertEqual(large[0], "B")
            assignments.append(small[0])
        self.assertGreater(assignments.count("B"), 60)
        self.assertLess(assignments.count("B"), 140)

    def test_real_http_remote_timeout_falls_back_with_bounded_latency(self):
        class Slow(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                time.sleep(.5)
                try:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{}')
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Slow)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.router.remote = f"http://127.0.0.1:{server.server_port}/pick"
        self.path.write_text(json.dumps({**DEFAULT_CONFIG, "percent": 100, "remote_timeout": .1}))
        from text_shadow_proxy import post

        def upstream(url, payload, timeout):
            return post(url, payload, timeout) if url == self.router.remote else self.upstream(url, payload, timeout)
        try:
            started = time.monotonic()
            with patch("text_ab_router.post", side_effect=upstream), patch.object(self.shadow, "submit"):
                result = self.router.pick(self.payload)
            self.assertEqual(result["ab"]["served"], "cpu_fusion")
            self.assertLess(time.monotonic() - started, .4)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
