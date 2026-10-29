"""Capture private inference-parity cases or replay them against loopback HTTP.

These cases may be training images: this checks serving parity and latency,
never estimates generalization accuracy. No challenge is submitted externally.
"""
import argparse
import base64
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request

BOXES=[(0,34,220,254),(226,34,446,254),(452,34,672,254),
       (0,260,220,480),(226,260,446,480),(452,260,672,480)]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--cases",required=True)
    p.add_argument("--capture",action="store_true")
    p.add_argument("--bundle");p.add_argument("--rounds");p.add_argument("--images")
    p.add_argument("--count",type=int,default=10)
    p.add_argument("--url",default="http://127.0.0.1:9994/pick")
    p.add_argument("--report")
    args=p.parse_args()
    cases=Path(args.cases)
    if args.capture:
        from PIL import Image
        from text_runtime import TextPredictor
        predictor=TextPredictor(args.bundle)
        rounds=json.loads(Path(args.rounds).read_text())
        # Spread cases across dates/targets rather than selecting easy matches.
        rounds=sorted(rounds,key=lambda r:r["id"])
        stride=max(1,len(rounds)//args.count)
        selected=rounds[::stride][:args.count]
        with cases.open("x") as stream:
            for r in selected:
                path=Path(args.images)/r["image"]
                payload={"image_b64":base64.b64encode(path.read_bytes()).decode(),
                         "instruction":"包含文字："+r["target"],
                         "regions":[{"id":i+1,"range":box} for i,box in enumerate(BOXES)]}
                expected=predictor.pick(Image.open(path).convert("RGB"),payload["regions"],r["target"])
                stream.write(json.dumps({"round":r["id"],"request":payload,
                                         "expected":expected},ensure_ascii=False)+"\n")
        cases.chmod(0o600)
        print(json.dumps({"captured":len(selected),"model_version":predictor.version}))
        return
    url=urllib.parse.urlparse(args.url)
    if url.hostname not in ("127.0.0.1","localhost","::1"):
        raise ValueError("replay accepts loopback services only")
    results=[]
    for line in cases.read_text().splitlines():
        case=json.loads(line);started=time.monotonic()
        data=json.dumps(case["request"]).encode()
        req=urllib.request.Request(args.url,data=data,headers={"Content-Type":"application/json"})
        with urllib.request.urlopen(req,timeout=60) as response:actual=json.load(response)
        expected=case["expected"]
        score_error=max(abs(a-b) for a,b in zip(actual["scores"],expected["scores"]))
        match=(actual["ok"] and actual["region_ids"]==expected["region_ids"]
               and actual["model_version"]==expected["model_version"]
               and actual["ocr"]==expected["ocr"] and score_error<1e-3)
        results.append({"round":case["round"],"match":match,
                        "maximum_score_error":score_error,
                        "cost_ms":round((time.monotonic()-started)*1000,1)})
    report={"purpose":"inference parity only; not generalization accuracy",
            "cases":len(results),"passed":sum(r["match"] for r in results),"results":results}
    if args.report:Path(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps(report,ensure_ascii=False))
    if not all(r["match"] for r in results):raise SystemExit(1)


if __name__=="__main__":main()
