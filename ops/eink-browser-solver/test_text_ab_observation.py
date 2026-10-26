import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from summarize_text_ab import summarize
from text_ab_control import main
from text_ab_router import DEFAULT_CONFIG


class ObservationTests(unittest.TestCase):
    def test_only_matching_live_phase_is_counted_and_no_credentials_exported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            records = []
            for i, (phase, source, served, code) in enumerate([
                ("p", "live", "gpu_fusion", "0"),
                ("p", "live", "cpu_fusion", "51"),
                ("p", "replay", "gpu_fusion", "0"),
                ("older", "live", "legacy", "0"),
            ], 1):
                stamp = f"2026-10-26T12-00-00-00{i}Z"
                records.append({"runStamp": stamp, "round": 1, "errorCode": code, "ticket": "private"})
                (root / f"r1-{stamp}-pick.json").write_text(json.dumps({"pick": {"ab": {
                    "phase": phase, "source": source, "cohort": "B", "served": served}}}))
            records.append(dict(records[0]))  # A repeated log row counts once.
            (root / "verify-outcomes.jsonl").write_text("\n".join(json.dumps(r) for r in records))
            report = summarize(root, "p")
            self.assertEqual(report["groups"]["B:gpu_fusion"]["accepted"], 1)
            self.assertEqual(report["groups"]["B:cpu_fusion"]["verifier_codes"], {"51": 1})
            self.assertEqual(sum(g["observed_rounds"] for g in report["groups"].values()), 2)
            self.assertNotIn("private", json.dumps(report))

    def test_no_new_outcomes_is_an_empty_report(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertEqual(summarize(temp, "p")["groups"], {})

    def test_failed_percentage_activation_restores_original_file(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "config.json"
            original = json.dumps(DEFAULT_CONFIG)
            path.write_text(original)
            with patch("sys.argv", ["control", "--config", str(path), "--percent", "10"]), \
                    patch("text_ab_control.urllib.request.urlopen", side_effect=ConnectionError):
                with self.assertRaises(ConnectionError):
                    main()
            self.assertEqual(path.read_text(), original)


if __name__ == "__main__":
    unittest.main()
