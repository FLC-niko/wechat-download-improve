"""Observe text requests without changing the legacy pick returned to daemon.

Primary HTTP replies do not wait for candidate inference. A bounded worker pool
keeps private, deduplicated images and prediction metadata for human labeling.
Only the image, instruction and regions are retained; never tickets or tokens.
"""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
import time
import urllib.request

PICK_FIELDS=("ok","region_id","region_ids","method","model_version","target",
             "low_confidence","training_target_rounds","threshold","scores",
             "knn","class_probabilities","ocr","cost_ms","confidence","decision_margin")


def safe_pick(value):
    return {k:value[k] for k in PICK_FIELDS if k in value}


def post(url,payload,timeout):
    request=urllib.request.Request(url,data=json.dumps(payload).encode(),
                                  headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(request,timeout=timeout) as response:
        return json.load(response)


class Shadow:
    def __init__(self,primary,candidate,directory,timeout=20,review_queue=None):
        self.primary,self.candidate=primary,candidate
        self.directory=Path(directory)
        self.directory.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.directory.chmod(0o700)
        self.timeout=timeout
        self.review_queue=review_queue
        self.queue_lock=threading.Lock()
        self.pool=ThreadPoolExecutor(max_workers=2)
        self.slots=threading.BoundedSemaphore(4)
        self.dropped=0

    def submit(self,payload,primary,source="live",candidate_result=None,routing=None):
        if not self.slots.acquire(blocking=False):
            self.dropped+=1
            return
        # Deliberately whitelist fields instead of copying caller metadata.
        safe={k:payload[k] for k in ("image_b64","instruction","regions")}
        try:self.pool.submit(self.capture,safe,primary,source,candidate_result,routing)
        except BaseException:
            self.slots.release()
            raise

    def capture(self,payload,primary,source,candidate_result=None,routing=None):
        try:
            raw=base64.b64decode(payload["image_b64"],validate=True)
            image_hash=hashlib.sha256(raw).hexdigest()
            suffix=(json.dumps({"phase":routing["phase"],"source":source},sort_keys=True)
                    if routing else "")
            key=hashlib.sha256((image_hash+payload["instruction"]+suffix).encode()).hexdigest()
            image=self.directory/(key+".jpg")
            record=self.directory/(key+".json")
            if record.exists():return
            # No ground-truth is inferred from either model's prediction.
            metadata={"schema":1,"id":key,"captured_utc":time.strftime(
                "%Y-%m-%dT%H:%M:%SZ",time.gmtime()),"image":image.name,
                "image_sha256":image_hash,"instruction":payload["instruction"],
                "regions":payload["regions"],"primary":safe_pick(primary),
                "label_status":"human-label-required","source":source}
            if routing:metadata["routing"]=routing
            try:metadata["candidate"]=safe_pick(candidate_result if candidate_result is not None
                                               else post(self.candidate,payload,self.timeout))
            except Exception as exc:metadata["candidate_error"]=type(exc).__name__
            image_temporary=image.with_suffix(f".jpg.{threading.get_ident()}.tmp")
            image_temporary.write_bytes(raw)
            image_temporary.chmod(0o600);image_temporary.replace(image)
            temporary=record.with_suffix(f".json.{threading.get_ident()}.tmp")
            temporary.write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
            temporary.chmod(0o600);temporary.replace(record)
            if self.review_queue:
                from build_text_review_queue import build
                with self.queue_lock:build(self.directory,self.review_queue)
        finally:self.slots.release()


def handler(shadow):
    class Handler(BaseHTTPRequestHandler):
        def send_json(self,code,value):
            data=json.dumps(value,ensure_ascii=False).encode()
            self.send_response(code);self.send_header("Content-Type","application/json")
            self.send_header("Content-Length",str(len(data)));self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path!="/health":self.send_json(404,{"error":"not found"});return
            self.send_json(200,{"status":"ok","mode":"shadow",
                                "dropped_shadow_requests":shadow.dropped})

        def do_POST(self):
            if self.path not in ("/pick","/seq-pick"):
                self.send_json(404,{"error":"not found"});return
            try:
                length=int(self.headers.get("Content-Length","0"))
                if not 0<length<=4*1024*1024:
                    self.send_json(413,{"error":"invalid request size"});return
                payload=json.loads(self.rfile.read(length))
                endpoint=shadow.primary if self.path=="/pick" else shadow.primary.removesuffix("/pick")+"/seq-pick"
                result=post(endpoint,payload,30)
                if (self.path=="/pick" and "包含文字" in payload.get("instruction","")
                        and all(k in payload for k in ("image_b64","instruction","regions"))):
                    source="replay" if self.headers.get("X-Eink-Shadow-Source")=="replay" else "live"
                    try:shadow.submit(payload,result,source)
                    except Exception:shadow.dropped+=1
                self.send_json(200,result)
            except Exception as exc:self.send_json(502,{"ok":False,"error":type(exc).__name__})

        def log_message(self,*args):pass
    return Handler


def main():
    shadow=Shadow(os.environ.get("TEXT_PRIMARY_URL","http://127.0.0.1:9993/pick"),
                  os.environ.get("TEXT_CANDIDATE_URL","http://127.0.0.1:9994/pick"),
                  os.environ.get("TEXT_SHADOW_DIR","/home/topview/captcha/text-shadow"),
                  review_queue=os.environ.get("TEXT_REVIEW_QUEUE","/home/topview/captcha/text-review-queue.jsonl"))
    server=ThreadingHTTPServer(("127.0.0.1",int(os.environ.get("TEXT_SHADOW_PORT","9995"))),handler(shadow))
    try:server.serve_forever()
    finally:server.server_close();shadow.pool.shutdown(wait=True)


if __name__=="__main__":main()
