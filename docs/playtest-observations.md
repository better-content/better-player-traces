# Bounded playtest observations

## Authority and feature scope

This repository owns the observations at `AnnotationService and TraceStorageManager`. Authoritative source remains the
behavior owner; the local full gate tests native behavior. Logging source/privacy contracts
are not pack runtime, performance or visual acceptance.

Existing annotation ID/revision identifies create/update/delete storage admission versus permission/capacity/missing rejection. Annotation admission is not durable archive publication. shard.publish occurs only after TraceArchive.publish returns; actual existing failure stacks stay visible. No annotation text, icon content, author UUID, echo clip or filesystem path is logged. Shard records omit precise shard location, so same-revision concurrent shards are not uniquely correlated by this summary.

## Controls, bounds and lifecycle

The existing loader SLF4J backend owns `bc.playtest.better_player_traces.*`; no runtime provider
is bundled. Startup reports real mod version, effective loaded instrumentation and own-artifact
source metadata when available, otherwise UNKNOWN. Metadata is bounded and read only from this
mod's exact Forge ModFile at initialization. Normal standalone logging stays INFO; an explicitly
selected DEBUG profile enables causal records. One process-local instance shares 120 detail
records/minute, burst20, bounded256 aggregation keys and 2048-character single-line records.
Native END-server-tick boundaries coalesce monotonic30-second summaries; server stop clears
keys without replenishing the bucket or rearming TRACE. Actual failures preserve existing
normal severity/stack traces independently of detail suppression.

Focused TRACE remains default off and requires `bc.playtest.trace.better_player_traces.dimension`
and exactly one `.subject` UUID or `.pos=x,y,z`; the once-per-process window begins on the first
matching detail and expires at60seconds/1000records, also constrained by the common bucket.
These feature boundaries currently emit causal DEBUG, not per-tick/per-frame TRACE. The helper
supports focused details but a focus property does not invent an uninstrumented native observer.
Hot diagnostics guard before native context/field creation; no extra inventory/chunk scan,
RNG draw, entity construction, packet or persistent ledger is added.

## Verification boundary

The unchanged shared helper contract covers off/lazy behavior, escaping/truncation,
budget/cardinality/overflow/fakeclock/reset/focus and own-metadata validation. The owner-specific
source contract asserts actual event seams/privacy markers, not native success. Existing native
feature tests and the owning documented full gate remain required before publication. Exact
candidate gameplay, paired native UI review where relevant and performance acceptance are
separate; do not infer them from a causal log or a source-only pass.
