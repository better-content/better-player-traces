package com.bettercontent.betterplayertraces.client

import com.bettercontent.betterplayertraces.dto.TraceOwner

/** Pure tick-driven selection: rendering never scans the footprint stream. */
data class TraceAimCandidate(val owner: TraceOwner?, val perpendicular: Double, val along: Double, val key: String)

class TraceAimSelection {
    var selected: TraceAimCandidate? = null
        private set
    private var lastValid = Long.MIN_VALUE
    private var challenger: TraceOwner? = null
    private var challenging = false
    private var challengerSince = 0L

    fun update(candidates: List<TraceAimCandidate>, now: Long): TraceAimCandidate? {
        val ranked = candidates.sortedWith(compareBy<TraceAimCandidate> { it.perpendicular }.thenBy { it.along }.thenBy { it.key })
        val best = ranked.firstOrNull()
        val current = selected?.let { previous -> ranked.firstOrNull { it.owner?.id == previous.owner?.id } }
        if (current != null) { selected = current; lastValid = now }
        if (current == null && selected != null && now - lastValid >= 250) {
            selected = null
            challenging = false
        }
        if (best == null) {
            challenging = false
            if (selected != null && now - lastValid >= 250) selected = null
            return selected
        }
        if (selected == null) { selected = best; lastValid = now; return selected }
        if (best.owner?.id == selected?.owner?.id) { challenging = false; return selected }
        val better = current == null || (best.perpendicular < current.perpendicular && best.perpendicular <= current.perpendicular * 0.8)
        if (!better) { challenging = false; return selected }
        if (!challenging || challenger?.id != best.owner?.id) {
            challenger = best.owner; challengerSince = now; challenging = true
        } else if (now - challengerSince >= 200) {
            selected = best; lastValid = now; challenging = false
        }
        return selected
    }

    fun clear() { selected = null; challenging = false; lastValid = Long.MIN_VALUE }
}

/** An encounter is consumed on submission (or sight-on entry), cooldown starts only on actual display. */
class TraceEncounter {
    private var encountered = false
    private var eligibleSince: Long? = null
    private var outsideSince: Long? = null
    private var lastShown: Long? = null

    fun update(now: Long, fresh: Boolean, near: Boolean, inOuterArea: Boolean, sight: Boolean, presentationAllowed: Boolean): Boolean {
        if (!fresh) { eligibleSince = null; outsideSince = null; return false }
        if (inOuterArea) outsideSince = null else {
            if (outsideSince == null) outsideSince = now
            if (now - outsideSince!! >= 10_000) encountered = false
        }
        if (near && sight) { encountered = true; eligibleSince = null; return false }
        if (!near || sight || !presentationAllowed || encountered) { eligibleSince = null; return false }
        if (eligibleSince == null) eligibleSince = now
        if (now - eligibleSince!! < 500 || lastShown?.let { now - it < 60_000 } == true) return false
        encountered = true
        eligibleSince = null
        return true
    }

    fun shown(now: Long) { lastShown = now }
    fun reset(preserveCooldown: Boolean = false) {
        encountered = false; eligibleSince = null; outsideSince = null
        if (!preserveCooldown) lastShown = null
    }
}
