package com.fakerun.app.simulation

import com.fakerun.app.geo.RouteProcessor
import com.fakerun.app.model.*
import kotlin.math.*

object SessionPlanner {
    private data class FixedBlock(val startMeters: Double, val curve: SpeedCurve, val mode: MotionMode, val stopIndex: Int? = null)
    private data class Candidate(val segments: List<MotionSegment>, val movingSeconds: Double, val walkingSeconds: Double, val signature: List<Int>) {
        val ratio get() = walkingSeconds/movingSeconds
    }
    private class Infeasible(message: String): IllegalArgumentException(message)

    fun prepare(route: Route, config: SimulationConfig, seed: Long, cancelled: () -> Boolean = {false}): PreparedSimulation {
        val path=RouteProcessor.prepare(route,config,seed,cancelled)
        val low=max(3,config.stopDurationSeconds-5)
        val high=min(120,config.stopDurationSeconds+5)
        val stops=(1..config.stopCount).map { StopEvent(it,path.distanceMeters*it/(config.stopCount+1),low+floor(RandomValues.unit(seed,10,it.toLong())*(high-low+1)).toInt()) }
        if (config.walkTimeRatio==0.0) {
            val candidate=build(config,seed,path.distanceMeters,stops,null)
            return PreparedSimulation(route,config,seed,path,stops,null,candidate.segments,candidate.movingSeconds,0.0)
        }
        var previous: Candidate?=null
        var previousWidthMeters=0.0
        var lastReason="步行巡航不足 30 秒"
        val minimumRatio=max(0.0,config.walkTimeRatio-0.01)
        for (widthMeters in 1..ceil(path.distanceMeters).toInt()) {
            if (cancelled()) throw java.util.concurrent.CancellationException()
            val width=widthMeters.toDouble().coerceAtMost(path.distanceMeters)
            val walking=WalkingRegion((path.distanceMeters-width)/2,(path.distanceMeters+width)/2)
            val candidate=try { build(config,seed,path.distanceMeters,stops,walking) } catch (error: Infeasible) { lastReason=error.message!!; previous=null; continue }
            val bracketed=previous?.let {it.ratio<minimumRatio && candidate.ratio>=minimumRatio && it.signature==candidate.signature} ?: false
            if ((candidate.ratio >= minimumRatio && candidate.ratio <= config.walkTimeRatio+0.01) || bracketed) {
                var selected=candidate
                var selectedWidth=width
                val prior=previous
                if (prior!=null && prior.ratio<minimumRatio && prior.signature==candidate.signature) {
                    var left=previousWidthMeters
                    var right=width
                    repeat(30) {
                        val mid=(left+right)/2
                        val region=WalkingRegion((path.distanceMeters-mid)/2,(path.distanceMeters+mid)/2)
                        val refined=try {build(config,seed,path.distanceMeters,stops,region)} catch (_: Infeasible) {null}
                        if (refined!=null && refined.signature==candidate.signature && refined.ratio>=minimumRatio) {
                            right=mid
                            if (refined.ratio<=config.walkTimeRatio+0.01) {selected=refined; selectedWidth=mid}
                        } else left=mid
                    }
                }
                if (abs(selected.ratio-config.walkTimeRatio)<=0.0100000001) {
                    val region=WalkingRegion((path.distanceMeters-selectedWidth)/2,(path.distanceMeters+selectedWidth)/2)
                    return PreparedSimulation(route,config,seed,path,stops,region,selected.segments,selected.movingSeconds,selected.walkingSeconds)
                }
            }
            // 分段数量与停点归属变化时不跨越间断进行二分。
            previous=candidate
            previousWidthMeters=width
        }
        throw IllegalArgumentException("步行占比无法满足：$lastReason；请增加路线长度、修改比例或减少停止次数")
    }

    fun resume(plan: PreparedSimulation, startMeters: Double, completedStops: Set<Int>): List<MotionSegment> =
        build(plan.config,plan.seed,plan.path.distanceMeters,plan.stops.filter {it.index !in completedStops},plan.walkingRegion,startMeters,plan.segments).segments

