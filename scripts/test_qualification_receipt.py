"""Unrun no-overwrite/approval-gate regressions for qualification receipts."""
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from qualification import receipt


class QualificationReceiptTest(unittest.TestCase):
    def setUp(self):
        scratch = receipt.ROOT / "build/qualification/test-fixtures"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_receipt_is_append_only(self):
        target = self.root / "run/input.json"
        receipt.atomic_new_json(target, {"status": "FROZEN_UNRUN"})
        with self.assertRaises(FileExistsError):
            receipt.atomic_new_json(target, {"status": "passed"})
        self.assertEqual("FROZEN_UNRUN", json.loads(target.read_text())["status"])

    def test_pass_requires_actual_evidence_and_human_approval_reference(self):
        attempt = self.root / "attempts/one"
        attempt.mkdir(parents=True)
        (attempt / "input.json").write_text(json.dumps({"runId": "one", "tier": "verifyFast"}))
        values = SimpleNamespace(directory=attempt, evidence=[], status="passed",
                                 approval_reference="explicit tier approval", reviewed=True, limitations=[])
        with patch.object(receipt, "OUTPUT", attempt.parent):
            with self.assertRaises(ValueError):
                receipt.verdict(values)
            evidence = attempt / "test-report.xml"
            evidence.write_text("<testsuite />")
            values.evidence = [evidence]
            values.approval_reference = ""
            with self.assertRaises(ValueError):
                receipt.verdict(values)
            self.assertFalse((attempt / "verdict.json").exists())


if __name__ == "__main__":
    unittest.main()
