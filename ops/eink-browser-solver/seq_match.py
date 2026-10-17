"""Icon-sequence ("依次点击：") matcher for the E-Ink captcha solver.

Adapted from the multi-scale template-matching pipeline of
https://github.com/adnogpu/TXCaptcha (image/image_solver.py, commit fcbf69b),
reworked for this fixture:

  * sprite strip: black silhouettes with noise interference (anti-OCR)
  * main image : colored outline icons on a photo background (672x480)

Pipeline
  1. sprite  -> glyph silhouettes via adaptive dark/low-chroma threshold,
                fragment merging by bbox proximity
  2. search  -> filled binary mask of colored strokes (adaptive saturation
                quantile) plus dark strokes (local-contrast quantile)
  3. match   -> multi-scale / multi-rotation TM_CCOEFF_NORMED fused with
                recall/precision, top-N suppressed maxima per transform
  4. select  -> globally best non-overlapping combination (DFS over tops)

solve_seq() returns click points in image-pixel space, in sprite order.
"""

from __future__ import annotations

import math

import cv2
import numpy as np

MAX_TOPS = 10
ANGLE_RANGE = range(-40, 41, 10)
SCALES = (0.8, 1.0, 1.2, 1.4, 1.6, 1.9, 2.2)


def _components(mask: np.ndarray, min_area: int) -> list[dict]:
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    out = []
    for i in range(1, n):
        x, y, w, h, area = [int(v) for v in stats[i]]
        if area < min_area:
            continue
        m = ((lab[y:y + h, x:x + w] == i) * 255).astype(np.uint8)
        cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        filled = np.zeros_like(m)
        cv2.drawContours(filled, cnts, -1, 255, -1)
        out.append(dict(x=x, y=y, w=w, h=h, area=area, mask=filled))
    return out


def _merge_nearby(comps: list[dict], gap: int) -> list[dict]:
    parent = list(range(len(comps)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for i in range(len(comps)):
        for j in range(i + 1, len(comps)):
            a, b = comps[i], comps[j]
            if (a["x"] - gap < b["x"] + b["w"] and b["x"] - gap < a["x"] + a["w"]
                    and a["y"] - gap < b["y"] + b["h"] and b["y"] - gap < a["y"] + a["h"]):
                ra, rb = find(i), find(j)
                if ra != rb:
                    parent[rb] = ra
    groups = {}
    for i in range(len(comps)):
        groups.setdefault(find(i), []).append(comps[i])
    merged = []
    for gset in groups.values():
        x1 = min(c["x"] for c in gset)
        y1 = min(c["y"] for c in gset)
        x2 = max(c["x"] + c["w"] for c in gset)
        y2 = max(c["y"] + c["h"] for c in gset)
        m = np.zeros((y2 - y1, x2 - x1), np.uint8)
        for c in gset:
            m[c["y"] - y1:c["y"] - y1 + c["h"], c["x"] - x1:c["x"] - x1 + c["w"]] = np.maximum(
                m[c["y"] - y1:c["y"] - y1 + c["h"], c["x"] - x1:c["x"] - x1 + c["w"]], c["mask"])
        merged.append(dict(x=x1, y=y1, w=x2 - x1, h=y2 - y1, area=int((m > 0).sum()), mask=m))
    merged.sort(key=lambda c: c["x"])
    return merged


def sprite_templates(sprite_rgb: np.ndarray) -> list[np.ndarray]:
    mx = sprite_rgb.max(2).astype(np.int16)
    mn = sprite_rgb.min(2).astype(np.int16)
    sat = (mx - mn).astype(np.float32)
    g = cv2.cvtColor(sprite_rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    g_cut = min(110.0, max(55.0, float(np.percentile(g, 12))))
    mask = (((g <= g_cut) & (sat < 80)) * 255).astype(np.uint8)
    mask = cv2.erode(mask, np.ones((2, 2), np.uint8))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8), iterations=2)
    mask = cv2.dilate(mask, np.ones((2, 2), np.uint8))
    comps = _components(mask, 25)
    comps = [c for c in comps if 6 <= c["w"] <= 90 and 6 <= c["h"] <= 60]
    if comps:
        med = float(np.median([c["area"] for c in comps]))
        comps = [c for c in comps if c["area"] >= med * 0.20]
    return [g["mask"] for g in _merge_nearby(comps, 2)]


def search_mask(image_rgb: np.ndarray) -> np.ndarray:
    mx = image_rgb.max(2).astype(np.int16)
    mn = image_rgb.min(2).astype(np.int16)
    sat = (mx - mn).astype(np.float32)
    g = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    bg = cv2.boxFilter(g, -1, (21, 21), normalize=True)
    contrast = bg - g
    sat_cut = max(75.0, float(np.percentile(sat, 98)))
    vivid = (sat >= sat_cut) & (mx > 90)
    dark_px = (g < 80) & (sat < 48)
    if int(dark_px.sum()) > 50:
        dcut = max(30.0, float(np.percentile(contrast[dark_px], 80)))
        dark = dark_px & (contrast >= dcut)
    else:
        dark = np.zeros_like(vivid)
    raw = ((vivid | dark) * 255).astype(np.uint8)
    raw = cv2.morphologyEx(raw, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8), iterations=2)
    inv = (raw == 0).astype(np.uint8)
    h, w = inv.shape
    ff = inv.copy()
    m2 = np.zeros((h + 2, w + 2), np.uint8)
    cv2.floodFill(ff, m2, (0, 0), 2)
    holes = (ff != 2) & (inv == 1)
    filled = raw.copy()
    filled[holes] = 255
    return filled