    fun modeAt(config: SimulationConfig, walking: WalkingRegion?, distanceMeters: Double): MotionMode =
        if (config.walkTimeRatio>0 && walking!=null && distanceMeters>=walking.startMeters && distanceMeters<walking.endMeters) MotionMode.WALK else MotionMode.RUN

    private fun build(config: SimulationConfig, seed: Long, totalMeters: Double, stops: List<StopEvent>, walking: WalkingRegion?, startMeters: Double=0.0, originalSegments: List<MotionSegment>?=null): Candidate {
        val runSpeed=config.runSpeedKmh/3.6
        val walkSpeed=config.walkSpeedKmh/3.6
        fun base(mode: MotionMode) = if (mode==MotionMode.WALK) walkSpeed else runSpeed
        fun mode(distance: Double) = modeAt(config,walking,distance)
        val blocks=mutableListOf<FixedBlock>()
        blocks += FixedBlock(startMeters,SpeedCurve.transition(0.0,base(mode(startMeters))),MotionMode.TRANSITION)
        blocks += FixedBlock(totalMeters-SpeedCurve.transition(base(mode(totalMeters-1e-6)),0.0).distanceMeters,SpeedCurve.transition(base(mode(totalMeters-1e-6)),0.0),MotionMode.TRANSITION)
        if (walking!=null) {
            if (walking.startMeters>startMeters) {
                val curve=SpeedCurve.transition(runSpeed,walkSpeed)
                blocks += FixedBlock(walking.startMeters-curve.distanceMeters,curve,MotionMode.TRANSITION)
            }
            if (walking.endMeters>startMeters) blocks += FixedBlock(walking.endMeters,SpeedCurve.transition(walkSpeed,runSpeed),MotionMode.TRANSITION)
        }
        stops.forEach { stop ->
            if (stop.humanDistanceMeters < startMeters-1e-6) throw Infeasible("用户干预越过停止锚点 ${stop.index}，无法保持剩余事件")
            val speed=base(mode(stop.humanDistanceMeters))
            val deceleration=SpeedCurve.transition(speed,0.0)
            blocks += FixedBlock(stop.humanDistanceMeters-deceleration.distanceMeters,deceleration,MotionMode.TRANSITION,stop.index)
            blocks += FixedBlock(stop.humanDistanceMeters,SpeedCurve(0.0,0.0,stop.durationSeconds.toDouble()),MotionMode.DWELL,stop.index)
            blocks += FixedBlock(stop.humanDistanceMeters,SpeedCurve.transition(0.0,speed),MotionMode.TRANSITION,stop.index)
        }
        val ordered=blocks.sortedWith(compareBy<FixedBlock> {it.startMeters}.thenBy { if (it.mode==MotionMode.DWELL) 0 else 1 })
        var distanceMeters=startMeters
        var elapsedSeconds=0.0
        var cruiseIndex=0
        val segments=mutableListOf<MotionSegment>()
        val signature=mutableListOf<Int>()
        fun append(curve: SpeedCurve, mode: MotionMode, stopIndex: Int?=null, region: Int?=null) {
            segments += MotionSegment(mode,elapsedSeconds,distanceMeters,curve,stopIndex,region)
            distanceMeters += curve.distanceMeters
            elapsedSeconds += curve.durationSeconds
        }
        ordered.forEach { block ->
            val gapMeters=block.startMeters-distanceMeters
            if (gapMeters < -1e-6 || block.startMeters<startMeters-1e-6 || block.startMeters+block.curve.distanceMeters>totalMeters+1e-6) throw Infeasible("起终点、停止或走跑切换过渡重叠，请增加路线长度或减少停止次数")
            if (gapMeters > 1e-7) {
                val cruiseMode=mode((distanceMeters+block.startMeters)/2)
                val originalRegion=originalSegments?.firstOrNull {it.cruiseRegion!=null && it.endMeters>distanceMeters && it.startMeters<block.startMeters}?.cruiseRegion
                val randomRegion=originalRegion ?: cruiseIndex
                val curves=cruise(config,seed,cruiseMode,randomRegion,gapMeters)
                signature += if (cruiseMode==MotionMode.WALK) -curves.size else curves.size
                if (cruiseMode==MotionMode.WALK && curves.sumOf {it.durationSeconds}<30-1e-7) throw Infeasible("被停留分开的每段步行巡航须至少 30 秒")
                curves.forEach {append(it,cruiseMode,region=randomRegion)}
                cruiseIndex++
            }
            // 精确边界来自固定块，不让浮点积分误差累积改变锚点。
            distanceMeters=block.startMeters
            append(block.curve,block.mode,block.stopIndex)
        }
        if (abs(distanceMeters-totalMeters)>1e-5) throw Infeasible("排程未完整覆盖人物路径")
        val dwellSegments=segments.filter {it.mode==MotionMode.DWELL}
        if (dwellSegments.size!=stops.size) throw Infeasible("停止次数与参数不一致")
        dwellSegments.zipWithNext().forEach {(a,b) ->
            val cruiseSeconds=segments.filter {it.startSeconds>=a.endSeconds && it.endSeconds<=b.startSeconds && it.mode in listOf(MotionMode.RUN,MotionMode.WALK)}.sumOf {it.curve.durationSeconds}
            if (cruiseSeconds<10-1e-7) throw Infeasible("两次停止之间须至少 10 秒巡航，请减少停止次数或增加路线长度")
        }
        val movingSeconds=segments.filter {it.mode!=MotionMode.DWELL}.sumOf {it.curve.durationSeconds}
        val walkingSeconds=segments.filter {it.mode==MotionMode.WALK}.sumOf {it.curve.durationSeconds}
        if (walking!=null && walking.endMeters>startMeters && walkingSeconds<30-1e-7) throw Infeasible("步行巡航须至少 30 秒")
        return Candidate(segments.toList(),movingSeconds,walkingSeconds,signature+dwellSegments.map {if (mode(it.startMeters)==MotionMode.WALK) -it.stopIndex!! else it.stopIndex!!})
    }

