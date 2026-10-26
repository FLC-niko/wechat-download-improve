"""Atomically change rollout percentage, keeping a private previous config."""
import argparse
import datetime
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request

from text_ab_router import configuration


def atomic(path, data):
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="/home/topview/captcha/text-ab.json")
    parser.add_argument("--percent", type=int, choices=(0, 10, 25, 50, 100), required=True)
    parser.add_argument("--health-url", default="http://127.0.0.1:9995/health")
    args = parser.parse_args()
    if urllib.parse.urlparse(args.health_url).hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("health must use loopback")
    os.umask(0o077)
    path = Path(args.config)
    old = path.read_text()
    config = configuration(path)
    previous = config["percent"]
    config["percent"] = args.percent
    archive = path.with_name(path.name + "." + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    archive.write_text(old)
    atomic(path, json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    try:
        with urllib.request.urlopen(args.health_url, timeout=10) as response:
            health = json.load(response)
        if (health.get("mode") != "ab" or health.get("percent") != args.percent
                or health.get("config_error") or health.get("expected_version") != config["expected_version"]):
            raise ValueError("router did not load expected rollout config")
    except BaseException:
        atomic(path, old)
        raise
    print(json.dumps({"previous_percent": previous, "percent": args.percent,
                      "phase": config["phase"], "previous_config": str(archive)}))


if __name__ == "__main__":
    main()
