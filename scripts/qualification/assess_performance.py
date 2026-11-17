#!/usr/bin/env python3
"""Read-only evidence gate. It cannot create actors, workloads or performance samples.

Input is a JSON file matching docs/isolated-suite.md. Missing/inconsistent evidence is
INCONCLUSIVE, never a fabricated pass. 16/32 actors are always diagnostic.
"""
import argparse
import json
import math
from pathlib import Path

CASES = {"dispersed-clear", "dispersed-rain", "dense-clear", "dense-rain",
         "cold-read-reconnect", "long-save"}
CANDIDATES = {"redesigned", "pre-redesign", "no-traces"}


def percentile(values, q):
    if not values:
        raise ValueError("missing sample values")
    if any(not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0 for value in values):
        raise ValueError("samples must be nonnegative finite numbers")
    return sorted(values)[math.ceil(q * len(values)) - 1]


def evaluate(input_data):
    issues = []
    comparisons = []
    rows = input_data.get("runs", [])
    selected = {}
    for row in rows:
        try:
            key = (row["candidate"], row["actors"], row["case"])
            if key in selected:
                raise ValueError("duplicate attempt; preserve separate evidence and choose explicitly")
            if row["candidate"] not in CANDIDATES or row["actors"] not in (8, 16, 32):
                raise ValueError("unknown candidate or actor count")
            selected[key] = row
        except (KeyError, ValueError) as error:
            issues.append(f"invalid run row: {error}")
    for case in sorted(CASES):
        trio = [selected.get((name, 8, case)) for name in ("redesigned", "pre-redesign", "no-traces")]
        if any(row is None for row in trio):
            issues.append(f"{case}: missing redesigned/pre-redesign/no-traces parallel run")
            continue
        seeds = {row.get("seed") for row in trio}
        workloads = {row.get("workloadSha256") for row in trio}
        worlds = {row.get("worldPath") for row in trio}
        if None in seeds or len(seeds) != 1 or None in workloads or len(workloads) != 1:
            issues.append(f"{case}: seed/workload parity absent")
        if None in worlds or len(worlds) != 3:
            issues.append(f"{case}: candidate worlds must be separate; never mix v3 and v4 data")
    for actors in (16, 32):
        for case in ("dispersed-clear", "dispersed-rain", "dense-clear", "dense-rain"):
            if ("redesigned", actors, case) not in selected:
                issues.append(f"missing {actors}-actor diagnostic: {case}")
    healthy_notes = []
    results = []
    for (candidate, actors, case), row in sorted(selected.items()):
        failures = []
        try:
            if row["realClients"] < 2 or row["syntheticActors"] != actors - row["realClients"]:
                failures.append("two actual clients and explicitly labeled synthetic actors required")
            if len(set(row["syntheticActorIds"])) != row["syntheticActors"]:
                failures.append("missing or duplicate synthetic actor identifiers")
            if row["warmupSeconds"] < 600 or row["steadySeconds"] < 1200 or row["heapObservationSeconds"] < 1800:
                failures.append("10m warmup / 20m steady / 30m heap window incomplete")
            if row["worldSchema"] != ("v4" if candidate == "redesigned" else "v3" if candidate == "pre-redesign" else "none"):
                failures.append("candidate world schema mismatch")
            if not all(row.get(field) for field in ("sourceManifestSha256", "worldPath", "serverLog", "clientALog", "clientBLog", "renderer")):
                failures.append("missing independent client/server and frozen source/renderer evidence")
            tps = percentile(row["tps"], 0.05)
            if row["tpsWindowSeconds"] != 60 or len(row["tps"]) < 20:
                failures.append("sustained TPS needs >=20 independent 60-second steady windows")
            tick_p95 = percentile(row["tickMs"], 0.95)
            tick_p99 = percentile(row["tickMs"], 0.99)
            notes = row.get("healthyDurableNoteMs", [])
            note_p95 = percentile(notes, 0.95) if notes else None
            note_p99 = percentile(notes, 0.99) if notes else None
            client_frames = {key: percentile(row["clientFrameMs"][key], 0.95) for key in ("a", "b")}
            start_heap, end_heap = row["postGcHeapMiB"]
            if start_heap <= 0 or end_heap < 0:
                failures.append("post-GC heap floors invalid")
            if end_heap - start_heap > max(128, 0.10 * start_heap):
                failures.append("post-GC retained heap growth exceeds gate")
            if row["budgetViolations"]:
                failures.append("configured tile/record/encoded-byte/pinned/I-O budget violation")
            if row.get("outageIntervals"):
                failures.append("healthy note window must exclude outages; use the fault tier")
            if row.get("gcBookendInLatencySamples", True):
                failures.append("GC bookend collections must be excluded from latency windows")
            if actors == 8 and candidate == "redesigned":
                healthy_notes.extend(notes)
                if min(row["tps"]) < 19 or tick_p95 > 50 or tick_p99 > 100:
                    failures.append("eight-actor TPS/tick threshold")
                if client_frames["a"] > 50 or client_frames["b"] > 50:
                    failures.append("eight-actor per-client frame threshold")
                if note_p95 is None or note_p95 > 5000 or note_p99 > 15000:
                    failures.append("eight-actor healthy durable-note threshold or missing sample")
            metrics = {"tpsP05": tps, "tickP95Ms": tick_p95, "tickP99Ms": tick_p99,
                       "noteP95Ms": note_p95, "noteP99Ms": note_p99, "clientFrameP95Ms": client_frames}
        except (KeyError, ValueError, TypeError, IndexError) as error:
            failures.append(f"missing/invalid metric: {error}")
            metrics = {}
        if failures:
            issues.append(f"{candidate}/{actors}/{case}: " + "; ".join(failures))
        results.append({"candidate": candidate, "actors": actors, "case": case,
                        "status": "diagnostic" if actors != 8 else "failed_or_inconclusive" if failures else "metrics_in_bounds",
                        "metrics": metrics, "issues": failures})
    if len(healthy_notes) < 100:
        issues.append(f"eight-actor redesigned healthy notes: {len(healthy_notes)} < 100")
    for case in CASES:
        redesign = next((r for r in results if r["candidate"] == "redesigned" and r["actors"] == 8 and r["case"] == case), None)
        if not redesign or not redesign["metrics"]:
            continue
        for baseline in ("pre-redesign", "no-traces"):
            old = next((r for r in results if r["candidate"] == baseline and r["actors"] == 8 and r["case"] == case), None)
            if old and old["metrics"]:
                for field in ("tickP95Ms", "tickP99Ms"):
                    prior = old["metrics"][field]
                    if prior and redesign["metrics"][field] > prior * 1.1:
                        comparisons.append(f"{case}: {field} > 110% of {baseline}; investigate")
    # A numeric gate is only one layer; human assessment of screenshots, errors,
    # save integrity and baseline regressions is required before any overall PASS.
    return {"status": "inconclusive" if issues else "numeric_eight_actor_gate_only",
            "issues": issues, "baselineInvestigations": sorted(comparisons), "runs": results,
            "warning": "No real-client, save-integrity or full-pack acceptance is inferred. 16/32 are diagnostics."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("refusing to overwrite a prior assessment")
    verdict = evaluate(json.loads(args.evidence.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as stream:
        json.dump(verdict, stream, indent=2, sort_keys=True)
        stream.write("\n")
    print(verdict["status"])


if __name__ == "__main__":
    main()
