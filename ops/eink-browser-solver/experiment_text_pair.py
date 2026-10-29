"""Offline target-glyph verifier, evaluated on a date containing unseen targets.

Frozen calligraphy embeddings + shared image/glyph projection. Human positive
tiles additionally supervise cross-character matching; negative tile character
names are never assumed. Thresholds are selected on target-held-out inner
folds. This experiment does not modify serving bundles.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import re
import shutil

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import torch
from torch import nn
from torchvision import models
from sklearn.model_selection import GroupKFold

from train_text_model import BOXES, EVAL_TRANSFORM, encode, save_json
from text_features import metrics


class PairHead(nn.Module):
    def __init__(self):
        super().__init__()
        self.image=nn.Sequential(nn.Linear(2048,128),nn.LayerNorm(128),nn.GELU(),nn.Linear(128,64))
        self.glyph=nn.Sequential(nn.Linear(2048,128),nn.LayerNorm(128),nn.GELU(),nn.Linear(128,64))
        self.scale=nn.Parameter(torch.tensor(2.3))
        self.bias=nn.Parameter(torch.tensor(-1.))

    def vectors(self,x,g):
        return nn.functional.normalize(self.image(x),dim=-1),nn.functional.normalize(self.glyph(g),dim=-1)

    def forward(self,x,g):
        a,b=self.vectors(x,g)
        return (a*b).sum(-1)*self.scale.exp().clamp(max=30)+self.bias


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data",required=True);p.add_argument("--images",required=True)
    p.add_argument("--initialization",required=True);p.add_argument("--fonts",required=True)
    p.add_argument("--audit",required=True);p.add_argument("--ocr",required=True)
    p.add_argument("--output",required=True);p.add_argument("--date",default="2026-10-24")
    p.add_argument("--epochs",type=int,default=180)
    args=p.parse_args()
    torch.set_num_threads(4);torch.cuda.set_per_process_memory_fraction(.30,0)
    torch.manual_seed(20261026);random.seed(20261026);np.random.seed(20261026)
    out=Path(args.output);out.mkdir(mode=0o700,parents=True,exist_ok=False)
    shutil.copy2(__file__,out/Path(__file__).name)
    labels=json.loads((Path(args.data)/"labels.json").read_text())
    rounds={r["id"]:r for r in json.loads((Path(args.data)/"rounds.json").read_text()) if r["id"] in labels}
    audit=json.loads(Path(args.audit).read_text());ocrs=json.loads(Path(args.ocr).read_text())
    ids=sorted(rounds);chars=sorted({r["target"] for r in rounds.values()});c2i={c:i for i,c in enumerate(chars)}
    encoder=models.resnet50(weights=None)
    sd=torch.load(args.initialization,map_location="cpu",weights_only=True)
    encoder.load_state_dict({k:v for k,v in sd.items() if not k.startswith("fc.")},strict=False)
    encoder.eval().cuda()
    tile_vectors=[];tile_rounds=[];tile_targets=[];ys=[]
    with torch.inference_mode():
        for rid in ids:
            image=Image.open(Path(args.images)/rounds[rid]["image"]).convert("RGB")
            x=torch.stack([EVAL_TRANSFORM(image.crop(b)) for b in BOXES]).cuda()
            tile_vectors.append(encode(encoder,x).cpu())
            tile_rounds += [rid]*6;tile_targets += [c2i[rounds[rid]["target"]]]*6
            ys += [int(i in labels[rid]["selected"]) for i in range(1,7)]
        fonts=[]
        candidates=sorted(Path(args.fonts).glob("*.ttf"))+[
            Path("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
            Path("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")]
        rejected=[]
        for font in candidates:
            try:
                ImageFont.truetype(str(font),180)
                fonts.append(font)
            except OSError:
                rejected.append(str(font))
        save_json(out/"font_audit.json",{"accepted":[str(f) for f in fonts],"rejected":rejected})
        if not fonts:raise ValueError("no target fonts")
        (out/"fonts").mkdir()
        for font in fonts:shutil.copy2(font,out/"fonts"/font.name)
        font_vectors=[]
        for char in chars:
            variants=[]
            for font in fonts:
                image=Image.new("RGB",(224,224),"white")
                draw=ImageDraw.Draw(image);f=ImageFont.truetype(str(font),180)
                box=draw.textbbox((0,0),char,font=f)
                draw.text(((224-(box[2]-box[0]))/2-box[0],
                           (224-(box[3]-box[1]))/2-box[1]),char,font=f,fill="black")
                variants.append(EVAL_TRANSFORM(image))
            font_vectors.append(encode(encoder,torch.stack(variants).cuda()).cpu().mean(0))
    del encoder;torch.cuda.empty_cache()
    x=torch.cat(tile_vectors).clone().cuda();g=torch.stack(font_vectors).clone().cuda()
    # Keep feature magnitudes bounded without fitting statistics to test images.
    x=nn.functional.normalize(x,dim=1);g=nn.functional.normalize(g,dim=1)
    targets=torch.tensor(tile_targets,device="cuda");y=torch.tensor(ys,dtype=torch.float32,device="cuda")
    truth={r:labels[r]["selected"] for r in ids}

    def fit(train_ids,test_ids,tag):
        train=set(train_ids);test=set(test_ids)
        tr=torch.tensor([i for i,r in enumerate(tile_rounds) if r in train],device="cuda")
        te=torch.tensor([i for i,r in enumerate(tile_rounds) if r in test],device="cuda")
        train_chars=sorted({c2i[rounds[r]["target"]] for r in train_ids})
        train_chars_tensor=torch.tensor(train_chars,device="cuda")
        local={c:i for i,c in enumerate(train_chars)}
        m=PairHead().cuda();opt=torch.optim.AdamW(m.parameters(),lr=.001,weight_decay=.01)
        for epoch in range(args.epochs):
            m.train()
            perm=tr[torch.randperm(len(tr),device="cuda")]
            for batch in perm.split(128):
                opt.zero_grad(set_to_none=True)
                logits=m(x[batch],g[targets[batch]])
                loss=nn.functional.binary_cross_entropy_with_logits(logits,y[batch])
                positives=batch[y[batch]>0]
                if len(positives):
                    a,b=m.vectors(x[positives],g[train_chars_tensor])
                    ce_targets=torch.tensor([local[int(c)] for c in targets[positives]],device="cuda")
                    loss=loss+.5*nn.functional.cross_entropy(a@b.T*m.scale.exp().clamp(max=30),ce_targets)
                loss.backward();opt.step()
        m.eval()
        with torch.inference_mode():probs=torch.sigmoid(m(x[te],g[targets[te]])).cpu().numpy()
        scores={};offset=0
        for rid in ids:
            if rid in test:scores[rid]=probs[offset:offset+6].tolist();offset+=6
        torch.save({"state_dict":{k:v.cpu() for k,v in m.state_dict().items()},
                    "train_rounds":train_ids},out/f"{tag}.pt")
        print(json.dumps({"completed":tag,"train_rounds":len(train),"test_rounds":len(test)}),flush=True)
        return scores

    test=[r for r in ids if args.date in r]
    held_groups={audit["group_by_round"][r] for r in test}
    train=[r for r in ids if audit["group_by_round"][r] not in held_groups
           and re.search(r"\d{4}-\d{2}-\d{2}",r).group(0)<args.date]
    inner={}
    # Simulate unseen targets when fitting thresholds, not just unseen rounds.
    splitter=GroupKFold(n_splits=3)
    for i,(tr,va) in enumerate(splitter.split(train,groups=[rounds[r]["target"] for r in train])):
        va_ids=[train[j] for j in va]
        va_groups={audit["group_by_round"][r] for r in va_ids}
        tr_ids=[train[j] for j in tr if audit["group_by_round"][train[j]] not in va_groups]
        inner.update(fit(tr_ids,va_ids,f"inner-{i}"))
    def picks(scores,threshold,union):
        return {r:[i+1 for i,v in enumerate(values) if v>=threshold or
                   (union and rounds[r]["target"] in ocrs[r][i])] for r,values in scores.items()}
    selected=[]
    inner_truth={r:truth[r] for r in inner}
    for union in (False,True):
        options=[]
        for threshold in np.arange(.1,.901,.05):
            result=metrics(picks(inner,threshold,union),inner_truth)
            options.append((result["exact"],result["round_macro_f1"],float(threshold)))
        selected.append((union,max(options)[-1]))
    outer_scores=fit(train,test,"temporal")
    results={"protocol":"unseen-target inner folds; date holdout; duplicate groups excluded",
             "test_targets_seen_in_training":sorted({rounds[r]["target"] for r in train}&
                                                       {rounds[r]["target"] for r in test}),
             "thresholds":selected,"experiments":{}}
    for union,threshold in selected:
        results["experiments"]["pair_union_ocr" if union else "pair_only"]={
            "threshold":threshold,"metrics":metrics(picks(outer_scores,threshold,union),
                                                       {r:truth[r] for r in test})}
    results["experiments"]["ocr"]={"metrics":metrics({r:[i+1 for i,t in enumerate(ocrs[r])
        if rounds[r]["target"] in t] for r in test},{r:truth[r] for r in test})}
    save_json(out/"evaluation.json",results);save_json(out/"scores.json",outer_scores)
    save_json(out/"source_manifest.json",{"arguments":vars(args),
        "labels_sha256":hashlib.sha256((Path(args.data)/"labels.json").read_bytes()).hexdigest(),
        "initialization_sha256":hashlib.sha256(Path(args.initialization).read_bytes()).hexdigest()})
    print(json.dumps({name:d["metrics"]["exact"] for name,d in results["experiments"].items()}),flush=True)


if __name__=="__main__":main()
