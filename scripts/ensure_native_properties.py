"""Nonshipping first-launch setup: create native defaults only when absent."""
from pathlib import Path
import sys


def ensure_properties(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    properties = directory / "server.properties"
    try:
        with properties.open("x", encoding="utf-8") as output:
            output.write("# Native source fixture: remaining settings use Minecraft defaults.\n")
        return True
    except FileExistsError:
        if not properties.is_file():
            raise ValueError("Native server.properties exists but is not a regular file")
        return False


if __name__ == "__main__":
    ensure_properties(sys.argv[1])
