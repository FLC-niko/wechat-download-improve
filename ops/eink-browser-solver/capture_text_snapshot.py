"""Capture/verify the complete private text-candidate service configuration.

The unchanged full Python/model runtime is anchored to the verified original
baseline, rather than duplicated each time. Run as root on the application host.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def sha(path):
    h=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for data in iter(lambda:stream.read(1024*1024),b""):h.update(data)
    return h.hexdigest()


def verify(dest):
    dest=Path(dest).resolve(strict=True)
    manifest=json.loads((dest/"snapshot.json").read_text())
    for name,value in manifest["files"].items():
        path=(dest/name).resolve(strict=True)
        if not path.is_relative_to(dest) or sha(path)!=value:
            raise ValueError(f"invalid snapshot file: {name}")
    anchor=Path(manifest["runtime_baseline"])
    if sha(anchor/"manifest.json")!=manifest["runtime_baseline_manifest_sha256"]:
        raise ValueError("runtime baseline anchor changed")
    return manifest


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--snapshot",required=True)
    p.add_argument("--verify-only",action="store_true")
    p.add_argument("--release",default="/home/topview/captcha/text-current")
    p.add_argument("--runtime-baseline",default="/home/topview/captcha/snapshots/20261026T035356Z-production-baseline")
    args=p.parse_args()
    dest=Path(args.snapshot)
    if args.verify_only:
        m=verify(dest)
        print(json.dumps({"verified":True,"files":len(m["files"]),"snapshot":str(dest)}));return
    if os.geteuid()!=0:raise PermissionError("private environment capture requires root")
    if dest.exists():raise ValueError("refusing to overwrite a service snapshot")
    from text_release import verify as verify_release
    release=Path(args.release).resolve(strict=True)
    release_metadata=verify_release(release)
    baseline=Path(args.runtime_baseline).resolve(strict=True)
    baseline_sha=sha(baseline/"manifest.json")
    stage=dest.with_name(dest.name+".staging")
    stage.mkdir(parents=True,mode=0o700)
    try:
        shutil.copytree(release,stage/"release",symlinks=True)
        paths=["/etc/eink-browser-solver.env",
               "/etc/systemd/system/eink-clip-service.service",
               "/etc/systemd/system/eink-browser-solver.service",
               "/etc/systemd/system/eink-text-candidate.service",
               "/etc/systemd/system/eink-text-shadow.service",
               "/etc/systemd/system/eink-text-ab-outcomes.service",
               "/etc/systemd/system/eink-text-ab-outcomes.timer",
               "/opt/eink-browser-solver/clip_service.py",
               "/opt/eink-browser-solver/solver-daemon.mjs",
               "/opt/eink-browser-solver/seq_match.py",
               "/opt/eink-browser-solver/package.json",
               "/opt/eink-browser-solver/package-lock.json",
               "/opt/eink-browser-solver/node_modules",
               "/home/topview/captcha/text_release.py",
               "/home/topview/captcha/restore_vision_baseline.py",
               "/home/topview/captcha/capture_text_snapshot.py",
               "/home/topview/captcha/text-shadow-service",
               "/home/topview/captcha/text-ab.json",
               "/home/topview/captcha/text-validation"]
        for raw in paths:
            src=Path(raw)
            if not src.exists():continue
            target=stage/"files"/src.relative_to("/")
            target.parent.mkdir(parents=True,exist_ok=True)
            if src.is_dir():shutil.copytree(src,target,symlinks=True)
            else:shutil.copy2(src,target)
        states={name:{key:subprocess.run(["systemctl",command,name],text=True,
                                       capture_output=True).stdout.strip()
                      for key,command in (("active","is-active"),("enabled","is-enabled"))}
                for name in ("eink-text-candidate","eink-text-shadow","eink-clip-service","eink-browser-solver",
                             "eink-text-ab-outcomes.timer")}
        metadata={"schema":1,"created_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  "accuracy_status":"best grouped candidate; unseen-target generalization unresolved",
                  "offline_exact":release_metadata["offline_exact"],
                  "release_name":release.name,"runtime_baseline":str(baseline),
                  "runtime_baseline_manifest_sha256":baseline_sha,"services":states,
                  "files":{str(f.relative_to(stage)):sha(f) for f in stage.rglob("*")
                           if f.is_file() and not f.is_symlink()}}
        (stage/"snapshot.json").write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
        verify(stage)
        stage.rename(dest)
    except BaseException:
        shutil.rmtree(stage);raise
    print(json.dumps({"snapshot":str(dest),"files":len(metadata["files"]),
                      "offline_exact":metadata["offline_exact"],
                      "manifest_sha256":sha(dest/"snapshot.json")}))


if __name__=="__main__":main()
