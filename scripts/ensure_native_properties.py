"""Nonshipping first-launch setup: isolate native trace fixtures from world generation."""
from pathlib import Path
import json
import sys


FLAT_SETTINGS = {
    "layers": [
        {"block": "minecraft:bedrock", "height": 1},
        {"block": "minecraft:dirt", "height": 2},
        {"block": "minecraft:grass_block", "height": 1},
    ],
    "biome": "minecraft:plains",
    "lakes": False,
    "features": False,
    "structure_overrides": [],
}


def ensure_properties(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    properties = directory / "server.properties"
    try:
        with properties.open("x", encoding="utf-8") as output:
            # GameTest clears its arena near y=-60. Normal terrain can contain
            # pending dungeon/chest block entities there; clearing it before
            # their first load leaves DUMMY entities pointing at air. These
            # trace/persistence/weather tests do not exercise terrain generation.
            output.write(
                "# Native source fixture: remaining settings use Minecraft defaults.\n"
                "level-type=minecraft:flat\n"
                "generate-structures=false\n"
                "level-seed=0\n"
                f"generator-settings={json.dumps(FLAT_SETTINGS, separators=(',', ':'))}\n"
            )
        return True
    except FileExistsError:
        if not properties.is_file():
            raise ValueError("Native server.properties exists but is not a regular file")
        return False


if __name__ == "__main__":
    ensure_properties(sys.argv[1])
