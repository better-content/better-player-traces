#!/usr/bin/env python3
"""Freeze isolated test inputs and retain operator-reviewed receipts. Does NOT execute tests.

Examples (after explicit tier-specific approval, not as implicit authorization):
  python3 scripts/qualification/receipt.py freeze --tier verifyFast --task-graph build/qualification/graph.txt
  python3 scripts/qualification/receipt.py verdict build/qualification/attempts/ID --status inconclusive --evidence logs/run.log
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
SUITE = Path(__file__).with_name("suite.json")
OUTPUT = ROOT / "build/qualification/attempts"


def utc():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def call(command, cwd=ROOT):
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, check=True).stdout.strip()


def source_inventory(root):
    """Include dirty tracked and untracked inputs; never hash generated/world directories."""
    paths = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                           cwd=root, capture_output=True, check=True).stdout.split(b"\0")
    result = {}
    for raw in paths:
        if not raw:
            continue
        relative = os.fsdecode(raw)
        candidate = root / relative
        if candidate.is_file() and not candidate.is_symlink():
            result[relative] = digest(candidate)
    return dict(sorted(result.items()))


def candidate(root):
    root = root.resolve(strict=True)
    files = source_inventory(root)
    canonical = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return {"root": str(root), "commit": call(["git", "rev-parse", "HEAD"], root),
            "status": call(["git", "status", "--porcelain=v1", "--untracked-files=all"], root),
            "files": files, "manifestSha256": hashlib.sha256(canonical).hexdigest()}


def memory():
    path = Path("/proc/meminfo")
    return next((line for line in path.read_text().splitlines() if line.startswith("MemTotal:")), None) if path.exists() else None


def cpu_model():
    path = Path("/proc/cpuinfo")
    return next((line.split(":", 1)[1].strip() for line in path.read_text().splitlines()
                 if line.startswith("model name")), platform.processor()) if path.exists() else platform.processor()


def optional(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=10)
        return (result.stdout + result.stderr).strip() if result.returncode == 0 else {"unavailable": result.stderr.strip(), "exit": result.returncode}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"unavailable": str(error)}


def atomic_new_json(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(content, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def freeze(args):
    assert args.tier in json.loads(SUITE.read_text())["tiers"]
    graph = args.task_graph.resolve(strict=True)
    provider = args.provider.resolve(strict=True)
    baselines = {}
    for name, path in (("preRedesign", args.old_source), ("noTraces", args.no_traces)):
        if path:
            baselines[name] = candidate(path)
    if args.tier == "performance" and set(baselines) != {"preRedesign", "noTraces"}:
        raise ValueError("performance freeze requires separate old-source and no-Traces repositories")
    if len({str(ROOT.resolve()), *(item["root"] for item in baselines.values())}) != len(baselines) + 1:
        raise ValueError("candidate and baselines must be distinct workspaces")
    configurations = [{"path": str(path.resolve(strict=True)), "sha256": digest(path.resolve())}
                      for path in args.config]
    run_id = str(uuid.uuid4())
    directory = OUTPUT / run_id
    # Distinct ID, never reuse or overwrite a prior attempt or its world.
    directory.mkdir(parents=True, exist_ok=False)
    try:
        receipt = {
            "schema": "better-player-traces-input-v1", "runId": run_id, "createdUtc": utc(),
            "tier": args.tier, "status": "FROZEN_UNRUN", "approved": False,
            "suiteManifest": digest(SUITE), "source": candidate(ROOT), "baselines": baselines,
            "provider": {"path": str(provider), "sha256": digest(provider)},
            "toolchain": {"java": optional(["java", "-version"]), "forge": "47.4.22",
                          "kotlinForForge": "4.12.0", "python": sys.version.split()[0]},
            "host": {"platform": platform.platform(), "cpu": cpu_model(),
                     "cpuCount": os.cpu_count(), "memory": memory(),
                     "gpu": optional(["nvidia-smi", "--query-gpu=name,memory.total,utilization.gpu", "--format=csv,noheader"]),
                     "renderer": args.renderer or "UNMEASURED"},
            "taskGraph": {"path": str(graph), "sha256": digest(graph)},
            "configuration": configurations,
            "world": str(args.world.resolve()) if args.world else None,
            "warning": "Input inventory only; no task, test, Minecraft process or benchmark was executed."
        }
        atomic_new_json(directory / "input.json", receipt)
    except BaseException:
        # Do not turn a partly written run into a reusable ID; retain evidence for review.
        raise
    print(directory)


def verdict(args):
    directory = args.directory.resolve(strict=True)
    if directory.parent != OUTPUT.resolve():
        raise ValueError("receipt must be a direct child of the repository-local attempts directory")
    frozen = json.loads((directory / "input.json").read_text())
    evidence = []
    for path in args.evidence:
        path = path.resolve(strict=True)
        if not path.is_file():
            raise ValueError(f"evidence must be an existing file: {path}")
        evidence.append({"path": str(path), "sha256": digest(path)})
    if args.status == "passed" and (not evidence or not args.reviewed):
        raise ValueError("cannot record pass without retained evidence and human review")
    if not args.approval_reference:
        raise ValueError("record the human's explicit approval reference for the actual execution tier")
    atomic_new_json(directory / "verdict.json", {
        "schema": "better-player-traces-verdict-v1", "runId": frozen["runId"],
        "tier": frozen["tier"], "createdUtc": utc(), "status": args.status,
        "approvalReference": args.approval_reference,
        "operatorReviewed": args.reviewed, "evidence": evidence,
        "limitations": args.limitations,
        "warning": "Operator-reported verdict; this utility does not run or validate the tier. Missing checks are inconclusive."
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    first = commands.add_parser("freeze", help="create immutable input inventory WITHOUT running a tier")
    first.add_argument("--tier", required=True, choices=list(json.loads(SUITE.read_text())["tiers"]))
    first.add_argument("--task-graph", required=True, type=Path, help="previously audited Gradle task graph file")
    first.add_argument("--provider", type=Path, default=ROOT.parent / "better-gameplay-notices/build/libs/better-gameplay-notices-1.0.1.jar")
    first.add_argument("--old-source", type=Path)
    first.add_argument("--no-traces", type=Path)
    first.add_argument("--config", type=Path, action="append", default=[])
    first.add_argument("--world", type=Path, help="new disposable world location; no world is created by freeze")
    first.add_argument("--renderer", help="observed OpenGL renderer string, not an inferred GPU")
    first.set_defaults(action=freeze)
    last = commands.add_parser("verdict", help="write one operator-reviewed result, never overwrite")
    last.add_argument("directory", type=Path)
    last.add_argument("--status", choices=["passed", "failed", "inconclusive"], required=True)
    last.add_argument("--approval-reference", required=True)
    last.add_argument("--reviewed", action="store_true")
    last.add_argument("--evidence", type=Path, action="append", default=[])
    last.add_argument("--limitations", action="append", default=[])
    last.set_defaults(action=verdict)
    args = parser.parse_args()
    try:
        args.action(args)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(2, f"qualification receipt refused: {error}\n")


if __name__ == "__main__":
    main()
