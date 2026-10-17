"""Local cn-clip region matcher for image_select (no external LLM API).

Upstream `crackTCaptcha` only ships an LLM vision backend for image_select.
This module adds a fully local backend: crop every candidate region from the
background image, score each crop against the instruction text with
Chinese-CLIP (cn-clip), and pick the best region.

Behaviour is controlled by environment variables:

- ``TCAPTCHA_IMAGE_SELECT_BACKEND``: ``auto`` (default), ``clip`` or ``llm``
- ``TCAPTCHA_CLIP_MODEL``: cn-clip checkpoint name (default ``ViT-B-16``)
- ``TCAPTCHA_CLIP_DEVICE``: torch device (default ``cpu``)
- ``TCAPTCHA_CLIP_MODEL_DIR``: checkpoint download root
  (default ``/opt/crack-tcaptcha/models``)
"""

from __future__ import annotations

import io
import logging
import os
import threading
import time

from crack_tcaptcha.exceptions import SolveError

log = logging.getLogger(__name__)

_DEFAULT_MODEL_DIR = "/opt/crack-tcaptcha/models"

_lock = threading.Lock()
_model = None
_preprocess = None
_tokenize = None


def _env(name: str, default: str) -> str:
    value = os.environ.get(name, "").strip()
    return value or default


def backend_setting() -> str:
    return _env("TCAPTCHA_IMAGE_SELECT_BACKEND", "auto").lower()


def clip_available() -> bool:
    """Cheap capability probe: backend allows clip and imports succeed."""
    if backend_setting() == "llm":
        return False
    try:
        import cn_clip.clip  # noqa: F401
        import torch  # noqa: F401
        from PIL import Image  # noqa: F401
    except Exception:
        return False
    return True


def warmup() -> None:
    """Load the checkpoint eagerly; safe to call repeatedly."""
    if clip_available():
        _load_model()


def _load_model():
    global _model, _preprocess, _tokenize
    if _model is not None:
        return
    with _lock:
        if _model is not None:
            return
        started = time.time()
        from cn_clip.clip import load_from_name, tokenize

        model_name = _env("TCAPTCHA_CLIP_MODEL", "ViT-B-16")
        device = _env("TCAPTCHA_CLIP_DEVICE", "cpu")
        model_dir = _env("TCAPTCHA_CLIP_MODEL_DIR", _DEFAULT_MODEL_DIR)
        model, preprocess = load_from_name(model_name, device=device, download_root=model_dir)
        model.eval()
        _model, _preprocess, _tokenize = model, preprocess, tokenize
        log.info(
            "image_select clip: model loaded model=%s device=%s in %.1fs",
            model_name,
            device,
            time.time() - started,
        )


def match_region_clip(bg_bytes: bytes, *, instruction: str, regions, bg_size) -> int:
    """Pick the region whose crop best matches the instruction via cn-clip."""
    if not clip_available():
        raise SolveError("image_select: local clip backend unavailable")
    if not regions:
        raise SolveError("image_select: no regions to match")

    from crack_tcaptcha.solvers.llm_vision import _strip_instruction

    cleaned = _strip_instruction(instruction)

    import torch
    from PIL import Image

    _load_model()
    image = Image.open(io.BytesIO(bg_bytes)).convert("RGB")
    width, height = image.size
    crops = []
    for region in regions:
        x1, y1, x2, y2 = (int(value) for value in region.range)
        x1 = max(0, min(x1, width - 1))
        y1 = max(0, min(y1, height - 1))
        x2 = max(x1 + 1, min(x2, width))
        y2 = max(y1 + 1, min(y2, height))
        crops.append(image.crop((x1, y1, x2, y2)))

    batch = torch.stack([_preprocess(crop) for crop in crops])
    with torch.no_grad():
        image_features = _model.encode_image(batch)
        text_features = _model.encode_text(_tokenize([cleaned]))
    image_features = image_features / image_features.norm(dim=-1, keepdim=True)
    text_features = text_features / text_features.norm(dim=-1, keepdim=True)
    sims = (image_features @ text_features.T).squeeze(-1).tolist()

    best_index = max(range(len(sims)), key=lambda index: sims[index])
    ranked = sorted(sims, reverse=True)
    margin = ranked[0] - ranked[1] if len(ranked) > 1 else ranked[0]
    region_id = regions[best_index].id
    log.info(
        "image_select clip: instruction=%r sims=%s margin=%.3f -> region=%s",
        cleaned,
        [round(value, 3) for value in sims],
        margin,
        region_id,
    )
    return region_id


__all__ = ["backend_setting", "clip_available", "match_region_clip", "warmup"]
