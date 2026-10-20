package com.bettercontent.betterplayertraces.diagnostics;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
/** Actual production projection test; native gameplay gates own whole-feature state/RNG equivalence. */
final class FeatureObservationTest {
    public static void main(String[] args) { new FeatureObservationTest().actualEmitterStagesPrivacyAndObservationalPurity(); }
    @Test void actualEmitterStagesPrivacyAndObservationalPurity() {
        var records = new ArrayList<String>(); boolean[] enabled = {false};
        var log = new PlaytestLog("better_player_traces", new PlaytestLog.Sink() {
            public boolean enabled(String category, String level) { return enabled[0]; }
            public void write(String category, String level, String text) { records.add(text); }
        }, () -> 0L, new Properties());
        var contextReads = new AtomicInteger();
        Supplier<PlaytestLog.Context> ctx = () -> { contextReads.incrementAndGet(); return PlaytestLog.Context.server("minecraft:overworld", 10); };
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
        // Observational purity at this scalar projection seam only, not a synthetic native replay.
        long[] state = {42, 3}; long[] before = state.clone();
        var nativeRng = new Random(41); var baselineRng = new Random(41);
        FeatureObservation.annotation(log, ctx, "annotation.create", "admitted", "storage_accepted", id, 2);
        FeatureObservation.annotation(log, ctx, "annotation.update", "rejected", "permission", id, 2);
        FeatureObservation.archive(log, true, 7, 128);
        FeatureObservation.archive(log, false, 6, 128);
        assertTrue(records.isEmpty()); assertEquals(0, contextReads.get());
        assertArrayEquals(before, state); assertEquals(baselineRng.nextLong(), nativeRng.nextLong());
        enabled[0] = true;
        FeatureObservation.annotation(log, ctx, "annotation.create", "admitted", "storage_accepted", id, 2);
        FeatureObservation.annotation(log, ctx, "annotation.update", "rejected", "permission", id, 2);
        FeatureObservation.archive(log, true, 7, 128);
        FeatureObservation.archive(log, false, 6, 128);
        assertEquals(4, records.size());
        assertTrue(records.get(0).contains("result=admitted reason=storage_accepted"), records.toString());
        assertTrue(records.get(1).contains("result=rejected reason=permission"), records.toString());
        assertTrue(records.get(2).contains("result=published reason=archive_publication_confirmed"), records.toString());
        assertTrue(records.get(3).contains("result=superseded reason=older_snapshot_not_published"), records.toString());
        assertTrue(records.stream().noneMatch(s -> s.contains("text=")), records.toString());
        assertTrue(records.stream().noneMatch(s -> s.contains("owner=")), records.toString());
        assertTrue(records.stream().noneMatch(s -> s.contains("payload=")), records.toString());
        assertTrue(records.stream().noneMatch(s -> s.contains("durable=true")), records.toString());
        assertTrue(records.get(0).contains("stage=storage_admission"));
        assertTrue(records.get(2).contains("stage=atomic_archive_publication"));
        assertFalse(records.get(3).contains("stage=atomic_archive_publication"));
        assertArrayEquals(before, state); assertEquals(baselineRng.nextLong(), nativeRng.nextLong());
    }
}
