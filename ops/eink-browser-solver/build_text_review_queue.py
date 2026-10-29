"""Rank private shadow samples for human labeling; never infer ground truth."""
import argparse
import json
import os
from pathlib import Path


def build(samples,output,limit=100):
    rows=[]
    for path in Path(samples).glob("*.json"):
        d=json.loads(path.read_text())
        if d.get("source")!="live" or d.get("label_status")!="human-label-required":continue
        old=set(d.get("primary",{}).get("region_ids") or [])
        candidate=d.get("candidate",{})
        new=set(candidate.get("region_ids") or [])
        reasons=[];priority=0
        if candidate.get("training_target_rounds",0)==0:
            priority+=100;reasons.append("target absent from labeled training rounds")
        if old!=new:priority+=50;reasons.append("old and candidate disagree")
        margin=candidate.get("decision_margin",1)
        if margin<.1:priority+=int(30*(.1-margin)/.1);reasons.append("decision near threshold")
        if d.get("candidate_error"):priority+=20;reasons.append("candidate inference failed")
        rows.append({"id":d["id"],"instruction":d["instruction"],
                     "image":str(path.parent/d["image"]),"metadata":str(path),
                     "priority":priority,"reasons":reasons,
                     "label_status":"human-label-required"})
    rows.sort(key=lambda r:(-r["priority"],r["id"]))
    output=Path(output)
    temporary=output.with_name(output.name+f".tmp-{os.getpid()}")
    temporary.write_text("".join(json.dumps(r,ensure_ascii=False)+"\n" for r in rows[:limit]))
    temporary.chmod(0o600);temporary.replace(output)
    return {"pending_live_samples":len(rows),"queued":min(len(rows),limit),"output":str(output)}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--samples",required=True);p.add_argument("--output",required=True)
    p.add_argument("--limit",type=int,default=100)
    args=p.parse_args()
    print(json.dumps(build(args.samples,args.output,args.limit)))


if __name__=="__main__":main()
