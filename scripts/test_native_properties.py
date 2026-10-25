from pathlib import Path
import json
import unittest
import uuid
from ensure_native_properties import ensure_properties


class NativePropertiesTest(unittest.TestCase):
    def fresh(self):
        # Retain synthetic fixtures as well; no world/log cleanup is hidden in tests.
        root = Path(__file__).resolve().parents[1] / "build/native-setup-tests" / str(uuid.uuid4())
        root.mkdir(parents=True)
        return root

    def test_first_launch_has_readable_default_properties(self):
        root = self.fresh() / "new-attempt"
        self.assertTrue(ensure_properties(root))
        text = (root / "server.properties").read_text()
        self.assertIn("Minecraft defaults", text)
        settings = dict(line.split("=", 1) for line in text.splitlines()
                        if line and not line.startswith("#"))
        self.assertEqual(json.loads(settings.pop("generator-settings")), {
            "layers": [
                {"block": "minecraft:bedrock", "height": 1},
                {"block": "minecraft:dirt", "height": 2},
                {"block": "minecraft:grass_block", "height": 1},
            ],
            "biome": "minecraft:plains",
            "lakes": False,
            "features": False,
            "structure_overrides": [],
        })
        self.assertEqual(settings, {
            "level-type": "minecraft:flat",
            "generate-structures": "false",
            "level-seed": "0",
        })

    def test_existing_properties_preserved_byte_for_byte(self):
        root = self.fresh()
        payload = b"# Existing authored/native settings\nlevel-seed=8675309\ncustom=\xff\n"
        (root / "server.properties").write_bytes(payload)
        self.assertFalse(ensure_properties(root))
        self.assertEqual(payload, (root / "server.properties").read_bytes())

    def test_non_file_rejected_without_replacement(self):
        root = self.fresh()
        (root / "server.properties").mkdir()
        with self.assertRaises(ValueError):
            ensure_properties(root)
        self.assertTrue((root / "server.properties").is_dir())
