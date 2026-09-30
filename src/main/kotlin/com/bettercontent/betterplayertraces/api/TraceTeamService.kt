package com.bettercontent.betterplayertraces.api

import com.bettercontent.betterplayertraces.domain.GLOBAL_TEAM
import com.bettercontent.betterplayertraces.domain.TraceTeam

interface TraceTeamService {
    fun teamFor(id: String?): TraceTeam
    fun canRead(playerTeam: TraceTeam, targetTeam: TraceTeam): Boolean
}

class DefaultTraceTeamService : TraceTeamService {
    override fun teamFor(id: String?): TraceTeam = GLOBAL_TEAM

    override fun canRead(playerTeam: TraceTeam, targetTeam: TraceTeam): Boolean = true
}
