"""Serving-safe features shared by training, evaluation and inference.

Only observations and training-set statistics are accepted here. In particular,
the true selected set and its size must never be inputs to this function.
"""
from __future__ import annotations

import numpy as np

FEATURE_NAMES = (
    "ocr_hit", "knn", "class_probability", "log_training_target_rounds",
    "log_ocr_hits", "knn_margin_to_other", "knn_minus_mean", "knn_rank",
    "knn_ge_07", "knn_ge_05",
)


def features(target, ocr_texts, knn, probabilities, training_target_rounds):
    k = np.asarray(knn, dtype=np.float64)
    p = np.asarray(probabilities, dtype=np.float64)
    if k.shape != (6,) or p.shape != (6,) or len(ocr_texts) != 6:
        raise ValueError("text fusion requires exactly six tiles")
    if not np.isfinite(k).all() or not np.isfinite(p).all():
        raise ValueError("non-finite model scores")
    hits = [bool(target and target in text) for text in ocr_texts]
    return np.asarray([
        [int(hits[i]), k[i], p[i], np.log1p(training_target_rounds),
         np.log1p(sum(hits)), k[i] - np.delete(k, i).max(), k[i] - k.mean(),
         np.count_nonzero(k > k[i]) / 6.0, int(k[i] >= .7), int(k[i] >= .5)]
        for i in range(6)
    ], dtype=np.float64)


def predict_linear(x, coefficients, intercept):
    z = np.clip(np.asarray(x) @ np.asarray(coefficients) + intercept, -40, 40)
    return 1.0 / (1.0 + np.exp(-z))


def metrics(predictions, truth):
    """Use every labeled round, including empty/rejected predictions."""
    correct = misses = extras = tp = fp = fn = 0
    f1s = []
    errors = []
    for rid, true_ids in truth.items():
        actual = set(true_ids)
        picked = set(predictions.get(rid, []))
        correct += picked == actual
        misses += bool(actual - picked)
        extras += bool(picked - actual)
        tp += len(actual & picked)
        fp += len(picked - actual)
        fn += len(actual - picked)
        den = len(actual) + len(picked)
        f1s.append(2 * len(actual & picked) / den if den else 1.0)
        if actual != picked:
            errors.append({"round": rid, "expected": sorted(actual),
                           "predicted": sorted(picked),
                           "missed": sorted(actual - picked),
                           "extra": sorted(picked - actual)})
    n = len(truth)
    return {"rounds": n, "correct": correct, "exact": correct / n if n else 0,
            "round_macro_f1": float(np.mean(f1s)) if n else 0,
            "tile_micro_f1": 2 * tp / max(1, 2 * tp + fp + fn),
            "miss_rounds": misses, "extra_rounds": extras, "errors": errors}
