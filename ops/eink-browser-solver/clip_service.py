"""Local vision picker service for the browser captcha solver daemon.

Strategies, routed by instruction text:

- character-search tasks ("包含文字：X"): detect characters with the bundled
  YOLOv8 detector and compare glyphs with the Siamese matcher (rendered target
  char via font.ttf). This distinguishes visually-similar distractor characters
  (e.g. 唐/衙, 溶/容) which plain OCR confuses. ddddocr text matching is kept
  as a cross-check / fallback.
- everything else (scene/object): cn-clip image-text similarity.

POST /pick  {"image_b64": "...", "instruction": "...",
             "regions": [{"id": 1, "range": [x1,y1,x2,y2]}, ...]}
POST /seq-pick {"image_b64": "...", "sprite_b64": "..."}
             -> {"ok":true,"clicks":[{"x","y","score"}...],"min_score":...,"img_w","img_h"}
GET  /health
"""

from __future__ import annotations

import base64
import io
import json
import logging
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from PIL import Image

LOG = logging.getLogger("vision-pick")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

MODEL_NAME = os.environ.get("CLIP_MODEL", "ViT-B-16")
MODEL_DIR = os.environ.get("CLIP_MODEL_DIR", "/opt/crack-tcaptcha/models")
DEVICE = os.environ.get("CLIP_DEVICE", "cpu")
PORT = int(os.environ.get("CLIP_PORT", "9993"))

_lock = threading.Lock()
_model = None
_preprocess = None
_tokenize = None

_ocr = None
_ocr_lock = threading.Lock()


def _load():
    global _model, _preprocess, _tokenize
    if _model is not None:
        return
    with _lock:
        if _model is not None:
            return
        started = time.time()
        from cn_clip.clip import load_from_name, tokenize

        model, preprocess = load_from_name(MODEL_NAME, device=DEVICE, download_root=MODEL_DIR)
        model.eval()
        _model, _preprocess, _tokenize = model, preprocess, tokenize
        LOG.info("clip model loaded %s in %.1fs", MODEL_NAME, time.time() - started)


def _load_ocr():
    global _ocr
    if _ocr is not None:
        return
    with _ocr_lock:
        if _ocr is not None:
            return
        import ddddocr

        _ocr = ddddocr.DdddOcr(show_ad=False)
        LOG.info("ddddocr loaded")


def _crop_box(image: Image.Image, region: dict) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = (int(v) for v in region["range"])
    x1 = max(0, min(x1, image.width - 1))
    y1 = max(0, min(y1, image.height - 1))
    x2 = max(x1 + 1, min(x2, image.width))
    y2 = max(y1 + 1, min(y2, image.height))
    return x1, y1, x2, y2


def _char_search_target(instruction: str) -> str:
    m = re.search(r"文字[:：]?\s*(.+)", instruction)
    if not m:
        return ""
    target = m.group(1).strip().strip("“”\"'。.，, ")
    return "".join(ch for ch in target if "\u4e00" <= ch <= "\u9fff" or ch.isalnum())


def _ocr_texts(image: Image.Image, regions: list, scale: float = 1.0) -> list:
    _load_ocr()
    results = []
    for region in regions:
        x1, y1, x2, y2 = _crop_box(image, region)
        crop = image.crop((x1, y1, x2, y2))
        if scale != 1.0:
            crop = crop.resize(
                (max(1, int(crop.width * scale)), max(1, int(crop.height * scale))),
                Image.LANCZOS,
            )
        buf = io.BytesIO()
        crop.save(buf, format="PNG")
        try:
            text = _ocr.classification(buf.getvalue()) or ""
        except Exception as exc:  # noqa: BLE001
            text = f"<ocr-error {exc}>"
        results.append({"id": region["id"], "text": text[:40]})
    return results


_FONT_PATHS_CACHE = None
_SIAMESE_MIN_SCORE = float(os.environ.get("SIAMESE_MIN_SCORE", "0.30"))


def _font_paths() -> list:
    global _FONT_PATHS_CACHE
    if _FONT_PATHS_CACHE is not None:
        return _FONT_PATHS_CACHE
    import glob

    paths = []
    try:
        from crack_tcaptcha.solvers import word_ocr as wo

        paths.append(str(wo._FONT_PATH))
    except Exception:  # noqa: BLE001
        pass
    for pattern in (
        "/usr/share/fonts/truetype/wqy/*.ttc",
        "/usr/share/fonts/truetype/wqy/*.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
        "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc",
    ):
        paths.extend(sorted(glob.glob(pattern)))
    seen, out = set(), []
    for item in paths:
        if item not in seen:
            seen.add(item)
            out.append(item)
    _FONT_PATHS_CACHE = out[:4]
    return _FONT_PATHS_CACHE


