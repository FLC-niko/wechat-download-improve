"""Create, verify and atomically select self-contained private text releases.

This manages vision-service code and model bundles, not Kotlin deploy/current.
Creation never changes a running service. Activation requires an explicit link
and optional systemd unit; a failed restart/health check restores the old link.
"""
from __future__ import annotations

import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

SERVICE_FILES = ("clip_service.py", "seq_match.py", "text_runtime.py", "text_features.py")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify(release):
    release = Path(release).resolve(strict=True)
    manifest = json.loads((release/"release.json").read_text())
    if manifest.get("schema") != 1 or not manifest.get("files"):
        raise ValueError("invalid release manifest")
    paths = set(manifest["files"])
    required = {f"service/{n}" for n in SERVICE_FILES} | {
        f"bundle/{n}" for n in ("model.pt","references.npz","training.json",
                                "fusion.json","evaluation.json","manifest.json")}
    if not required.issubset(paths):
        raise ValueError("incomplete release")
    for name, expected in manifest["files"].items():
        path = release/name
        if not path.resolve().is_relative_to(release) or path.is_symlink():
            raise ValueError("release files must stay inside the release")
        if digest(path) != expected:
            raise ValueError(f"checksum mismatch: {name}")
    # Check the inner artifact manifest too, so the serving loader and release
    # verifier cannot disagree about which model files are covered.
    model = json.loads((release/"bundle/manifest.json").read_text())
    for name, expected in model["files"].items():
        if Path(name).name != name or digest(release/"bundle"/name) != expected:
            raise ValueError(f"model checksum mismatch: {name}")
    return manifest


def create(args):
    dest = Path(args.dest).absolute()
    if dest.exists():
        raise ValueError("refusing to overwrite a release")
    dest.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    stage = dest.with_name(dest.name + f".staging-{os.getpid()}")
    stage.mkdir(mode=0o700)
    try:
        (stage/"service").mkdir()
        for name in SERVICE_FILES:
            shutil.copy2(Path(args.source)/name, stage/"service"/name)
        shutil.copytree(args.bundle, stage/"bundle", symlinks=False)
        evaluation = json.loads((stage/"bundle/evaluation.json").read_text())
        score = evaluation["fusion"]["exact"]
        if score < args.min_exact:
            raise ValueError(f"evaluation exact {score:.4f} below requested gate {args.min_exact:.4f}")
        metadata = {"schema":1,"created_utc":datetime.datetime.now(
            datetime.timezone.utc).isoformat(),"name":dest.name,
            "accuracy_status":evaluation["status"],
            "offline_exact":score,"offline_rounds":evaluation["fusion"]["rounds"],
            "runtime_python":args.python,
            "files":{str(p.relative_to(stage)):digest(p) for p in sorted(stage.rglob("*"))
                     if p.is_file() and "__pycache__" not in p.parts}}
        (stage/"release.json").write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
        verify(stage)
        for p in stage.rglob("*"):
            if p.is_file():p.chmod(0o400)
        stage.rename(dest)
    except BaseException:
        shutil.rmtree(stage)
        raise
    return {"release":str(dest),"offline_exact":score,
            "manifest_sha256":digest(dest/"release.json")}


def atomic_link(link, target):
    tmp = link.with_name(link.name + f".tmp-{os.getpid()}")
    try:
        tmp.symlink_to(target, target_is_directory=True)
        os.replace(tmp,link)
    finally:
        if tmp.is_symlink():tmp.unlink()


def _activate_locked(args):
    release=Path(args.release).resolve(strict=True)
    verify(release)
    link=Path(args.link).absolute()
    link.parent.mkdir(parents=True,exist_ok=True)
    if link.exists() and not link.is_symlink():
        raise ValueError("refusing to replace a real directory")
    try:
        old=link.resolve(strict=True) if link.is_symlink() else None
    except FileNotFoundError:
        # Restoring from a snapshot must also work when the previously selected
        # release directory was accidentally removed.
        old=None
    if old == release:
        return {"current":str(release),"changed":False}
    previous=link.with_name(link.name+".previous")
    if previous.exists() and not previous.is_symlink():
        raise ValueError("previous pointer must be a symlink")
    atomic_link(link,release)
    try:
        if args.service:
            subprocess.run(["systemctl","restart",args.service],check=True)
        if args.health_url:
            version=digest(release/"bundle/manifest.json")[:16]
            error=None
            for _ in range(60):
                try:
                    with urllib.request.urlopen(args.health_url,timeout=3) as response:
                        health=json.load(response)
                    if (health.get("status")=="ok" and health.get("text_loaded") is True
                            and health.get("text_version")==version):
                        error=None;break
                    error=ValueError("service did not load the selected text bundle")
                except Exception as exc:error=exc
                time.sleep(1)
            if error:raise error
    except BaseException:
        if old is not None:
            atomic_link(link,old)
            if args.service:subprocess.run(["systemctl","restart",args.service],check=True)
        else:
            link.unlink()
            if args.service:subprocess.run(["systemctl","stop",args.service],check=True)
        raise
    # Retain previous only after the new service is healthy. Existing release
    # directories are never removed, even after multiple switches.
    if old is not None:atomic_link(previous,old)
    return {"current":str(release),"previous":str(old) if old else None,"changed":True}


def activate(args):
    link=Path(args.link).absolute()
    link.parent.mkdir(parents=True,exist_ok=True)
    with link.with_name(link.name+".lock").open("a") as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        return _activate_locked(args)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest="command",required=True)
    c=commands.add_parser("create")
    c.add_argument("--source",required=True);c.add_argument("--bundle",required=True)
    c.add_argument("--dest",required=True);c.add_argument("--min-exact",type=float,required=True)
    c.add_argument("--python",default="/opt/crack-tcaptcha/venv/bin/python")
    v=commands.add_parser("verify");v.add_argument("--release",required=True)
    a=commands.add_parser("activate")
    a.add_argument("--release",required=True);a.add_argument("--link",required=True)
    a.add_argument("--service");a.add_argument("--health-url")
    args=parser.parse_args()
    if args.command=="create":result=create(args)
    elif args.command=="verify":
        m=verify(args.release);result={"verified":True,"name":m["name"],
                                      "offline_exact":m["offline_exact"]}
    else:result=activate(args)
    print(json.dumps(result,ensure_ascii=False))


if __name__=="__main__":main()
