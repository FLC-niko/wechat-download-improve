"""Train and evaluate landscape-character fusion without answer features.

Run on the authorized training host. Data and resulting weights stay private.
Nested grouped evaluation keeps identical tiles together and fits each fusion
head using inner out-of-fold base-model predictions. Never import old OCR
pseudo-label rows over the human-corrected rows.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import random
import re
import shutil
import time

import cv2
import ddddocr
import numpy as np
from PIL import Image
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset
from torchvision import models, transforms
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupKFold

from text_features import FEATURE_NAMES, features, metrics

BOXES = [(0,34,220,254), (226,34,446,254), (452,34,672,254),
         (0,260,220,480), (226,260,446,480), (452,260,672,480)]
NORMALIZE = transforms.Normalize([.485,.456,.406], [.229,.224,.225])
EVAL_TRANSFORM = transforms.Compose([transforms.Resize((224,224)),
                                    transforms.ToTensor(), NORMALIZE])
TRAIN_TRANSFORM = transforms.Compose([
    transforms.RandomResizedCrop(224, scale=(.90,1.0), ratio=(.95,1.05)),
    transforms.ColorJitter(.20,.20,.15,.03),
    transforms.ToTensor(), NORMALIZE,
])


def save_json(path, value):
    path = Path(path)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    tmp.replace(path)


def encode(model, x):
    for name in ("conv1", "bn1", "relu", "maxpool", "layer1", "layer2",
                 "layer3", "layer4", "avgpool"):
        x = getattr(model, name)(x)
    return torch.flatten(x, 1)


class Tiles(Dataset):
    def __init__(self, items, classes, train):
        self.items, self.classes = items, classes
        self.transform = TRAIN_TRANSFORM if train else EVAL_TRANSFORM

    def __len__(self):
        return len(self.items)

    def __getitem__(self, index):
        item = self.items[index]
        return self.transform(item["image"]), self.classes[item["label"]]


def split_rounds(ids, groups, count):
    unique = len(set(groups[r] for r in ids))
    if unique < 2:
        raise ValueError("not enough independent image groups")
    splitter = GroupKFold(n_splits=min(count, unique))
    return [(sorted(ids[i] for i in tr), sorted(ids[i] for i in va))
            for tr, va in splitter.split(ids, groups=[groups[r] for r in ids])]


class Experiment:
    def __init__(self, args):
        self.args = args
        self.out = Path(args.output)
        self.out.mkdir(parents=True, exist_ok=False, mode=0o700)
        (self.out/"source").mkdir()
        for name in ("train_text_model.py", "text_features.py"):
            shutil.copy2(Path(__file__).parent/name,self.out/"source"/name)
        self.labels = json.loads((Path(args.data)/"labels.json").read_text())
        self.rounds = {r["id"]: r for r in json.loads(
            (Path(args.data)/"rounds.json").read_text()) if r["id"] in self.labels}
        self.ids = sorted(self.rounds)
        self.truth = {r: self.labels[r]["selected"] for r in self.ids}
        self.images, self.items, self.groups, self.ocrs = {}, [], {}, {}
        parent = {r: r for r in self.ids}

        def find(r):
            while parent[r] != r:
                parent[r] = parent[parent[r]]
                r = parent[r]
            return r

        seen, observations, unknown = {}, collections.defaultdict(list), []
        cached_ocr={}
        if args.ocr_cache_run:
            cache=Path(args.ocr_cache_run)
            contract=json.loads((cache/"candidate/fusion.json").read_text())["ocr"]
            if contract!={"engine":"ddddocr","beta":True,"preprocess":"lab-clahe-2.0-8x8"}:
                raise ValueError("OCR cache preprocessing mismatch")
            previous=json.loads((cache/"ocr.json").read_text())
            references=json.loads((cache/"candidate/training.json").read_text())["reference_tiles"]
            for reference in references:
                cached_ocr[reference["sha256"]]=previous[reference["round"]][reference["tile"]-1]
        ocr = ddddocr.DdddOcr(show_ad=False, beta=True)
        for rid in self.ids:
            r = self.rounds[rid]
            image = Image.open(Path(args.images)/r["image"]).convert("RGB")
            if image.size != (672,480):
                raise ValueError(f"unexpected grid size: {rid}: {image.size}")
            selected = set(self.truth[rid])
            if not selected.issubset(set(range(1,7))):
                raise ValueError(f"invalid labels: {rid}")
            tiles = [image.crop(b) for b in BOXES]
            self.images[rid] = tiles
            self.ocrs[rid] = []
            for i, tile in enumerate(tiles, 1):
                h = hashlib.sha256(tile.tobytes()).hexdigest()
                if h in seen:
                    parent[find(rid)] = find(seen[h])
                seen[h] = rid
                observations[(h,r["target"])].append((rid,i,i in selected))
                if h in cached_ocr:
                    self.ocrs[rid].append(cached_ocr[h])
                else:
                    lab = cv2.cvtColor(np.asarray(tile), cv2.COLOR_RGB2LAB)
                    lab[:,:,0] = cv2.createCLAHE(2.0,(8,8)).apply(lab[:,:,0])
                    processed = Image.fromarray(cv2.cvtColor(lab,cv2.COLOR_LAB2RGB))
                    self.ocrs[rid].append(ocr.classification(processed) or "")
                old = str(next((x for x in r["tiles"] if x["tile"] == i),
                               {}).get("ocr") or "").strip()
                label = r["target"] if i in selected else (
                    old if len(old) == 1 and old != r["target"] else None)
                if label:
                    self.items.append({"round":rid,"tile":i,"label":label,
                                       "image":tile,"hash":h})
                else:
                    unknown.append({"round":rid,"tile":i,"ocr":old})
        near_pairs=[]
        if args.near_duplicate_audit:
            near_pairs=json.loads(Path(args.near_duplicate_audit).read_text())["pairs"]
            for pair in near_pairs:
                a,b=pair["a"][0],pair["b"][0]
                if a not in parent or b not in parent:
                    raise ValueError("near-duplicate audit references another dataset")
                parent[find(a)]=find(b)
        self.groups = {r: find(r) for r in self.ids}
        conflicts = [v for v in observations.values()
                     if len({x[2] for x in v}) > 1]
        self.classes = sorted({x["label"] for x in self.items} |
                              {r["target"] for r in self.rounds.values()})
        self.c2i = {c:i for i,c in enumerate(self.classes)}
        self.init = torch.load(args.initialization,map_location="cpu",weights_only=True)
        self.init = {k:v for k,v in self.init.items() if not k.startswith("fc.")}
        manifest = {"rounds":len(self.ids),"tiles":6*len(self.ids),
                    "classifier_tiles":len(self.items),
                    "unknown_negative_characters":unknown,
                    "independent_groups":len(set(self.groups.values())),
                    "duplicate_tile_instances":6*len(self.ids)-len(seen),
                    "annotation_conflicts":conflicts,
                    "additional_near_duplicate_pairs":len(near_pairs),
                    "group_by_round":self.groups,
                    "classes":self.classes,"feature_names":FEATURE_NAMES,
                    "source_sha256":{name:hashlib.sha256(
                        (Path(args.data)/name).read_bytes()).hexdigest()
                        for name in ("labels.json","rounds.json")},
                    "initialization_sha256":hashlib.sha256(
                        Path(args.initialization).read_bytes()).hexdigest(),
                    "arguments":vars(args)}
        save_json(self.out/"data_audit.json",manifest)
        if conflicts:
            raise ValueError("conflicting human labels; see data_audit.json")
        save_json(self.out/"ocr.json",self.ocrs)
        self.fits = 0

    def base(self, train_ids, evaluate_ids, tag, persist=False):
        self.fits += 1
        seed = self.args.seed + self.fits
        random.seed(seed);np.random.seed(seed);torch.manual_seed(seed)
        started = time.time()
        allowed = set(train_ids)
        # One classifier label per pixel-identical tile; human corrections won
        # before this point. Discard conflicting weak negative class names.
        by_hash = collections.defaultdict(list)
        for x in self.items:
            if x["round"] in allowed:
                by_hash[x["hash"]].append(x)
        items = [v[0] for v in by_hash.values() if len({x["label"] for x in v}) == 1]
        model = models.resnet50(weights=None)
        model.fc = nn.Sequential(nn.Dropout(.3),nn.Linear(2048,len(self.classes)))
        model.load_state_dict(self.init,strict=False)
        for n,p in model.named_parameters():
            p.requires_grad = n.startswith("layer4") or n.startswith("fc")
        model.to(self.args.device)
        counts = collections.Counter(x["label"] for x in items)
        weights = torch.tensor([1/np.sqrt(max(1,counts[c])) for c in self.classes],
                               dtype=torch.float32,device=self.args.device)
        weights /= weights.mean()
        criterion = nn.CrossEntropyLoss(weight=weights)
        optimizer = torch.optim.AdamW([
            {"params":model.layer4.parameters(),"lr":3e-4},
            {"params":model.fc.parameters(),"lr":self.args.head_learning_rate},
        ],weight_decay=1e-4)
        loader = DataLoader(Tiles(items,self.c2i,True),batch_size=self.args.batch_size,
                            shuffle=True,num_workers=2,drop_last=False)
        # Adapting running statistics uses training images only. This option is
        # explicit because the calligraphy-to-landscape domain shift is large.
        for epoch in range(self.args.epochs):
            if self.args.adapt_batchnorm:
                model.train()
            else:
                model.eval();model.layer4.train();model.fc.train()
            total = 0.
            for x,y in loader:
                x=x.to(self.args.device);y=y.to(self.args.device)
                optimizer.zero_grad(set_to_none=True)
                loss=criterion(model(x),y);loss.backward();optimizer.step()
                total += loss.item()
            print(json.dumps({"tag":tag,"epoch":epoch+1,
                              "loss":round(total/max(1,len(loader)),4)}),flush=True)
        model.eval()
        reference = []
        with torch.inference_mode():
            for start in range(0,len(items),32):
                batch=torch.stack([EVAL_TRANSFORM(x["image"])
                                   for x in items[start:start+32]]).to(self.args.device)
                reference.append(nn.functional.normalize(encode(model,batch),dim=1).cpu())
            reference=torch.cat(reference)
            output={}
            for rid in evaluate_ids:
                target=self.rounds[rid]["target"]
                x=torch.stack([EVAL_TRANSFORM(t) for t in self.images[rid]]).to(self.args.device)
                v=encode(model,x)
                prob=torch.softmax(model.fc(v),1)[:,self.c2i[target]].cpu().numpy()
                v=nn.functional.normalize(v,dim=1).cpu()
                indexes=[i for i,item in enumerate(items) if item["label"]==target]
                knn=(reference[indexes]@v.T).max(0).values.numpy() if indexes else np.zeros(6)
                output[rid]={"target":target,"ocr":self.ocrs[rid],
                             "knn":knn.tolist(),"prob":prob.tolist(),
                             "training_target_rounds":sum(
                                 self.rounds[r]["target"]==target for r in train_ids)}
        if persist:
            dest=self.out/tag;dest.mkdir(mode=0o700)
            torch.save({"state_dict":{k:v.cpu() for k,v in model.state_dict().items()},
                        "classes":self.classes},dest/"model.pt")
            np.savez_compressed(dest/"references.npz",features=reference.numpy(),
                                labels=np.asarray([x["label"] for x in items]))
            save_json(dest/"training.json",{
                "rounds":train_ids,"target_rounds":dict(collections.Counter(
                    self.rounds[r]["target"] for r in train_ids)),
                "reference_tiles":[{"round":x["round"],"tile":x["tile"],
                                    "label":x["label"],"sha256":x["hash"]} for x in items]})
        del model,optimizer
        if self.args.device.startswith("cuda"):torch.cuda.empty_cache()
        print(json.dumps({"completed":tag,"training_tiles":len(items),
                          "seconds":round(time.time()-started,1)}),flush=True)
        save_json(self.out/f"{tag}-scores.json",output)
        return output

    def xy(self,scores,ids):
        return (np.concatenate([features(scores[r]["target"],scores[r]["ocr"],
                    scores[r]["knn"],scores[r]["prob"],
                    scores[r]["training_target_rounds"]) for r in ids]),
                np.asarray([int(i in self.truth[r]) for r in ids for i in range(1,7)]))

    def head(self,scores,ids):
        x,y=self.xy(scores,ids)
        clf=LogisticRegression(max_iter=2000,C=1,class_weight="balanced").fit(x,y)
        p=clf.predict_proba(x)[:,1].reshape(-1,6)
        # Threshold selection occurs only within the outer training set.
        choices=[]
        truth={r:self.truth[r] for r in ids}
        for threshold in np.arange(.25,.751,.05):
            pred={r:[i+1 for i,v in enumerate(row) if v>=threshold]
                  for r,row in zip(ids,p)}
            m=metrics(pred,truth)
            choices.append((m["exact"],m["round_macro_f1"],-abs(threshold-.5),float(threshold)))
        threshold=max(choices)[-1]
        return clf,threshold

    def run(self):
        if self.args.temporal_date:
            test_ids=[r for r in self.ids if self.args.temporal_date in r]
            held_groups={self.groups[r] for r in test_ids}
            train_ids=[r for r in self.ids if self.groups[r] not in held_groups
                       and re.search(r"\d{4}-\d{2}-\d{2}",r).group(0)<self.args.temporal_date]
            if not test_ids or not train_ids:
                raise ValueError("temporal holdout requires nonempty train and test")
            outer=[(train_ids,test_ids)]
        else:
            outer=split_rounds(self.ids,self.groups,5)
        all_scores,all_predictions,fold_results={},{},[]
        for fold,(train_ids,test_ids) in enumerate(outer):
            inner_scores={}
            for inner,(tr,va) in enumerate(split_rounds(train_ids,self.groups,3)):
                inner_scores.update(self.base(tr,va,f"outer{fold}-inner{inner}"))
            clf,threshold=self.head(inner_scores,sorted(inner_scores))
            test_scores=self.base(train_ids,test_ids,f"fold-{fold}",persist=True)
            x,_=self.xy(test_scores,test_ids)
            probabilities=clf.predict_proba(x)[:,1].reshape(-1,6)
            predictions={rid:[i+1 for i,v in enumerate(row) if v>=threshold]
                         for rid,row in zip(test_ids,probabilities)}
            all_scores.update(test_scores);all_predictions.update(predictions)
            head={"feature_names":FEATURE_NAMES,"coefficients":clf.coef_[0].tolist(),
                  "intercept":float(clf.intercept_[0]),"threshold":threshold,
                  "ocr":{"engine":"ddddocr","beta":True,"preprocess":"lab-clahe-2.0-8x8"}}
            save_json(self.out/f"fold-{fold}"/"fusion.json",head)
            fold_result={"fold":fold,"threshold":threshold,"train_rounds":train_ids,
                         "test_rounds":test_ids,
                         "metrics":metrics(predictions,{r:self.truth[r] for r in test_ids})}
            fold_results.append(fold_result)
            save_json(self.out/"progress.json",fold_results)
            save_json(self.out/"oof_scores.json",all_scores)
            save_json(self.out/"oof_predictions.json",all_predictions)
            print(json.dumps({"fold_done":fold,"exact":fold_result["metrics"]["exact"]}),flush=True)
        evaluation_ids=sorted(all_scores)
        evaluation_truth={r:self.truth[r] for r in evaluation_ids}
        baselines={}
        baselines["clahe_ocr"]=metrics({r:[i+1 for i,t in enumerate(self.ocrs[r])
            if self.rounds[r]["target"] in t] for r in evaluation_ids},evaluation_truth)
        for threshold in (.65,.7):
            baselines[f"ocr_union_knn_{threshold}"]=metrics({r:[i+1 for i in range(6)
                if self.rounds[r]["target"] in self.ocrs[r][i] or d["knn"][i]>=threshold]
                for r,d in all_scores.items()},evaluation_truth)
        protocol=(f"temporal holdout {self.args.temporal_date}; duplicate groups excluded; 3 inner folds"
                  if self.args.temporal_date else
                  "5 outer duplicate-group folds; 3 inner folds for fusion training")
        evaluation={"protocol":protocol,
                    "status":"offline-grouped-evaluation; prospective-validation-pending",
                    "fusion":metrics(all_predictions,evaluation_truth),"baselines":baselines,
                    "folds":fold_results}
        save_json(self.out/"evaluation.json",evaluation)
        if self.args.temporal_date:
            print(json.dumps({"done":str(self.out),"exact":evaluation["fusion"]["exact"],
                              "correct":evaluation["fusion"]["correct"],
                              "rounds":len(evaluation_ids)}),flush=True)
            return
        clf,threshold=self.head(all_scores,self.ids)
        self.base(self.ids,[],"candidate",persist=True)
        head={"feature_names":FEATURE_NAMES,"coefficients":clf.coef_[0].tolist(),
              "intercept":float(clf.intercept_[0]),"threshold":threshold,
              "ocr":{"engine":"ddddocr","beta":True,"preprocess":"lab-clahe-2.0-8x8"}}
        save_json(self.out/"candidate/fusion.json",head)
        save_json(self.out/"candidate/evaluation.json",evaluation)
        save_json(self.out/"candidate/data_audit.json",json.loads((self.out/"data_audit.json").read_text()))
        manifest={"schema":1,"feature_names":FEATURE_NAMES,
                  "accuracy_status":"offline-grouped-evaluation; prospective-validation-pending",
                  "files":{p.name:hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in (self.out/"candidate").iterdir() if p.is_file()}}
        save_json(self.out/"candidate/manifest.json",manifest)
        print(json.dumps({"done":str(self.out),"exact":evaluation["fusion"]["exact"],
                          "correct":evaluation["fusion"]["correct"],
                          "threshold":threshold}),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data",required=True)
    parser.add_argument("--images",required=True)
    parser.add_argument("--initialization",required=True)
    parser.add_argument("--output",required=True)
    parser.add_argument("--epochs",type=int,default=8)
    parser.add_argument("--batch-size",type=int,default=24)
    parser.add_argument("--seed",type=int,default=20261026)
    parser.add_argument("--device",default="cuda:0")
    parser.add_argument("--adapt-batchnorm",action="store_true")
    parser.add_argument("--head-learning-rate",type=float,default=3e-4)
    parser.add_argument("--temporal-date",help="e.g. 2026-10-24; evaluation only, no full-data candidate")
    parser.add_argument("--near-duplicate-audit",help="conservatively merge additional near-duplicate image groups")
    parser.add_argument("--ocr-cache-run",help="reuse OCR only by verified RGB tile hash and matching preprocessing contract")
    args=parser.parse_args()
    torch.set_num_threads(4)
    if args.device.startswith("cuda"):
        torch.cuda.set_per_process_memory_fraction(.30,0)
    Experiment(args).run()


if __name__=="__main__":
    main()