def _render_char_variants(char: str) -> list:
    """Render the target char with several fonts; the captcha font is unknown."""
    from PIL import ImageDraw, ImageFont

    variants = []
    for path in _font_paths():
        try:
            img = Image.new("RGB", (52, 52), color="white")
            draw = ImageDraw.Draw(img)
            font = ImageFont.truetype(path, 40)
            bbox = font.getbbox(char)
            x = (52 - (bbox[2] - bbox[0])) // 2
            draw.text((x, -3), char, fill=(227, 178, 56), font=font)
            variants.append(np.ascontiguousarray(np.array(img)[..., ::-1]))
        except Exception:  # noqa: BLE001
            continue
    return variants


def _pick_by_siamese(image: Image.Image, regions: list, target: str, raw_instruction: str) -> dict:
    """YOLO detect + Siamese glyph matching per tile; pick best tile."""
    from crack_tcaptcha.solvers import word_ocr as wo

    image_rgb = np.array(image)
    try:
        import cv2

        image_bgr = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2BGR)
    except Exception:  # noqa: BLE001
        image_bgr = image_rgb[..., ::-1]

    chars = list(target)
    details = []
    best_id = None
    best_score = -1.0
    for region in regions:
        x1, y1, x2, y2 = _crop_box(image, region)
        tile = image_bgr[y1:y2, x1:x2]
        try:
            bboxes = wo._yolo_detect(tile)
        except Exception as exc:  # noqa: BLE001
            details.append({"id": region["id"], "error": str(exc)[:80]})
            continue
        per_char = {}
        if bboxes:
            crops = []
            for bx1, by1, bx2, by2 in bboxes:
                c = tile[by1:by2, bx1:bx2]
                if c.size:
                    crops.append(c)
            for ch in chars:
                try:
                    refs = _render_char_variants(ch) or [wo._render_char(ch)]
                    sims = []
                    for ref in refs:
                        sims.extend(wo._siamese_score_batch(crops, ref) if crops else [])
                    per_char[ch] = round(max(sims), 3) if sims else -1.0
                except Exception as exc:  # noqa: BLE001
                    per_char[ch] = f"err:{str(exc)[:40]}"
        score = min([v for v in per_char.values() if isinstance(v, float)], default=-1.0) if per_char else -1.0
        details.append({"id": region["id"], "nboxes": len(bboxes), "score": round(score, 3) if isinstance(score, float) else score, "per_char": per_char})
        if isinstance(score, float) and score > best_score:
            best_score = score
            best_id = region["id"]

    ocr_cross = None
    try:
        ocr_cross = _ocr_texts(image, regions)
    except Exception as exc:  # noqa: BLE001
        ocr_cross = [{"error": str(exc)[:80]}]

    def _hits(items):
        if not items or not isinstance(items[0], dict) or "id" not in items[0]:
            return []
        return [item["id"] for item in items if any(ch in item.get("text", "") for ch in set(target))]

    hits = _hits(ocr_cross)
    if not hits:
        # Native-resolution OCR often misses stylized glyphs; retry on 2x upscaled tiles.
        try:
            ocr_up = _ocr_texts(image, regions, scale=2.0)
            up_hits = _hits(ocr_up)
            if up_hits:
                ocr_cross = ocr_up
                hits = up_hits
                LOG.info("upscaled OCR found target %r on %s", target, hits)
        except Exception as exc:  # noqa: BLE001
            LOG.info("upscaled OCR failed: %s", str(exc)[:80])

    LOG.info("siamese pick target=%r details=%s ocr=%s -> %s", target, details, ocr_cross, best_id)
    confidence = best_score if isinstance(best_score, float) else -1.0
    low_confidence = False
    # Observed rule for this captcha: click ALL tiles containing the target character
    # (landscape glyphs). Verified samples: "丙" tiles read by OCR as 丙 were exactly the
    # correct set; clicking a single tile or letting Siamese override the OCR set fails.
    if hits:
        selected, method = hits, "ocr-set"
    elif confidence >= _SIAMESE_MIN_SCORE and best_id is not None:
        selected, method = [best_id], "siamese"
    elif best_id is not None and confidence > 0:
        selected, method, low_confidence = [best_id], "siamese-low", True
    else:
        # No usable signal. Blind CLIP picks on single-char tasks are almost always wrong.
        selected, method, low_confidence = [], "none", True
    LOG.info(
        "char-search target=%r -> %s (method=%s, conf=%.3f, low=%s)",
        target, selected, method, confidence, low_confidence,
    )
    return {
        "ok": True,
        "region_id": selected[0] if selected else None,
        "region_ids": selected,
        "method": method,
        "confidence": confidence,
        "low_confidence": low_confidence,
        "target": target,
        "details": details,
        "ocr": ocr_cross,
    }


