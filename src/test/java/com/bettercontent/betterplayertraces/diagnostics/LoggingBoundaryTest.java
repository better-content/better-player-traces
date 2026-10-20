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
        assertTrue(source.contains("annotation.create"), "missing authored boundary annotation.create");
        assertTrue(source.contains("annotation.update"), "missing authored boundary annotation.update");
        assertTrue(source.contains("annotation.delete"), "missing authored boundary annotation.delete");
        assertTrue(source.contains("storage_accepted"), "missing authored boundary storage_accepted");
        assertTrue(source.contains("capacity"), "missing authored boundary capacity");
        assertFalse(source.contains("PlaytestLog.fields(\"text\""), "private/unobserved field in diagnostic source");
        assertFalse(source.contains("PlaytestLog.fields(\"owner\""), "private/unobserved field in diagnostic source");
        assertTrue(source.contains("enabled("), "guard before native diagnostic fields");
    }
}