def _rot_scale(mask: np.ndarray, angle: float, scale: float) -> np.ndarray:
    h, w = mask.shape[:2]
    nw, nh = max(3, int(w * scale)), max(3, int(h * scale))
    s = cv2.resize(mask, (nw, nh), interpolation=cv2.INTER_NEAREST)
    h2, w2 = s.shape[:2]
    c = (w2 / 2, h2 / 2)
    m = cv2.getRotationMatrix2D(c, angle, 1.0)
    cs, sn = abs(m[0, 0]), abs(m[0, 1])
    bw, bh = int(h2 * sn + w2 * cs), int(h2 * cs + w2 * sn)
    m[0, 2] += bw / 2 - c[0]
    m[1, 2] += bh / 2 - c[1]
    r = cv2.warpAffine(s, m, (bw, bh), flags=cv2.INTER_NEAREST,
                       borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    ys, xs = np.where(r > 0)
    if len(xs) == 0:
        return r
    x1, x2 = max(0, int(xs.min()) - 3), min(r.shape[1], int(xs.max()) + 4)
    y1, y2 = max(0, int(ys.min()) - 3), min(r.shape[0], int(ys.max()) + 4)
    return r[y1:y2, x1:x2]


def _match_template(bg_mask: np.ndarray, tmask: np.ndarray) -> list[dict]:
    h, w = bg_mask.shape[:2]
    tops: list[dict] = []
    for scale in SCALES:
        for angle in ANGLE_RANGE:
            tpl = _rot_scale(tmask, float(angle), float(scale))
            if tpl.shape[0] > h or tpl.shape[1] > w or int((tpl > 0).sum()) < 12:
                continue
            response = cv2.matchTemplate(bg_mask, tpl, cv2.TM_CCOEFF_NORMED)
            work = response.copy()
            target = tpl > 0
            for _ in range(5):
                _, corr, _, loc = cv2.minMaxLoc(work)
                if not math.isfinite(corr):
                    break
                x, y = loc
                patch = bg_mask[y:y + tpl.shape[0], x:x + tpl.shape[1]] > 0
                overlap = int(np.logical_and(patch, target).sum())
                recall = overlap / max(1, int(target.sum()))
                precision = overlap / max(1, int(patch.sum()))
                score = 0.55 * float(corr) + 0.28 * recall + 0.17 * precision
                cx, cy = x + tpl.shape[1] / 2.0, y + tpl.shape[0] / 2.0
                idx = next((i for i, m in enumerate(tops)
                            if math.hypot(cx - m["x"], cy - m["y"]) < 14), None)
                if idx is None:
                    tops.append({"x": cx, "y": cy, "score": score})
                elif score > tops[idx]["score"]:
                    tops[idx] = {"x": cx, "y": cy, "score": score}
                tops.sort(key=lambda m: -m["score"])
                del tops[MAX_TOPS:]
                r = max(12, min(tpl.shape[1], tpl.shape[0]) // 2)
                work[max(0, y - r):min(work.shape[0], y + r + 1),
                     max(0, x - r):min(work.shape[1], x + r + 1)] = -2
    return tops


def _best_combination(match_lists: list[list[dict]]) -> tuple[list[dict] | None, float]:
    best = None
    best_score = -math.inf

    def combo_score(combo: list[dict]) -> float:
        score = sum(m["score"] for m in combo)
        for i in range(len(combo)):
            for j in range(i + 1, len(combo)):
                dist = math.hypot(combo[i]["x"] - combo[j]["x"], combo[i]["y"] - combo[j]["y"])
                if dist < 28:
                    score -= 1.2 * (1 - dist / 28)
        return score

    def visit(index: int, current: list[dict]) -> None:
        nonlocal best, best_score
        if index == len(match_lists):
            sc = combo_score(current)
            if sc > best_score:
                best, best_score = tuple(current), sc
            return
        for m in match_lists[index]:
            current.append(m)
            visit(index + 1, current)
            current.pop()

    visit(0, [])
    return (list(best) if best else None), best_score


def solve_seq(image_rgb: np.ndarray, sprite_rgb: np.ndarray) -> dict:
    templates = sprite_templates(sprite_rgb)
    h, w = image_rgb.shape[:2]
    result = {"ok": False, "img_w": w, "img_h": h, "n_glyphs": len(templates)}
    if not templates:
        result["error"] = "sprite glyphs not found"
        return result
    bg_mask = search_mask(image_rgb)
    match_lists = []
    for tmask in templates:
        tops = _match_template(bg_mask, tmask)
        if not tops:
            result["error"] = "no match candidates"
            return result
        match_lists.append(tops)
    combo, _ = _best_combination(match_lists)
    if combo is None:
        result["error"] = "no non-overlapping combination"
        return result
    clicks = [{"x": round(m["x"], 1), "y": round(m["y"], 1), "score": round(m["score"], 4)}
              for m in combo]
    scores = [c["score"] for c in clicks]
    result.update({
        "ok": True,
        "clicks": clicks,
        "scores": scores,
        "min_score": min(scores),
        "mean_score": round(sum(scores) / len(scores), 4),
    })
    return result