def _pick_by_clip(image: Image.Image, instruction: str, regions: list) -> dict:
    import torch

    crops = [image.crop(_crop_box(image, region)) for region in regions]
    batch = torch.stack([_preprocess(c) for c in crops])
    with torch.no_grad():
        image_features = _model.encode_image(batch)
        text_features = _model.encode_text(_tokenize([instruction.strip()]))
    image_features = image_features / image_features.norm(dim=-1, keepdim=True)
    text_features = text_features / text_features.norm(dim=-1, keepdim=True)
    sims = (image_features @ text_features.T).squeeze(-1).tolist()
    order = sorted(range(len(sims)), key=lambda i: sims[i], reverse=True)
    ranked = [sims[i] for i in order]
    margin = ranked[0] - ranked[1] if len(ranked) > 1 else ranked[0]
    best = regions[order[0]]["id"]
    plural_hint = any(h in instruction for h in ("所有", "全部", "哪些", "几个", "多种", "找出"))
    if plural_hint:
        threshold = max(0.30, ranked[0] * 0.85)
        selected = [regions[i]["id"] for i in order if sims[i] >= threshold] or [best]
        selected = selected[:4]
    else:
        # Recorded rounds: single picks were right 81% of the time, multi picks only 44%.
        selected = [best]
    LOG.info(
        "clip pick instruction=%r sims=%s margin=%.3f -> regions=%s",
        instruction,
        [round(v, 3) for v in sims],
        margin,
        selected,
    )
    return {"ok": True, "region_id": best, "region_ids": selected, "method": "clip", "sims": [round(v, 4) for v in sims], "margin": round(margin, 4)}


def pick(image_b64: str, instruction: str, regions: list) -> dict:
    _load()
    image = Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB")
    target = _char_search_target(instruction)
    if target and "文字" in instruction:
        return _pick_by_siamese(image, regions, target, instruction)
    import torch

    torch.set_num_threads(int(os.environ.get("CLIP_THREADS", "4")))
    return _pick_by_clip(image, instruction, regions)


def seq_pick(image_b64: str, sprite_b64: str) -> dict:
    """Icon-sequence ("依次点击：") matcher; see seq_match.py for the pipeline."""
    from seq_match import solve_seq

    image = np.array(Image.open(io.BytesIO(base64.b64decode(image_b64))).convert("RGB"))
    sprite = np.array(Image.open(io.BytesIO(base64.b64decode(sprite_b64))).convert("RGB"))
    return solve_seq(image, sprite)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path == "/health":
            self._send(200, {"status": "ok", "model": MODEL_NAME, "loaded": _model is not None})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        if self.path == "/seq-pick":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                data = json.loads(self.rfile.read(length).decode())
                result = seq_pick(data["image_b64"], data["sprite_b64"])
                self._send(200, result)
            except Exception as exc:  # noqa: BLE001
                LOG.exception("seq-pick failed")
                self._send(500, {"ok": False, "error": str(exc)[:300]})
            return
        if self.path != "/pick":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            data = json.loads(self.rfile.read(length).decode())
            result = pick(data["image_b64"], data.get("instruction", ""), data["regions"])
            self._send(200, result)
        except Exception as exc:  # noqa: BLE001
            LOG.exception("pick failed")
            self._send(500, {"ok": False, "error": str(exc)[:300]})

    def log_message(self, *args):  # silence default access log
        pass


if __name__ == "__main__":
    LOG.info("loading models before serving...")
    _load()
    threading.Thread(target=_load_ocr, daemon=True).start()
    try:
        from crack_tcaptcha.solvers import word_ocr as wo

        wo.warmup()
        LOG.info("word_click models warmed up")
    except Exception as exc:  # noqa: BLE001
        LOG.warning("word_click warmup failed: %s", exc)
    LOG.info("vision pick service on 127.0.0.1:%d", PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