    private fun cruise(config: SimulationConfig, seed: Long, mode: MotionMode, regionIndex: Int, distanceMeters: Double): List<SpeedCurve> {
        val baseKmh=if (mode==MotionMode.WALK) config.walkSpeedKmh else config.runSpeedKmh
        val rangeKmh=if (mode==MotionMode.WALK) 1.0 else 2.0
        val lowKmh=max(0.5,baseKmh-rangeKmh)
        val highKmh=baseKmh+rangeKmh
        val base=baseKmh/3.6
        val count=max(2,ceil(distanceMeters/(lowKmh/3.6*12)).toInt())
        val anchors=DoubleArray(count+1) {base}
        for (i in 1 until count) {
            // 两个允许区间合并抽样，排除基准附近 0.05 km/h。
            val leftLength=baseKmh-0.05-lowKmh
            val rightLength=highKmh-baseKmh-0.05
            val sample=RandomValues.unit(seed,20L+mode.ordinal,regionIndex.toLong()*100000+i)*(leftLength+rightLength)
            anchors[i]=(if (sample<leftLength) lowKmh+sample else baseKmh+0.05+sample-leftLength)/3.6
        }
        repeat(31) {attempt ->
            val mean=(0 until count).sumOf {(anchors[it]+anchors[it+1])/2}/count
            val h=distanceMeters/mean/count
            if ((0 until count).all {val delta=abs(anchors[it+1]-anchors[it]); 1.875*delta/h<=0.8+1e-12 && 5.774*delta/(h*h)<=0.6+1e-12})
                return (0 until count).map {SpeedCurve(anchors[it],anchors[it+1],h)}
            if (attempt<30) for (i in 1 until count) anchors[i]=base+(anchors[i]-base)/2
        }
        throw Infeasible("巡航距离过短，速度浮动无法满足加速度限制")
    }
}
