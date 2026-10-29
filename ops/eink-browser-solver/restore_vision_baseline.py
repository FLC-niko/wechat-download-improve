"""Verify/restore the pre-change full vision-service snapshot on its host.

Default is verify-only. --apply restores code and systemd configuration, keeping
the separately saved model/venv available. --restore-runtime also restores the
original Python environment and model directory. Never restores live debug data
or account/application state. Run as root on the application host.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

CODE = ("clip_service.py","solver-daemon.mjs","seq_match.py","package.json","package-lock.json")
CONFIG = ("/etc/eink-browser-solver.env",
          "/etc/systemd/system/eink-clip-service.service",
          "/etc/systemd/system/eink-browser-solver.service")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot")
    parser.add_argument("--apply",action="store_true")
    parser.add_argument("--restore-runtime",action="store_true")
    args=parser.parse_args()
    snapshot=Path(args.snapshot).resolve(strict=True)
    manifest=json.loads((snapshot/"manifest.json").read_text())
    if manifest["kind"]!="production-baseline":raise ValueError("not a production baseline")
    for entry in manifest["files"]:
        path=(snapshot/entry["path"]).resolve(strict=True)
        if not path.is_relative_to(snapshot):raise ValueError("invalid snapshot path")
        h=hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda:stream.read(1024*1024),b""):h.update(block)
        if h.hexdigest()!=entry["sha256"]:raise ValueError(f"corrupt snapshot: {entry['path']}")
    print(json.dumps({"verified":True,"snapshot":str(snapshot),
                      "files":len(manifest["files"]),"apply":args.apply}),flush=True)
    if not args.apply:return
    if os.geteuid()!=0:raise PermissionError("restore requires root")
    services=["eink-browser-solver","eink-clip-service"]
    subprocess.run(["systemctl","stop",*services],check=True)
    try:
        if args.restore_runtime:
            for raw in ("/opt/crack-tcaptcha/venv","/opt/crack-tcaptcha/models"):
                src=snapshot/"files"/raw.lstrip("/")
                dest=Path(raw)
                backup=dest.with_name(dest.name+f".before-restore-{int(time.time())}")
                stage=dest.with_name(dest.name+f".restoring-{os.getpid()}")
                shutil.copytree(src,stage,symlinks=True)
                dest.rename(backup);stage.rename(dest)
        paths=[f"/opt/eink-browser-solver/{name}" for name in CODE]+list(CONFIG)
        for raw in paths:
            src=snapshot/"files"/raw.lstrip("/")
            if not src.exists():continue
            dest=Path(raw)
            temp=dest.with_name(dest.name+f".restore-{os.getpid()}")
            shutil.copy2(src,temp);os.replace(temp,dest)
        subprocess.run(["systemctl","daemon-reload"],check=True)
    finally:
        subprocess.run(["systemctl","start",*reversed(services)],check=True)
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:9993/health",timeout=3) as response:
                health=json.load(response)
            if health.get("status")=="ok":
                print(json.dumps({"restored":True,"health":health}));return
        except Exception:pass
        time.sleep(1)
    raise RuntimeError("baseline restored but vision health did not recover within 90 seconds")


if __name__=="__main__":main()
