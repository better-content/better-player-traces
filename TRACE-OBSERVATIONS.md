# Trace attribution and anonymous proximity hints

## Player-facing contract

Trace sight identifies the inspected footprint stream with the creator's name left of the crosshair and a 16px head (including hat layer) right of it. Own marks say **Your traces**; unresolved names say **Unknown player’s traces**. The account UUID, not the displayed name, determines ownership. Annotations, guidance, blood pools and death echoes keep their existing presentation.

An inspection candidate must use the same resolved surface geometry as footprint rendering, be at most 12 blocks from the camera, in front of it, within 0.6 blocks of its look ray, and unobstructed. Ranking is perpendicular distance, along-ray distance, then a deterministic key. A competing owner must improve perpendicular distance by at least 20% continuously for 200ms. A lost owner cannot remain selected beyond 250ms, even with alternating challengers.

Outside sight, foreign surviving drawable marks within 8 horizontal / 3 vertical blocks and clear eye-to-mark visibility can produce **Someone has passed this way**, followed by the actual bound Reveal Traces key. Entry requires 500ms; one local encounter consumes one hint irrespective of owner/mark count. An encounter that begins with sight enabled is consumed silently. Rearming requires 10 continuously observed seconds outside the 12 horizontal / 4 vertical area, and another shown hint cannot begin until 60 seconds after the last actual presentation. Menus, F1, stale data, dimension changes and missing client state suppress hints; sight activation and area exit cancel pending/visible hints. Dimension changes clear encounter/selection state but preserve the session-wide 60-second cooldown; logout resets the whole session.

## Data and dependencies

- Requires `better-gameplay-notices` containing the `TRACES` theme and client-local transient API. Its discoveries retain FIFO priority; a new discovery discards a transient without replay. Pending transients expire after five seconds; validity is rechecked through presentation, and the 3.2-second display/sound/cooldown starts only on first display.
- Trace protocol: `better_player_traces_v9`; notices protocol: `2`. Both ends must have matching providers. Existing mod artifact versions/filenames are retained; do not mix old provider binaries with this feature.
- Forge dev/runtime minimum is 47.4.22, matching gameplay-notices (the previous traces dev pin was 47.4.13).
- Each tile page has a bounded dictionary of UUID + optional validated account name and compact references. Server pages use 512 records to retain space for worst-case attribution. Paging/revisions/evictions remain authoritative. No unrelated profiles or texture properties are transmitted.
- Names come only from online players or the server's existing UUID profile cache. Vanilla asynchronous client skin facilities provide portraits; UUID-default skins appear immediately. Per-owner cached requests use generation tokens so old callbacks cannot repopulate a new world.
- Storage schema is unchanged: attribution uses the persisted `sourcePlayerInternal` UUID. No migration, rewrite or bulk external name recovery occurs.
- Hidden subscription polling stays at 100 ticks. Successful unchanged responses confirm freshness; data becomes stale after ten seconds. Added observation work restricts candidates to nearby tiles once per client tick, not a full collection scan per render.

## Local verification

Use the existing Java 17 SDK and repository wrapper. Build the sibling notices provider first, then:

```sh
./gradlew --offline --no-daemon --max-workers=2 verifyFast assemble
./gradlew --offline --no-daemon --max-workers=2 verifyFull
```

Canonical production dependency resolution follows `BC_CUSTOM_MOD_JAR_DIR` or the sibling `build/libs/` convention, failing rather than silently falling back. The active-pack manifest has a provider-ordering edge from traces to gameplay-notices; this does not deploy or activate anything.

The existing CI pack baseline predates the new notices client API. Before publishing/merging the coordinated repositories, update the pinned verified provider baseline to an immutable release containing that API. Do not substitute a floating provider branch or pretend the old baseline qualifies this feature.

### Disposable real-client observation fixture

`src/test/.../TraceObservationNativeFixture.kt` is nonshipping and inert unless `traces.observationFixture=true`. It seeds two cached named creators, an unresolved creator and the observer's own mark on a fresh dedicated-server platform. The connected client uses the normal network, surfaces, sight selector and notice system; it never injects a selected owner. It checks selected identities and captures rendered PNGs for both names, unknown/self wording, F1 and GUI scale 3. It rebinds Reveal Traces to G to exercise key-component rendering.

For mapped development launches only, explicitly build notices' `mappedDevJar` and copy it under the canonical provider filename in an **owned temporary override directory**. Its SRG production JAR is not a raw mapped-development runtime. Never deploy this mapped fixture artifact.

Prepare distinct fresh server/client working directories under an owned directory with an accepted disposable EULA, loopback-only server port 25585, offline-mode fixture login and flat-world settings. Export the actual ForgeGradle JavaExec commands:

```sh
BC_CUSTOM_MOD_JAR_DIR=/absolute/owned/providers ./gradlew --offline --no-daemon --max-workers=2 \
  verifyFast exportObservationLaunches \
  -I scripts/visual-validation/export-observation-launches.gradle \
  -PtracesObservationFixture=true -PtracesFixtureRoot=/absolute/owned/fixture
python3 scripts/visual-validation/run-observation-launches.py /absolute/owned/fixture
```

The export preserves Forge module/classpath/source-set bindings and allows both prepared native processes to run without competing for Gradle's project lock. A usable display must already be available; in Worklane use an authenticated Xvfb TCP display with `-nolock -nolisten unix`, an owned Xauthority file and user-space temporary directories. No `/tmp` writes or shared worlds are needed. Without a usable GPU, software GL is a supported diagnostic fallback. If Forge's optional early progress window times out under software GL, set `earlyWindowControl = false` in the fresh owned client’s `config/fml.toml`; the real game window must still render normally. Use simulation distance 5 or above in client options (4 is invalid in vanilla 1.20.1).

The launcher retains failures, enforces startup/client/shutdown deadlines, refuses world/evidence reuse, and writes `native-observation.json`.

### Verification recorded 2026-10-28

`verifyFull assemble` passed with 117 JUnit tests, three Python setup checks, and all 12 required native GameTests. Notices passed 18 JUnit tests; focused thread/stance consumers passed 13 + 6 tests. The final fresh seeded client/server run exited 0/0 and captured seven real rendered screenshots; all 483 recorded source/class/resource fingerprints remained unchanged during that run. Local evidence, logs, artifact hashes and images are retained under `build/evidence/trace-observations-20261028/`; see `verification.json`. No pack JARs were deployed.

This is not full feature acceptance. Its maximum result is deliberately `partial_pass`: seeded profiles are not two genuinely connected owners, and this short fixture is not the complete acceptance matrix. Inspect the real screenshots. Full acceptance additionally needs actual owner disconnect/skin cases, crossing/occlusion/exit-reentry/dimension scenarios, narrow viewports/menus, thread and stance discoveries alongside hints, and the existing visual regressions. None of this qualifies a pack release or replaces exact-ZIP Smoke.
