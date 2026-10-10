package com.fakerun.app.geo

import com.fakerun.app.model.*
import com.fakerun.app.simulation.RandomValues
import kotlin.math.*

data class PathSample(val humanDistanceMeters: Double, val centerDistanceMeters: Double, val routePoint: Wgs84Point, val humanPoint: Wgs84Point)
data class AvoidanceEvent(val startMeters: Double, val peakMeters: Double)
data class PathPosition(val routePoint: Wgs84Point, val humanPoint: Wgs84Point, val centerDistanceMeters: Double, val bearingDegrees: Double)
class HumanPath(val samples: List<PathSample>, val loopLengthMeters: Double, val avoidanceEvents: List<AvoidanceEvent>) {
    val distanceMeters get() = samples.last().humanDistanceMeters
    fun at(distanceMeters: Double): PathPosition {
        val distance = distanceMeters.coerceIn(0.0,this.distanceMeters)
        var low = 0
        var high = samples.lastIndex
        while (high-low > 1) {
            val mid = (low+high)/2
            if (samples[mid].humanDistanceMeters <= distance) low = mid else high = mid
        }
        val a = samples[low]
        val b = samples[high]
        val u = (distance-a.humanDistanceMeters)/(b.humanDistanceMeters-a.humanDistanceMeters)
        val before = samples[max(0,low-1)].humanPoint
        val after = samples[min(samples.lastIndex,high+1)].humanPoint
        return PathPosition(Geo.interpolate(a.routePoint,b.routePoint,u),Geo.interpolate(a.humanPoint,b.humanPoint,u),a.centerDistanceMeters+(b.centerDistanceMeters-a.centerDistanceMeters)*u,Geo.bearingDegrees(before,after))
    }
}

