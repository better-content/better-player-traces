#!/usr/bin/env python3
"""Supervised, real two-client observation of ONE disposable minimal-stack v4 world.

Requires separate explicit human approval for the two-client tier and an exported
launches.json (-PtracesTwoClients=true -PtracesObservationFixture=true). Does not
assert GUI/visual/gameplay success; review BOTH client logs and screenshots by hand.
No pack-facing JAR or full-pack launch is involved. Never run on a valued world.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "qualification"))
from receipt import candidate, digest

REPO = Path(__file__).resolve().parents[2]
ATTEMPTS = REPO / "build/qualification/attempts"


def filesums(root):
    result = {}
    if root.exists():
        for path in sorted(root.rglob("*")):
            if path.is_file() and path.name != "session.lock":
                result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def stop(process, graceful=False):
    if process is None or process.poll() is not None:
        return
    if graceful and process.stdin:
        try:
            process.stdin.write(b"stop\n")
            process.stdin.flush()
            process.wait(timeout=60)
            return
        except (OSError, subprocess.TimeoutExpired):
            pass
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def launch(spec, log, stdin):
    return subprocess.Popen(spec["command"], cwd=spec["cwd"], env=spec["environment"],
                            stdin=stdin, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("attempt", type=Path, help="previously frozen build/qualification/attempts/UUID")
    parser.add_argument("--approval-reference", required=True, help="identify tier-specific human authorization")
    parser.add_argument("--stage", choices=["initial", "restart"], required=True)
    parser.add_argument("--seconds", type=int, default=600, help="bounded supervised observation window")
    args = parser.parse_args()
    attempt = args.attempt.resolve(strict=True)
    if attempt.parent != ATTEMPTS.resolve() or not (attempt / "input.json").is_file():
        parser.error("must use a frozen, repository-local attempt; never point at a valued world")
    frozen = json.loads((attempt / "input.json").read_text())
    if frozen["tier"] != "two-client":
        parser.error("frozen input tier is not two-client")
    if candidate(REPO)["manifestSha256"] != frozen["source"]["manifestSha256"] or digest(Path(frozen["provider"]["path"])) != frozen["provider"]["sha256"]:
        parser.error("source/provider bytes changed after freeze; create a NEW attempt")
    root = attempt / "native"
    specs = json.loads((root / "launches.json").read_text())
    if not {"runServer", "runClientA", "runClientB"}.issubset(specs):
        parser.error("exported launch requires runServer and TWO independent clients")
    for name, directory in (("runServer", "server"), ("runClientA", "client-a"), ("runClientB", "client-b")):
        if Path(specs[name]["cwd"]).resolve() != (root / directory).resolve():
            parser.error(f"{name} working directory escapes its owned attempt")
        command = specs[name]["command"]
        if "--gameDir" in command and (command.index("--gameDir") + 1 >= len(command) or
                                      Path(command[command.index("--gameDir") + 1]).resolve() != (root / directory).resolve()):
            parser.error(f"{name} game directory escapes its owned attempt")
    world = root / "server/world"
    if frozen["world"] is not None and Path(frozen["world"]).resolve() != world.resolve():
        parser.error("world path differs from the frozen disposable world")
    if (any(path.is_symlink() for path in (root, root / "server", root / "client-a", root / "client-b", world, root / "server/server.properties"))
            or (world.exists() and any(path.is_symlink() for path in world.rglob("*")))):
        parser.error("qualification processes/world must not be reached through a symlink")
    server_properties = root / "server/server.properties"
    if args.stage == "initial" and world.exists():
        parser.error("initial stage refuses existing world; retain it under its original attempt")
    if args.stage == "restart" and (not (world / "level.dat").is_file() or not (attempt / "initial-evidence.json").is_file()):
        parser.error("restart requires same prior disposable v4 world and initial evidence")
    if not server_properties.is_file() or "online-mode=false" not in server_properties.read_text().splitlines() or "level-name=world" not in server_properties.read_text().splitlines():
        parser.error("prepare dedicated offline-only server.properties with online-mode=false and level-name=world")
    if args.seconds not in range(30, 3601):
        parser.error("observation seconds must be between 30 and 3600")
    if specs["runClientA"]["cwd"] == specs["runClientB"]["cwd"]:
        parser.error("clients must have separate working directories")
    output = attempt / f"{args.stage}-evidence.json"
    if output.exists() or any((attempt / f"{args.stage}-{name}.log").exists() for name in ("server", "client-a", "client-b")):
        parser.error("cannot overwrite stage evidence; freeze a new attempt for a rerun")
    for name in ("runServer", "runClientA", "runClientB"):
        Path(specs[name]["cwd"]).mkdir(parents=True, exist_ok=True)
    launch_hash = digest(root / "launches.json")
    if args.stage == "restart" and json.loads((attempt / "initial-evidence.json").read_text())["launchSha256"] != launch_hash:
        parser.error("restart command changed after initial run")
    receipt = {"status": "inconclusive", "stage": args.stage, "launchSha256": launch_hash,
               "scope": "two actual client processes, NOT visual or gameplay acceptance",
               "approvalReference": args.approval_reference, "sourceManifest": frozen["source"]["manifestSha256"],
               "startedUtc": datetime.now(timezone.utc).isoformat(), "world": str(world),
               "beforeWorldFiles": filesums(world), "clients": {}}
    server = None
    clients = []
    try:
        with (attempt / f"{args.stage}-server.log").open("x") as sl, \
             (attempt / f"{args.stage}-client-a.log").open("x") as al, \
             (attempt / f"{args.stage}-client-b.log").open("x") as bl:
            server = launch(specs["runServer"], sl, subprocess.PIPE)
            deadline = time.monotonic() + 360
            while "Done (" not in (attempt / f"{args.stage}-server.log").read_text(errors="replace"):
                if server.poll() is not None:
                    raise RuntimeError(f"server exited during startup: {server.returncode}")
                if time.monotonic() > deadline:
                    raise TimeoutError("server startup exceeded 360s")
                time.sleep(1)
            clients = [launch(specs["runClientA"], al, subprocess.DEVNULL),
                       launch(specs["runClientB"], bl, subprocess.DEVNULL)]
            end = time.monotonic() + args.seconds
            while time.monotonic() < end:
                if server.poll() is not None or any(client.poll() is not None for client in clients):
                    raise RuntimeError("server/client exited before the supervised observation window ended")
                time.sleep(1)
            receipt["status"] = "evidence_only"
    except Exception as error:
        receipt["error"] = repr(error)
        raise
    finally:
        for client in clients:
            stop(client)
        stop(server, graceful=True)
        receipt["serverExit"] = None if server is None else server.returncode
        receipt["clients"] = {
            name: {"exit": client.returncode, "log": str(attempt / f"{args.stage}-{name}.log"),
                   "screenshots": [str(path) for path in sorted((root / name / "screenshots").glob("*.png"))]}
            for name, client in zip(("client-a", "client-b"), clients)
        }
        receipt["afterWorldFiles"] = filesums(world)
        receipt["endedUtc"] = datetime.now(timezone.utc).isoformat()
        if len(clients) != 2 or server is None or server.returncode != 0:
            receipt["status"] = "inconclusive"
        with output.open("x") as stream:
            json.dump(receipt, stream, indent=2, sort_keys=True)
            stream.write("\n")


if __name__ == "__main__":
    main()
