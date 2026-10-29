"""Conservative near-duplicate check of tiles across existing image groups."""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

BOXES=[(0,34,220,254),(226,34,446,254),(452,34,672,254),
       (0,260,220,480),(226,260,446,480),(452,260,672,480)]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--rounds",required=True);p.add_argument("--images",required=True)
    p.add_argument("--audit",required=True);p.add_argument("--output",required=True)
    args=p.parse_args()
    rounds=json.loads(Path(args.rounds).read_text())
    audit=json.loads(Path(args.audit).read_text())
    ids=[];bits=[];small=[]
    for r in rounds:
        image=Image.open(Path(args.images)/r["image"]).convert("L")
        for i,box in enumerate(BOXES,1):
            tile=np.asarray(image.crop(box))
            reduced=cv2.resize(tile,(32,32)).astype(np.float32)
            dct=cv2.dct(reduced)[:8,:8].reshape(-1)
            bits.append(dct>np.median(dct[1:]))
            small.append(cv2.resize(tile,(64,64)).astype(np.float32))
            ids.append((r["id"],i))
    bits=np.asarray(bits)
    extra=[]
    for i in range(len(ids)):
        matches=np.flatnonzero(np.count_nonzero(bits[i+1:]!=bits[i],axis=1)<=3)+i+1
        for j in matches:
            if audit["group_by_round"][ids[i][0]]==audit["group_by_round"][ids[j][0]]:continue
            distance=float(np.abs(small[i]-small[j]).mean())
            if distance<=2:
                extra.append({"a":ids[i],"b":ids[j],"mean_gray_difference":distance})
    result={"protocol":"64-bit DCT Hamming <=3 AND 64x64 mean gray absolute difference <=2/255",
            "additional_cross_group_pairs":len(extra),"pairs":extra,
            "limitation":"heuristic check; does not prove all visual near-duplicates are excluded"}
    Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps({"additional_cross_group_pairs":len(extra)}))


if __name__=="__main__":main()
