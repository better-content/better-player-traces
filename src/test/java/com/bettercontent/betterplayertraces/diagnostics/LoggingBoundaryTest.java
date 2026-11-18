package com.bettercontent.betterplayertraces.diagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Source/privacy invariant only; native behavior is covered by the owning full gate. */
final class LoggingBoundaryTest {
    @Test void authoritativeFeatureBoundaryAndPrivacyRemainExplicit() throws Exception {
        String source = Files.readString(Path.of("src/main/kotlin/com/bettercontent/betterplayertraces/logic/AnnotationService.kt"));
        source += Files.readString(Path.of("src/main/java/com/bettercontent/betterplayertraces/diagnostics/FeatureObservation.java"));
        assertTrue(source.contains("submitCreate(") && source.contains("submitUpdate(")
            && source.contains("submitDelete("), "all note mutations must use the shared publication boundary");
        assertTrue(source.contains("annotation.publish\", \"committed\", \"durable"),
            "only durable publication may be logged as accepted");
        assertTrue(source.contains("annotation.stage\", \"rejected"),
            "rejected staging must remain observable without reporting a commit");
        assertTrue(source.contains("STORAGE_CAPACITY"), "capacity rejection must remain explicit");
        assertFalse(source.contains("PlaytestLog.fields(\"text\""), "private/unobserved field in diagnostic source");
        assertFalse(source.contains("PlaytestLog.fields(\"owner\""), "private/unobserved field in diagnostic source");
        assertTrue(source.contains("enabled("), "guard before native diagnostic fields");
    }
}
