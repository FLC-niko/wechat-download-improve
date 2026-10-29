"""Versioned, local-only landscape-character model inference.

Model bundles are private artifacts, never fetched automatically. Checksums are
verified before loading. No scikit-learn dependency is needed by the service.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import threading
import time

import numpy as np
from PIL import Image

from text_features import FEATURE_NAMES, features, predict_linear


class TextPredictor:
    def __init__(self, bundle, threads=4, device="cpu", gpu_memory_fraction=.1):
        import torch
        from torch import nn
        from torchvision import models, transforms
        import ddddocr

        if device not in ("cpu", "cuda:0"):
            raise ValueError("text inference supports cpu or isolated cuda:0 only")
        self.device = torch.device(device)
        if self.device.type == "cuda":
            if not 0 < gpu_memory_fraction <= .2:
                raise ValueError("GPU inference memory fraction must be in (0, .2]")
            # Fusion contains discrete rank/threshold features: TF32 rounding
            # can change them even when class probabilities look very close.
            torch.backends.cuda.matmul.allow_tf32 = False
            torch.backends.cudnn.allow_tf32 = False
            torch.backends.cudnn.benchmark = False
            torch.backends.cudnn.deterministic = True
            torch.set_float32_matmul_precision("highest")
            torch.cuda.set_per_process_memory_fraction(gpu_memory_fraction, self.device)
        self.bundle = Path(bundle).resolve(strict=True)
        manifest = json.loads((self.bundle/"manifest.json").read_text())
        if manifest.get("schema") != 1:
            raise ValueError("unsupported text bundle schema")
        required = {"model.pt", "references.npz", "training.json", "fusion.json"}
        files = manifest.get("files", {})
        if not required.issubset(files):
            raise ValueError("incomplete text bundle")
        for name, expected in files.items():
            if Path(name).name != name:
                raise ValueError("bundle manifest paths must be plain filenames")
            actual = hashlib.sha256((self.bundle/name).read_bytes()).hexdigest()
            if actual != expected:
                raise ValueError(f"text artifact checksum mismatch: {name}")
        self.version = hashlib.sha256((self.bundle/"manifest.json").read_bytes()).hexdigest()[:16]
        self.head = json.loads((self.bundle/"fusion.json").read_text())
        if tuple(self.head["feature_names"]) != FEATURE_NAMES:
            raise ValueError("text feature order mismatch")
        if self.head["ocr"] != {"engine":"ddddocr", "beta":True,
                                "preprocess":"lab-clahe-2.0-8x8"}:
            raise ValueError("unsupported OCR preprocessing contract")
        self.training = json.loads((self.bundle/"training.json").read_text())
        torch.set_num_threads(threads)
        checkpoint = torch.load(self.bundle/"model.pt",map_location="cpu",weights_only=True)
        self.classes = {c:i for i,c in enumerate(checkpoint["classes"])}
        self.model = models.resnet50(weights=None)
        self.model.fc = nn.Sequential(nn.Dropout(.3), nn.Linear(2048,len(self.classes)))
        self.model.load_state_dict(checkpoint["state_dict"])
        self.model.to(self.device).eval()
        with np.load(self.bundle/"references.npz",allow_pickle=False) as refs:
            self.references = torch.from_numpy(refs["features"].copy())
            self.reference_labels = refs["labels"].copy()
        if self.references.ndim != 2 or self.references.shape[1] != 2048:
            raise ValueError("invalid reference feature shape")
        if len(self.reference_labels) != len(self.references):
            raise ValueError("invalid reference labels")
        self.transform = transforms.Compose([
            transforms.Resize((224,224)),transforms.ToTensor(),
            transforms.Normalize([.485,.456,.406],[.229,.224,.225]),
        ])
        self.ocr = ddddocr.DdddOcr(show_ad=False,beta=True)
        self.lock = threading.Lock()

    def pick(self, image, regions, target):
        import cv2
        import torch

        started = time.monotonic()
        if len(regions) != 6 or len({r["id"] for r in regions}) != 6:
            raise ValueError("text model requires six distinct regions")
        crops = []
        for region in regions:
            x1,y1,x2,y2 = map(int,region["range"])
            if not (0<=x1<x2<=image.width and 0<=y1<y2<=image.height):
                raise ValueError("text crop outside image")
            crops.append(image.crop((x1,y1,x2,y2)).convert("RGB"))
        with self.lock, torch.inference_mode():
            texts = []
            for crop in crops:
                lab=cv2.cvtColor(np.asarray(crop),cv2.COLOR_RGB2LAB)
                lab[:,:,0]=cv2.createCLAHE(2.0,(8,8)).apply(lab[:,:,0])
                processed=Image.fromarray(cv2.cvtColor(lab,cv2.COLOR_LAB2RGB))
                texts.append(self.ocr.classification(processed) or "")
            x=torch.stack([self.transform(crop) for crop in crops]).to(self.device)
            for name in ("conv1","bn1","relu","maxpool","layer1","layer2",
                         "layer3","layer4","avgpool"):
                x=getattr(self.model,name)(x)
            vectors=torch.flatten(x,1)
            if target in self.classes:
                probabilities=torch.softmax(self.model.fc(vectors),1)[:,self.classes[target]].cpu().numpy()
            else:
                probabilities=np.zeros(6)
            vectors=torch.nn.functional.normalize(vectors,dim=1).cpu()
            mask=np.flatnonzero(self.reference_labels==target)
            knn=(self.references[mask]@vectors.T).max(0).values.numpy() if len(mask) else np.zeros(6)
            support=int(self.training["target_rounds"].get(target,0))
            x=features(target,texts,knn,probabilities,support)
            scores=predict_linear(x,self.head["coefficients"],self.head["intercept"])
            threshold=self.head["threshold"]
            # A character may occur as a negative class label even when it was
            # never the instruction target. Keep that usable reference signal;
            # targets entirely outside the artifact vocabulary use OCR only.
            positions=[i for i,v in enumerate(scores) if v>=threshold] if target in self.classes else [
                i for i,text in enumerate(texts) if target and target in text]
        selected=[regions[i]["id"] for i in positions]
        return {"ok":True,"region_id":selected[0] if selected else None,
                "region_ids":selected,"method":"text-fusion-v2",
                "target":target,"model_version":self.version,
                "low_confidence":not bool(selected),
                "training_target_rounds":support,
                "threshold":threshold,
                "decision_margin":float(np.min(np.abs(scores-threshold))),
                "scores":[float(v) for v in scores],
                "knn":[float(v) for v in knn],
                "class_probabilities":[float(v) for v in probabilities],
                "ocr":[{"id":r["id"],"text":t} for r,t in zip(regions,texts)],
                "cost_ms":round((time.monotonic()-started)*1000,1)}
