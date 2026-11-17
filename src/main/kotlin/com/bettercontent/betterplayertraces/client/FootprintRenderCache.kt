package com.bettercontent.betterplayertraces.client

import com.bettercontent.betterplayertraces.dto.VisibleTraceDto
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import kotlin.math.floor

data class CachedFootprint(val mark: TraceVisualMark, val quad: SurfaceQuad, val bounds: AABB)
data class FootprintCellKey(val x: Int, val z: Int) : Comparable<FootprintCellKey> {
    override fun compareTo(other: FootprintCellKey): Int = compareValuesBy(this, other, FootprintCellKey::x, FootprintCellKey::z)
}
data class CachedFootprintCell(val key: FootprintCellKey, val bounds: AABB, val footprints: List<CachedFootprint>)
data class FootprintRenderData(val cells: List<CachedFootprintCell> = emptyList(), val sourceCount: Int = 0)

/**
 * Stable 64x64-block render cells using one canonical footprint primitive.
 *
 * The cache key deliberately ignores payload ordering: server distance sorting must not invalidate geometry or
 * produce the old clear/rebuild flicker. Tile snapshots can change only affected cells through [invalidateCell].
 */
object FootprintRenderCache {
    internal const val CELL_SIZE_BLOCKS = 64

    private var levelIdentity: Level? = null
    private var contentRevision: Long = Long.MIN_VALUE
    private var loginTime: Long = Long.MIN_VALUE
    private var recencyWindow: Long = Long.MIN_VALUE
    private var data = FootprintRenderData()
    private val cells = mutableMapOf<FootprintCellKey, CachedFootprintCell>()
    private val invalidatedCells = mutableSetOf<FootprintCellKey>()
    var terrainRevision: Long = 0L
        private set

    fun renderData(level: Level, traces: List<VisibleTraceDto>, revision: Long,
                   local: (FootprintCellKey) -> List<VisibleTraceDto> = { key -> traces.filter { cellKey(it.x, it.z) == key } }): FootprintRenderData =
        renderData(level, { traces }, revision, local)

    fun renderData(level: Level, all: () -> List<VisibleTraceDto>, revision: Long,
                   local: (FootprintCellKey) -> List<VisibleTraceDto>): FootprintRenderData {
        val session = TracesClientState.sessionLoginGameTime
        val window = com.bettercontent.betterplayertraces.config.TracesConfig.client.recencyWindowMinutes.get().toLong()
        if (levelIdentity !== level || loginTime != session || recencyWindow != window ||
            revision != contentRevision && invalidatedCells.isEmpty()) {
            rebuildAll(level, all())
        } else if (invalidatedCells.isNotEmpty()) rebuildInvalidated(level, local)
        contentRevision = revision
        loginTime = session
        recencyWindow = window
        return data
    }

    fun invalidateCell(chunkX: Int, chunkZ: Int) {
        invalidatedCells += FootprintCellKey(Math.floorDiv(chunkX, 4), Math.floorDiv(chunkZ, 4))
    }

    fun invalidateTerrainCell(chunkX: Int, chunkZ: Int) {
        invalidateCell(chunkX, chunkZ)
        terrainRevision++
    }

    fun clear() {
        data = FootprintRenderData()
        contentRevision = Long.MIN_VALUE
        loginTime = Long.MIN_VALUE
        recencyWindow = Long.MIN_VALUE
        cells.clear()
        invalidatedCells.clear()
        terrainRevision++
        levelIdentity = null
    }

    private fun rebuildAll(level: Level, traces: List<VisibleTraceDto>) {
        val started = System.nanoTime()
        levelIdentity = level
        invalidatedCells.clear()
        cells.clear()
        val resolved = resolve(level, traces)
        resolved.groupBy { footprint -> cellKey(footprint.mark.trace.x, footprint.mark.trace.z) }
            .forEach { (key, footprints) -> cells[key] = CachedFootprintCell(key, combinedBounds(footprints), footprints) }
        publish(started)
    }

    private fun rebuildInvalidated(level: Level, local: (FootprintCellKey) -> List<VisibleTraceDto>) {
        val started = System.nanoTime()
        val dirty = invalidatedCells.toSet()
        invalidatedCells.clear()
        dirty.forEach { key ->
            val resolved = resolve(level, local(key))
            if (resolved.isEmpty()) cells.remove(key)
            else cells[key] = CachedFootprintCell(key, combinedBounds(resolved), resolved)
        }
        publish(started)
    }

    internal fun localFootprints(level: Level, traces: List<VisibleTraceDto>): List<CachedFootprint> =
        resolve(level, traces.filter { it.strength > 0f })

    private fun resolve(level: Level, traces: List<VisibleTraceDto>): List<CachedFootprint> =
        TraceVisualModel.marks(
            traces,
            1f,
            0f,
            TracesClientRenderer.MAX_RENDERED_FOOTPRINTS,
            TracesClientState.sessionLoginGameTime,
            com.bettercontent.betterplayertraces.config.TracesConfig.client.recencyWindowMinutes.get().toLong() * 1_200L,
        )
            .mapNotNull { mark ->
                val quad = SurfaceAnchorResolver.footprintQuad(
                    level, mark.trace, mark.angle, mark.lateralOffset, mark.longitudinalOffset,
                    mark.width.toDouble(), mark.length.toDouble(),
                )
                    ?: return@mapNotNull null
                CachedFootprint(mark, quad, bounds(quad))
            }

    private fun publish(started: Long) {
        val ordered = cells.toSortedMap().values.toList()
        data = FootprintRenderData(ordered, ordered.sumOf { it.footprints.size })
        if (com.bettercontent.betterplayertraces.config.TracesConfig.client.visualDiagnostics.get()) {
            TracesClientLog.LOGGER.info(
                "TRACES_FOOTPRINT_CACHE source={} cells={} rebuildMicros={}",
                data.sourceCount,
                data.cells.size,
                (System.nanoTime() - started) / 1_000,
            )
        }
    }

    private fun cellKey(x: Double, z: Double): FootprintCellKey = FootprintCellKey(
        floor(x / CELL_SIZE_BLOCKS).toInt(),
        floor(z / CELL_SIZE_BLOCKS).toInt(),
    )

    private fun bounds(quad: SurfaceQuad): AABB {
        val positions = quad.vertices.map { it.position }
        return AABB(
            positions.minOf { it.x }, positions.minOf { it.y } - 0.01, positions.minOf { it.z },
            positions.maxOf { it.x }, positions.maxOf { it.y } + 0.01, positions.maxOf { it.z },
        )
    }

    private fun combinedBounds(footprints: List<CachedFootprint>): AABB = AABB(
        footprints.minOf { it.bounds.minX }, footprints.minOf { it.bounds.minY }, footprints.minOf { it.bounds.minZ },
        footprints.maxOf { it.bounds.maxX }, footprints.maxOf { it.bounds.maxY }, footprints.maxOf { it.bounds.maxZ },
    )
}
