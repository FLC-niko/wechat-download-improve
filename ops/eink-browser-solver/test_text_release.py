"""Rollback and artifact-integrity regressions; no real systemd/network calls."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import text_release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name).resolve()
        self.source=self.root/"source";self.source.mkdir()
        for name in text_release.SERVICE_FILES:(self.source/name).write_text("# service\n")
        self.bundle=self.root/"bundle";self.bundle.mkdir()
        for name in ("model.pt","references.npz","training.json","fusion.json"):
            (self.bundle/name).write_text("test artifact")
        (self.bundle/"evaluation.json").write_text(json.dumps({
            "status":"offline-grouped-evaluation; prospective-validation-pending",
            "fusion":{"exact":.75,"rounds":100}}))
        (self.bundle/"manifest.json").write_text(json.dumps({
            "schema":1,"files":{p.name:text_release.digest(p)
                                  for p in self.bundle.iterdir()}}))

    def tearDown(self):self.temp.cleanup()

    def release(self,name):
        path=self.root/name
        text_release.create(argparse.Namespace(source=str(self.source),
            bundle=str(self.bundle),dest=str(path),min_exact=.7,python="python3"))
        return path

    def args(self,release,**kwargs):
        return argparse.Namespace(release=str(release),link=str(self.root/"current"),
                                  service=kwargs.get("service"),
                                  health_url=kwargs.get("health_url"))

    def test_corruption_cannot_be_activated(self):
        release=self.release("one")
        model=release/"bundle/model.pt";model.chmod(0o600);model.write_text("corrupted")
        with self.assertRaisesRegex(ValueError,"checksum"):
            text_release.activate(self.args(release))
        self.assertFalse((self.root/"current").exists())

    def test_existing_snapshot_never_overwritten(self):
        self.release("one")
        with self.assertRaisesRegex(ValueError,"overwrite"):self.release("one")

    def test_recovery_works_with_a_dangling_current_pointer(self):
        release=self.release("restored")
        (self.root/"current").symlink_to(self.root/"accidentally-deleted-release")
        result=text_release.activate(self.args(release))
        self.assertTrue(result["changed"])
        self.assertEqual((self.root/"current").resolve(),release)

    def test_failed_restart_restores_current_and_preserves_previous(self):
        one=self.release("one");two=self.release("two")
        text_release.activate(self.args(one))
        with patch.object(text_release.subprocess,"run",side_effect=[
                RuntimeError("restart failed"),None]) as run:
            with self.assertRaisesRegex(RuntimeError,"restart failed"):
                text_release.activate(self.args(two,service="isolated-test.service"))
            self.assertEqual(run.call_count,2)
        self.assertEqual((self.root/"current").resolve(),one)
        self.assertTrue((two/"release.json").exists())

    def test_wrong_model_health_rolls_back(self):
        one=self.release("one");two=self.release("two")
        text_release.activate(self.args(one))
        response=unittest.mock.MagicMock()
        response.__enter__.return_value.read.return_value=b'{"status":"ok","text_loaded":true,"text_version":"wrong"}'
        with patch.object(text_release.urllib.request,"urlopen",return_value=response),\
                patch.object(text_release.time,"sleep"):
            with self.assertRaisesRegex(ValueError,"selected text bundle"):
                text_release.activate(self.args(two,health_url="http://127.0.0.1:9994/health"))
        self.assertEqual((self.root/"current").resolve(),one)

    def test_successful_switch_keeps_both_snapshots_and_previous(self):
        one=self.release("one");two=self.release("two")
        text_release.activate(self.args(one));text_release.activate(self.args(two))
        self.assertEqual((self.root/"current.previous").resolve(),one)
        self.assertEqual((self.root/"current").resolve(),two)
        text_release.activate(self.args(one))
        self.assertEqual((self.root/"current").resolve(),one)
        self.assertTrue((two/"release.json").exists())
        self.assertTrue(text_release.verify(one));self.assertTrue(text_release.verify(two))

    def test_accuracy_gate_leaves_no_partial_release(self):
        path=self.root/"too-low"
        with self.assertRaisesRegex(ValueError,"below requested gate"):
            text_release.create(argparse.Namespace(source=str(self.source),
                bundle=str(self.bundle),dest=str(path),min_exact=.9,python="python3"))
        self.assertFalse(path.exists())
        self.assertFalse(list(self.root.glob("*.staging-*")))


if __name__=="__main__":unittest.main()
