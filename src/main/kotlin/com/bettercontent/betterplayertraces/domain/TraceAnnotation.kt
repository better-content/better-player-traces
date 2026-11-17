package com.bettercontent.betterplayertraces.domain

import net.minecraft.core.BlockPos
import java.util.Arrays
import java.util.UUID

/** Ingress and egress copy the optional echo so an accepted shard cannot be mutated by callers. */
class TraceAnnotation(
    val id: UUID,
    val text: String,
    val icon: String,
    val color: Int,
    position: BlockPos,
    targetBlock: BlockPos,
    val team: TraceTeam,
    val revision: Int,
    val createdByInternal: UUID,
    encodedEcho: ByteArray? = null,
) {
    val position: BlockPos = position.immutable()
    val targetBlock: BlockPos = targetBlock.immutable()
    private val echoBytes: ByteArray? = encodedEcho?.copyOf()
    val hasEcho: Boolean get() = echoBytes != null
    val encodedEcho: ByteArray? get() = echoBytes?.copyOf()

    fun copy(
        id: UUID = this.id, text: String = this.text, icon: String = this.icon, color: Int = this.color,
        position: BlockPos = this.position, targetBlock: BlockPos = this.targetBlock,
        team: TraceTeam = this.team, revision: Int = this.revision,
        createdByInternal: UUID = this.createdByInternal, encodedEcho: ByteArray? = this.echoBytes,
    ): TraceAnnotation = TraceAnnotation(id, text, icon, color, position, targetBlock, team, revision, createdByInternal, encodedEcho)

    override fun equals(other: Any?): Boolean = other is TraceAnnotation &&
        id == other.id && text == other.text && icon == other.icon && color == other.color &&
        position == other.position && targetBlock == other.targetBlock && team == other.team &&
        revision == other.revision && createdByInternal == other.createdByInternal &&
        Arrays.equals(echoBytes, other.echoBytes)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + icon.hashCode()
        result = 31 * result + color
        result = 31 * result + position.hashCode()
        result = 31 * result + targetBlock.hashCode()
        result = 31 * result + team.hashCode()
        result = 31 * result + revision
        result = 31 * result + createdByInternal.hashCode()
        result = 31 * result + Arrays.hashCode(echoBytes)
        return result
    }

    override fun toString(): String = "TraceAnnotation(id=$id, revision=$revision, position=$position)"
}
