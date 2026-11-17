"""Unrun source tests for the offline numeric assessor; included in verifyNativeSetup."""
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).parent / "qualification/assess_performance.py"
spec = importlib.util.spec_from_file_location("assess_performance", path)
assessor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assessor)


class QualificationAssessorTest(unittest.TestCase):
    def test_missing_evidence_never_passes(self):
        result = assessor.evaluate({"runs": []})
        self.assertEqual("inconclusive", result["status"])
        self.assertTrue(any("missing" in issue for issue in result["issues"]))

    def test_forged_same_world_cannot_pass_schema_parity(self):
        rows = []
        for candidate, schema in (("redesigned", "v4"), ("pre-redesign", "v3"), ("no-traces", "none")):
            rows.append({"candidate": candidate, "actors": 8, "case": "dense-clear",
                         "worldSchema": schema, "worldPath": "/same/valued/world"})
        result = assessor.evaluate({"runs": rows})
        self.assertEqual("inconclusive", result["status"])
        self.assertTrue(any("candidate worlds must be separate" in issue for issue in result["issues"]))

    def test_nearest_rank_and_invalid_samples(self):
        self.assertEqual(4, assessor.percentile([4, 1, 2, 3], 0.95))
        with self.assertRaises(ValueError):
            assessor.percentile([float("nan")], 0.95)
        with self.assertRaises(ValueError):
            assessor.percentile([], 0.99)


if __name__ == "__main__":
    unittest.main()