object RouteProcessor {
    private data class CenterSample(val distanceMeters: Double, val point: Wgs84Point)
    fun prepare(route: Route, config: SimulationConfig, seed: Long, cancelled: () -> Boolean = {false}): HumanPath {
        config.validate()
        require(route.closed) { "请先点击闭合路线" }
        require(route.nodes.size in 3..500) { "闭合路线须有 3～500 个节点" }
        val nodes = route.nodes.mapIndexed { i, point ->
            try { CoordinateTransformer.toWgs84(point) } catch (error: IllegalArgumentException) { throw IllegalArgumentException("节点 ${i+1}：${error.message}") }
        }
        val center = mutableListOf<CenterSample>()
        val straightRegions = mutableListOf<ClosedFloatingPointRange<Double>>()
        fun append(point: Wgs84Point) {
            val previous = center.lastOrNull()
            val distance = if (previous == null) 0.0 else previous.distanceMeters+Geo.distanceMeters(previous.point,point)
            if (previous == null || distance-previous.distanceMeters > 1e-9) center += CenterSample(distance,point)
        }
        val entries = ArrayList<Wgs84Point>()
        val exits = ArrayList<Wgs84Point>()
        nodes.indices.forEach { i ->
            val previous = nodes[(i+nodes.size-1)%nodes.size]
            val point = nodes[i]
            val next = nodes[(i+1)%nodes.size]
            val incoming = Geo.deltaMeters(point,previous)* -1.0
            val outgoing = Geo.deltaMeters(point,next)
            require(incoming.lengthMeters >= 2 && outgoing.lengthMeters >= 2) { "节点 ${i+1} 相邻距离不足 2 米" }
            val turn = acos(((incoming.eastMeters*outgoing.eastMeters+incoming.northMeters*outgoing.northMeters)/(incoming.lengthMeters*outgoing.lengthMeters)).coerceIn(-1.0,1.0))
            require(turn <= Math.toRadians(150.0)) { "节点 ${i+1} 方向变化超过 150°，请增加转弯节点" }
            val trimMeters = min(3.0,min(incoming.lengthMeters/4,outgoing.lengthMeters/4))
            entries += Geo.offset(point,incoming*(-trimMeters/incoming.lengthMeters))
            exits += Geo.offset(point,outgoing*(trimMeters/outgoing.lengthMeters))
        }
        append(entries[0])
        nodes.indices.forEach { i ->
            val point = nodes[i]
            val entryVector = Geo.deltaMeters(point,entries[i])
            val exitVector = Geo.deltaMeters(point,exits[i])
            // 控制多边形弧长上界决定采样，曲线样本间距最多 0.5 米。
            val count = ceil((entryVector.lengthMeters+exitVector.lengthMeters)/0.25).toInt()
            for (j in 1..count) {
                val u = j.toDouble()/count
                append(Geo.offset(point,entryVector*((1-u).pow(2))+exitVector*(u*u)))
            }
            val lineStart = center.last().distanceMeters
            val end = entries[(i+1)%nodes.size]
            val start = exits[i]
            val lineCount = ceil(Geo.distanceMeters(start,end)/0.25).toInt()
            for (j in 1..lineCount) append(Geo.interpolate(start,end,j.toDouble()/lineCount))
            straightRegions += lineStart..center.last().distanceMeters
        }
        val loopLengthMeters = center.last().distanceMeters
        require(loopLengthMeters >= 100) { "平滑后闭合路线须至少 100 米" }
        val totalCenterMeters = loopLengthMeters*config.loopCount
        require(totalCenterMeters <= 50000) { "总展开中心路线超过 50 km，请减少圈数或路线长度" }
        fun centerAt(distanceMeters: Double): Wgs84Point {
            val d = if (distanceMeters >= totalCenterMeters) loopLengthMeters else ((distanceMeters%loopLengthMeters)+loopLengthMeters)%loopLengthMeters
            var low = 0
            var high = center.lastIndex
            while (high-low > 1) { val mid=(high+low)/2; if (center[mid].distanceMeters <= d) low=mid else high=mid }
            val a=center[low]; val b=center[high]
            return Geo.interpolate(a.point,b.point,(d-a.distanceMeters)/(b.distanceMeters-a.distanceMeters))
        }
        val available = (0 until config.loopCount).flatMap { loop -> straightRegions.map {
            max(it.start+loop*loopLengthMeters+10,loop*loopLengthMeters+10)..min(it.endInclusive+loop*loopLengthMeters-40,(loop+1)*loopLengthMeters-40)
        }.filter { it.endInclusive >= it.start } }
        val swayAmplitudeMeters = if (config.naturalSway) min(config.maxLateralOffsetMeters,0.15+0.2*RandomValues.unit(seed,1,0)) else 0.0
        val events = mutableListOf<AvoidanceEvent>()
        fun event(start: Double, index: Long) {
            val peakLimit = min(2.5,config.maxLateralOffsetMeters-swayAmplitudeMeters)
            if (peakLimit < 1.2) return
            val peak = (1.2+(peakLimit-1.2)*RandomValues.unit(seed,2,index)) * if (RandomValues.unit(seed,3,index)<0.5) -1 else 1
            events += AvoidanceEvent(start,peak)
        }
        if (config.avoidance && config.maxLateralOffsetMeters >= 1.2) {
            var candidate=0.0; var index=0L
            while (candidate < totalCenterMeters) {
                candidate += 80+80*RandomValues.unit(seed,4,index)
                if (available.any {candidate in it} && events.all {candidate>=it.startMeters+30}) event(candidate,index)
                index++
            }
            if (events.isEmpty() && available.isNotEmpty()) event(available.first().start,0)
        }
        val waveLengthMeters=4+4*RandomValues.unit(seed,5,0)
        val phase=2*PI*RandomValues.unit(seed,6,0)
        fun humanAt(centerDistanceMeters: Double, routePoint: Wgs84Point): Wgs84Point {
            val taper=RandomValues.smoothStep5((min(centerDistanceMeters,totalCenterMeters-centerDistanceMeters)/5).coerceIn(0.0,1.0))
            var offsetMeters=swayAmplitudeMeters*sin(2*PI*centerDistanceMeters/waveLengthMeters+phase)*taper
            events.firstOrNull {centerDistanceMeters in it.startMeters..it.startMeters+30}?.let {
                val local=centerDistanceMeters-it.startMeters
                val weight=when {local<10 -> RandomValues.smoothStep5(local/10); local<20 -> 1.0; else -> 1-RandomValues.smoothStep5((local-20)/10)}
                offsetMeters += it.peakMeters*weight
            }
            val tangent=Geo.deltaMeters(centerAt(centerDistanceMeters-0.1),centerAt(centerDistanceMeters+0.1))
            return Geo.offset(routePoint,LocalVector(-tangent.northMeters,tangent.eastMeters)*(offsetMeters/tangent.lengthMeters))
        }
        val samples=mutableListOf<PathSample>()
        val count=ceil(totalCenterMeters/0.2).toInt()
        for (i in 0..count) {
            if (i%1024==0 && cancelled()) throw java.util.concurrent.CancellationException()
            val centerDistanceMeters=totalCenterMeters*i/count
            val routePoint=centerAt(centerDistanceMeters)
            val humanPoint=humanAt(centerDistanceMeters,routePoint)
            val previous=samples.lastOrNull()
            val deltaMeters=previous?.let {Geo.distanceMeters(it.humanPoint,humanPoint)} ?: 0.0
            val divisions=max(1,ceil(deltaMeters/0.5).toInt())
            if (previous==null) samples += PathSample(0.0,0.0,routePoint,humanPoint)
            else for (j in 1..divisions) {
                val u=j.toDouble()/divisions
                samples += PathSample(previous.humanDistanceMeters+deltaMeters*u,previous.centerDistanceMeters+(centerDistanceMeters-previous.centerDistanceMeters)*u,Geo.interpolate(previous.routePoint,routePoint,u),Geo.interpolate(previous.humanPoint,humanPoint,u))
            }
        }
        return HumanPath(samples.toList(),loopLengthMeters,events.toList())
    }
}
