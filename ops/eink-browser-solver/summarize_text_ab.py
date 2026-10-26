"""Join existing local pick logs and verifier outcomes into aggregate A/B evidence.

Only observed error codes are counted. Nonzero codes are not human labels of
recognition errors. No tickets, URLs, instructions or account IDs are exported.
"""
import argparse
from collections import Counter, deque
import datetime
import json
import os
from pathlib import Path
import re


def summarize(debug, phase):
    outcomes = Path(debug) / "verify-outcomes.jsonl"
    rows = {}
    if outcomes.exists():
        with outcomes.open() as stream:
            for line in deque(stream, maxlen=10000):
                try:
                    value = json.loads(line)
                    stamp = value["runStamp"]
                    number = value["round"]
                    if not isinstance(stamp, str) or not re.fullmatch(r"[A-Za-z0-9-]+", stamp):
                        continue
                    if type(number) is not int or not 0 < number < 1000:
                        continue
                    rows[(stamp, number)] = value
                except (ValueError, KeyError, TypeError):
                    continue
    groups = {}
    for (stamp, number), row in rows.items():
        pick = Path(debug) / f"r{number}-{stamp}-pick.json"
        try:
            ab = json.loads(pick.read_text())["pick"]["ab"]
            if ab["phase"] != phase or ab["source"] != "live":
                continue
            cohort, served = ab["cohort"], ab["served"]
            if cohort not in ("A", "B") or served not in ("legacy", "cpu_fusion", "gpu_fusion"):
                continue
            code = str(row["errorCode"])
            if not re.fullmatch(r"\d{1,4}", code):
                continue
            key = cohort + ":" + served
            groups.setdefault(key, Counter())[code] += 1
        except (OSError, ValueError, KeyError, TypeError):
            continue
    summary = {}
    for key, codes in sorted(groups.items()):
        count = sum(codes.values())
        summary[key] = {"observed_rounds": count, "accepted": codes["0"],
                        "observed_acceptance_rate": codes["0"] / count,
                        "verifier_codes": dict(codes)}
    return {"phase": phase, "created_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "groups": summary, "status": "observational; no automatic promotion",
            "limitations": "Only submitted rounds with a recorded response are counted; skipped rounds are excluded. "
                           "Nonzero codes include environment/session failures; not recognition ground truth. "
                           "Assignments are by image and target; repeated rounds are not independent."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--debug", default="/opt/eink-browser-solver/debug")
    parser.add_argument("--config", default="/home/topview/captcha/text-ab.json")
    parser.add_argument("--output", default="/home/topview/captcha/text-ab-outcomes.json")
    args = parser.parse_args()
    os.umask(0o077)
    config = json.loads(Path(args.config).read_text())
    report = summarize(args.debug, config["phase"])
    output = Path(args.output)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    temporary.chmod(0o600)
    temporary.replace(output)
    print(json.dumps(report))


if __name__ == "__main__":
    main()
