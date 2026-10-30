#!/usr/bin/env python3
"""Run prepared ForgeGradle commands on a fresh owned fixture; this is NOT pack/release acceptance."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def terminate(process, graceful=False):
    if process is None or process.poll() is not None:
        return
    if graceful and process.stdin:
        try:
            process.stdin.write(b"stop\n")
            process.stdin.flush()
            process.wait(timeout=60)
            return
        except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
            pass
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    specs = json.loads((root / "launches.json").read_text())
    if (root / "server/observation-fixture").exists() or (root / "server-native.log").exists():
        parser.error("refusing to reuse a fixture world or overwrite native evidence")
    server = client = None
    receipt = {"status": "failed", "scope": "targeted seeded multiplayer rendering, not full acceptance"}
    try:
        with (root / "server-native.log").open("xb") as server_log, (root / "client-native.log").open("xb") as client_log:
            spec = specs["runServer"]
            server = subprocess.Popen(spec["command"], cwd=spec["cwd"], env=spec["environment"],
                                      stdin=subprocess.PIPE, stdout=server_log, stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + 180
            while "Done (" not in (root / "server-native.log").read_text(errors="replace"):
                if server.poll() is not None:
                    raise RuntimeError(f"server exited during startup: {server.returncode}")
                if time.monotonic() >= deadline:
                    raise TimeoutError("dedicated server startup exceeded 180 seconds")
                time.sleep(1)
            spec = specs["runClient"]
            client = subprocess.Popen(spec["command"], cwd=spec["cwd"], env=spec["environment"],
                                      stdin=subprocess.DEVNULL, stdout=client_log, stderr=subprocess.STDOUT, start_new_session=True)
            client.wait(timeout=300)
            receipt["clientExit"] = client.returncode
            text = (root / "client-native.log").read_text(errors="replace")
            required = ["anonymous-hint", "alice", "bob", "unknown", "own", "hidden-hud", "gui-scale-3"]
            images = [root / "client/screenshots" / f"observation-{name}.png" for name in required]
            if client.returncode != 0 or "TRACE_OBSERVATION_FIXTURE client_complete" not in text:
                raise RuntimeError("client did not complete the observation fixture")
            if not all(path.is_file() for path in images):
                raise RuntimeError("missing one or more rendered screenshots")
            receipt.update(status="partial_pass", screenshots=[str(path) for path in images])
    except Exception as error:
        receipt["error"] = str(error)
        raise
    finally:
        terminate(client)
        terminate(server, graceful=True)
        receipt["serverExit"] = None if server is None else server.returncode
        if receipt["status"] == "partial_pass" and receipt["serverExit"] != 0:
            receipt.update(status="failed", error="dedicated server did not shut down cleanly")
        (root / "native-observation.json").write_text(json.dumps(receipt, indent=2) + "\n")


if __name__ == "__main__":
    main()
