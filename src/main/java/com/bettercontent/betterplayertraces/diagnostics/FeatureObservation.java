package com.bettercontent.betterplayertraces.diagnostics;
import java.util.function.Supplier;
/** Pure owner-specific projection of already computed native outcomes; no gameplay writes or RNG. */
public final class FeatureObservation {
    private FeatureObservation() {}
    public static void annotation(PlaytestLog log, Supplier<PlaytestLog.Context> context, String event, String result, String reason, java.util.UUID id, int revision) {
        if (!log.enabled("annotation")) return;
        log.debug("annotation", event, context.get(), result, reason, () -> PlaytestLog.fields("annotation", id, "revision", revision, "stage", "storage_admission"));
    }
    public static void archive(PlaytestLog log, boolean published, long revision, int bytes) {
        if (!log.enabled("persistence")) return;
        log.event("persistence", "shard.publish", "server", published ? "published" : "superseded", published ? "archive_publication_confirmed" : "older_snapshot_not_published", () -> PlaytestLog.fields("revision", revision, "bytes", bytes, "stage", published ? "atomic_archive_publication" : "publication_skipped"));
    }
    public static void decision(PlaytestLog log, String category, String event, String result, String reason) {
        if (!log.enabled(category)) return;
        log.event(category, event, "server", result, reason, () -> "");
    }
}
