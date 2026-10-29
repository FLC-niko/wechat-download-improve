"""Replay labeled, saved images against an existing loopback service.

No browser or external challenge is used. Compare to held-out predictions
recorded in the candidate evaluation, never its full-data training-image score.
Requests contain images and target characters only; labels stay in this process.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request

BOXES = [(0, 34, 220, 254), (226, 34, 446, 254), (452, 34, 672, 254),
         (0, 260, 220, 480), (226, 260, 446, 480), (452, 260, 672, 480)]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def get_json(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def metrics(predictions, truth):
    correct = misses = extras = tp = fp = fn = 0
    f1s = []
    for rid, expected in truth.items():
        actual = set(predictions[rid])
        expected = set(expected)
        a, b, c = len(actual & expected), len(actual - expected), len(expected - actual)
        correct += actual == expected
        misses += bool(c)
        extras += bool(b)
        tp += a
        fp += b
        fn += c
        f1s.append(2 * a / (2 * a + b + c) if 2 * a + b + c else 1.0)
    return {"rounds": len(truth), "correct": correct, "exact": correct / len(truth),
            "round_macro_f1": sum(f1s) / len(truth),
            "tile_micro_f1": 2 * tp / (2 * tp + fp + fn),
            "miss_rounds": misses, "extra_rounds": extras}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rounds", required=True)
    parser.add_argument("--labels", required=True)
    parser.add_argument("--images", required=True)
    parser.add_argument("--candidate-evaluation", required=True)
    parser.add_argument("--service-source", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:9993/pick")
    parser.add_argument("--scheduler-health", default="http://127.0.0.1:9991/health")
    args = parser.parse_args()
    for url in (args.url, args.scheduler_health):
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
            raise ValueError("only local HTTP services are allowed")
    os.umask(0o077)
    rounds = {r["id"]: r for r in json.loads(Path(args.rounds).read_text())}
    labels = json.loads(Path(args.labels).read_text())
    truth = {rid: sorted(row["selected"]) for rid, row in labels.items()}
    evaluation = json.loads(Path(args.candidate_evaluation).read_text())
    fold_ids = [rid for fold in evaluation["folds"] for rid in fold["test_rounds"]]
    assert len(fold_ids) == len(set(fold_ids)) == len(truth)
    assert set(fold_ids) == set(truth) <= set(rounds)
    # A complete evaluation lists every non-exact prediction; exact rows equal truth.
    candidate = dict(truth)
    for error in evaluation["fusion"]["errors"]:
        assert sorted(error["expected"]) == truth[error["round"]]
        candidate[error["round"]] = error["predicted"]
    assert metrics(candidate, truth)["correct"] == evaluation["fusion"]["correct"]
    images = {rid: Path(args.images) / rounds[rid]["image"] for rid in truth}
    missing = [rid for rid, path in images.items() if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"missing {len(missing)} labeled images: {missing[:3]}")
    out = Path(args.output)
    out.mkdir(mode=0o700, parents=True, exist_ok=True)
    inputs = {key: digest(value) for key, value in {
        "rounds": args.rounds, "labels": args.labels,
        "candidate_evaluation": args.candidate_evaluation,
        "legacy_source": args.service_source}.items()}
    image_hashes = {rid: digest(path) for rid, path in images.items()}
    contract = {"inputs_sha256": inputs, "images_sha256": image_hashes,
                "url": args.url, "regions": BOXES, "cases": len(truth),
                "candidate_protocol": evaluation["protocol"],
                "candidate_score_source": "held-out evaluation error records and exact rows"}
    contract_path = out / "contract.json"
    if contract_path.exists():
        # JSON round-trip normalizes the box tuples for a strict resumable contract.
        assert json.loads(contract_path.read_text()) == json.loads(json.dumps(contract))
    else:
        contract_path.write_text(json.dumps(contract, ensure_ascii=False, indent=2))
    records = {}
    results_path = out / "legacy-results.jsonl"
    if results_path.exists():
        for line in results_path.read_text().splitlines():
            item = json.loads(line)
            assert item["round"] not in records
            records[item["round"]] = item
    assert set(records) <= set(truth)
    print(json.dumps({"preflight": "ok", "cases": len(truth), "resuming": len(records),
                      "legacy_source_sha256": inputs["legacy_source"]}), flush=True)
    for rid in sorted(truth):
        if rid in records:
            continue
        # Yield to real production work before each serial request.
        while True:
            health = get_json(args.scheduler_health)
            if health.get("status") == "ok" and health.get("active") == 0 and health.get("queued") == 0:
                break
            print(json.dumps({"waiting_for_idle": True}), flush=True)
            time.sleep(5)
        payload = {"image_b64": base64.b64encode(images[rid].read_bytes()).decode(),
                   "instruction": "包含文字：" + rounds[rid]["target"],
                   "regions": [{"id": i + 1, "range": box} for i, box in enumerate(BOXES)]}
        request = urllib.request.Request(args.url, data=json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json"})
        started = time.monotonic()
        with urllib.request.urlopen(request, timeout=120) as response:
            actual = json.load(response)
        if not actual.get("ok"):
            raise RuntimeError(f"service failed on {rid}")
        selected = actual.get("region_ids")
        assert isinstance(selected, list) and all(type(i) is int and 1 <= i <= 6 for i in selected)
        assert len(selected) == len(set(selected))
        row = {"round": rid, "image_sha256": image_hashes[rid],
               "predicted": sorted(selected), "expected": truth[rid],
               "correct": sorted(selected) == truth[rid],
               "method": actual.get("method"), "response": actual,
               "cost_ms": round((time.monotonic() - started) * 1000, 1)}
        with results_path.open("a") as stream:
            stream.write(json.dumps(row, ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        records[rid] = row
        if len(records) % 10 == 0 or len(records) == len(truth):
            print(json.dumps({"completed": len(records),
                              "correct_so_far": sum(r["correct"] for r in records.values())}), flush=True)
        time.sleep(0.2)
    assert digest(args.service_source) == inputs["legacy_source"], "source changed during replay"
    legacy = {rid: row["predicted"] for rid, row in records.items()}
    paired = {"both_correct": 0, "candidate_only_correct": 0,
              "legacy_only_correct": 0, "both_wrong": 0}
    details = []
    for rid in sorted(truth):
        old_ok, new_ok = legacy[rid] == truth[rid], sorted(candidate[rid]) == truth[rid]
        key = ("both_correct" if old_ok and new_ok else "candidate_only_correct" if new_ok
               else "legacy_only_correct" if old_ok else "both_wrong")
        paired[key] += 1
        details.append({"round": rid, "legacy": legacy[rid], "candidate_held_out": candidate[rid],
                        "expected": truth[rid], "comparison": key})
    report = {"purpose": "saved-image local replay; no external CAPTCHA acceptance measured",
              "legacy": metrics(legacy, truth), "candidate_held_out": metrics(candidate, truth),
              "paired": paired, "contract": contract,
              "limitations": ["candidate is grouped cross-validation, not a live final-model replay",
                              "171 historical rounds; not prospective online accuracy",
                              "near-duplicate grouping is heuristic"], "details": details}
    (out / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({key: report[key] for key in ("legacy", "candidate_held_out", "paired")}), flush=True)


if __name__ == "__main__":
    main()
