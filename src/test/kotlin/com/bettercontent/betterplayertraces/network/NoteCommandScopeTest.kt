package com.bettercontent.betterplayertraces.network

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteCommandScopeTest {
    @Test fun commandCannotMutateAnotherDimensionOrNewConnection() {
        assertFalse(noteCommandMayStage("minecraft:overworld", "minecraft:the_nether", true),
            "server must verify dimension again when queued packet reaches the game thread")
        assertFalse(noteCommandMayStage("minecraft:overworld", "minecraft:overworld", false),
            "a delayed archive ACK cannot be sent to a replacement connection")
        assertFalse(noteCommandMayStage("", "minecraft:overworld", true))
        assertTrue(noteCommandMayStage("minecraft:overworld", "minecraft:overworld", true),
            "ordinary query-radius subscription changes do not invalidate a note command")
    }
}
