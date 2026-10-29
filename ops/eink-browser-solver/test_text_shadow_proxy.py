import base64
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

from text_shadow_proxy import Shadow,handler,post


class ShadowTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.servers=[];self.shadows=[]

    def tearDown(self):
        for s in self.servers:s.shutdown();s.server_close()
        for shadow in self.shadows:shadow.pool.shutdown(wait=True)
        self.temp.cleanup()

    def server(self,cls):
        s=ThreadingHTTPServer(("127.0.0.1",0),cls)
        threading.Thread(target=s.serve_forever,daemon=True).start()
        self.servers.append(s)
        return f"http://127.0.0.1:{s.server_address[1]}/pick"

    def upstream(self,result=None,delay=0):
        class Upstream(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                time.sleep(delay)
                data=json.dumps(result or {"ok":True,"region_ids":[1,3]}).encode()
                self.send_response(200);self.end_headers();self.wfile.write(data)
            def log_message(self,*args):pass
        return self.server(Upstream)

    def test_shadow_returns_primary_without_waiting_for_candidate_and_whitelists(self):
        primary=self.upstream({"ok":True,"region_ids":[1,3],"ticket":"upstream-secret"})
        candidate=self.upstream({"ok":True,"region_ids":[1,3,5]},1)
        queue=Path(self.temp.name)/"queue.jsonl"
        shadow=Shadow(primary,candidate,self.temp.name,review_queue=queue);self.shadows.append(shadow)
        proxy=self.server(handler(shadow))
        payload={"image_b64":base64.b64encode(b"private-image").decode(),
                 "instruction":"包含文字：甲","regions":[],"ticket":"must-not-be-saved"}
        started=time.monotonic();result=post(proxy,payload,3)
        self.assertEqual(result["region_ids"],[1,3])
        self.assertLess(time.monotonic()-started,.8)
        shadow.pool.shutdown(wait=True)
        records=list(Path(self.temp.name).glob("*.json"))
        self.assertEqual(len(records),1)
        text=records[0].read_text();self.assertNotIn("must-not-be-saved",text)
        self.assertNotIn("upstream-secret",text)
        record=json.loads(text)
        self.assertEqual(record["candidate"]["region_ids"],[1,3,5])
        self.assertEqual(record["label_status"],"human-label-required")
        self.assertNotIn("selected",record)
        queued=[json.loads(line) for line in queue.read_text().splitlines()]
        self.assertEqual(len(queued),1)
        self.assertEqual(queued[0]["label_status"],"human-label-required")

    def test_bounded_queue_does_not_block_primary_when_capture_is_busy(self):
        primary=self.upstream();shadow=Shadow(primary,"http://127.0.0.1:1/pick",self.temp.name)
        self.shadows.append(shadow);proxy=self.server(handler(shadow))
        for _ in range(4):self.assertTrue(shadow.slots.acquire(blocking=False))
        result=post(proxy,{"image_b64":"eA==","instruction":"包含文字：乙","regions":[]},3)
        self.assertTrue(result["ok"]);self.assertEqual(shadow.dropped,1)
        for _ in range(4):shadow.slots.release()


if __name__=="__main__":unittest.main()
