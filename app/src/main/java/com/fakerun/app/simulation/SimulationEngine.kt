package com.fakerun.app.simulation

import com.fakerun.app.geo.*
import com.fakerun.app.model.*
import kotlin.math.*

class SimulationEngine(val plan: PreparedSimulation) {
    var state=RunState.RUNNING
        private set
    var message=""
        private set
    private var segments=plan.segments
    private var segmentIndex=0
    private var scheduleSeconds=0.0
    private var observationSeconds=0.0
    private var humanDistanceMeters=0.0
    private var movingSeconds=0.0
    private var walkingSeconds=0.0
    private var speedMetersPerSecond=0.0
    private var mode=MotionMode.TRANSITION
    private var lastBearingDegrees=plan.path.at(0.0).bearingDegrees
    private var userIntervened=false
    private var interventionDistanceMeters=0.0
    private var pendingEnd=false
    private var pending=false
    private var pausedDuringDwell=false
    private var manualCurve: SpeedCurve?=null
    private var manualSeconds=0.0
    private var manualStartMeters=0.0
    private val completedStops=mutableSetOf<Int>()

    fun tick(seconds: Double): SimulationSnapshot {
        var remaining=max(0.0,seconds-observationSeconds)
        observationSeconds=seconds
        while (remaining>1e-9 && state in listOf(RunState.RUNNING,RunState.PAUSING,RunState.RESUMING,RunState.STOPPING)) {
            val curve=manualCurve
            if (curve!=null) {
                val delta=min(remaining,curve.durationSeconds-manualSeconds)
                manualSeconds+=delta
                remaining-=delta
                movingSeconds+=delta
                val previous=humanDistanceMeters
                humanDistanceMeters=manualStartMeters+curve.distanceAt(manualSeconds)
                interventionDistanceMeters+=humanDistanceMeters-previous
                speedMetersPerSecond=curve.speedAt(manualSeconds)
                mode=MotionMode.TRANSITION
                if (manualSeconds>=curve.durationSeconds-1e-9) {
                    speedMetersPerSecond=0.0
                    manualCurve=null
                    if (pendingEnd) {state=RunState.IDLE; message="已平滑结束本次模拟"}
                    else {
                        val reachedDwell=segments.indexOfFirst {it.mode==MotionMode.DWELL && it.stopIndex !in completedStops && abs(it.startMeters-humanDistanceMeters)<1e-6}
                        if (reachedDwell>=0) {segmentIndex=reachedDwell; scheduleSeconds=segments[reachedDwell].startSeconds; pausedDuringDwell=true}
                        state=RunState.PAUSED; message="已暂停，GPS 漂移继续"
                    }
                    pending=false
                }
                continue
            }
            val segment=segments[segmentIndex]
            val delta=min(remaining,max(0.0,segment.endSeconds-scheduleSeconds))
            scheduleSeconds+=delta
            remaining-=delta
            val previousDistanceMeters=humanDistanceMeters
            humanDistanceMeters=segment.startMeters+segment.curve.distanceAt(scheduleSeconds-segment.startSeconds)
            if (state==RunState.RESUMING && segment.mode==MotionMode.TRANSITION) interventionDistanceMeters+=humanDistanceMeters-previousDistanceMeters
            speedMetersPerSecond=segment.curve.speedAt(scheduleSeconds-segment.startSeconds)
            mode=segment.mode
            if (mode!=MotionMode.DWELL) movingSeconds+=delta
            if (mode==MotionMode.WALK) walkingSeconds+=delta
            if (scheduleSeconds>=segment.endSeconds-1e-9) {
                if (segment.mode==MotionMode.DWELL) completedStops+=segment.stopIndex!!
                if (segmentIndex==segments.lastIndex) {
                    state=RunState.COMPLETED
                    humanDistanceMeters=plan.path.distanceMeters
                    speedMetersPerSecond=0.0
                    message="已完成全部圈数和计划事件"
                    break
                }
                segmentIndex++
                if (pending) beginManualStop()
                else if (state==RunState.RESUMING) {state=RunState.RUNNING; message="用户干预，原预计占比可能变化"}
            }
        }
        return snapshot()
    }

    fun pause() = requestOperation(false)
    fun endSmoothly() = requestOperation(true)
    private fun requestOperation(end: Boolean) {
        if (state==RunState.PAUSED) {
            if (end) {state=RunState.IDLE; message="已结束本次模拟"}
            return
        }
        if (state!=RunState.RUNNING) return
        userIntervened=true
        pendingEnd=end
        val segment=segments[segmentIndex]
        if (segment.mode==MotionMode.DWELL) {
            pausedDuringDwell=true
            speedMetersPerSecond=0.0
            state=if (end) RunState.IDLE else RunState.PAUSED
            message=if (end) "已结束本次模拟" else "已暂停，保留剩余驻留时间"
        } else {
            pending=true
            state=if (end) RunState.STOPPING else RunState.PAUSING
            message=if (segment.mode==MotionMode.TRANSITION) "当前减速/加速结束后执行" else "平滑停下中，等待当前速度锚点"
        }
    }

    private fun beginManualStop() {
        if (speedMetersPerSecond<=1e-9) {
            // 原计划减速到停点时恰好零速，冻结即将开始的驻留。
            pausedDuringDwell=segments[segmentIndex].mode==MotionMode.DWELL
            pending=false
            state=if (pendingEnd) RunState.IDLE else RunState.PAUSED
            message="已停下，剩余计划已冻结"
            return
        }
        val curve=SpeedCurve.transition(speedMetersPerSecond,0.0)
        val endMeters=humanDistanceMeters+curve.distanceMeters
        if (endMeters>plan.path.distanceMeters+1e-7 || plan.stops.any {it.index !in completedStops && it.humanDistanceMeters>=humanDistanceMeters-1e-7 && it.humanDistanceMeters<endMeters-1e-7}) {
            interrupt("新增减速会越过终点或未完成停止锚点，原排程已失效，请重新开始")
            return
        }
        manualCurve=curve
        manualStartMeters=humanDistanceMeters
        manualSeconds=0.0
        message="平滑停下中"
    }

    fun resume() {
        if (state!=RunState.PAUSED) return
        if (pausedDuringDwell) {
            pausedDuringDwell=false
            state=RunState.RUNNING
            message="继续剩余驻留，用户干预，原预计占比可能变化"
            return
        }
        try {
            segments=SessionPlanner.resume(plan,humanDistanceMeters,completedStops)
            segmentIndex=0
            scheduleSeconds=0.0
            state=RunState.RESUMING
            message="平滑恢复中，用户干预，原预计占比可能变化"
        } catch (error: IllegalArgumentException) {
            interrupt("剩余排程不可执行：${error.message}")
        }
    }

    fun interrupt(reason: String) {state=RunState.INTERRUPTED; message=reason; speedMetersPerSecond=0.0}
    fun error(reason: String) {state=RunState.ERROR; message=reason; speedMetersPerSecond=0.0}
    fun immediatelyEnd() {state=RunState.IDLE; message="已立即结束"; speedMetersPerSecond=0.0}

    fun snapshot(): SimulationSnapshot {
        val position=plan.path.at(humanDistanceMeters)
        if (speedMetersPerSecond>1e-6) lastBearingDegrees=position.bearingDegrees
        val error=if (plan.config.gpsDrift) gpsError(observationSeconds) else LocalVector(0.0,0.0)
        val accuracyIndex=floor(observationSeconds/15).toLong()
        val accuracyU=RandomValues.smoothStep5(observationSeconds/15-accuracyIndex)
        // 卫星数是用户配置的模拟观测字段；少于 4 时使用弱信号精度档位。
        // 高度按平坦路线基准叠加连续观测误差，不推断地图上的真实地形。
        val weakGps=plan.config.satelliteCount<4
        val minAccuracyMeters=if (weakGps) 20.0 else 4.0
        val accuracyRangeMeters=if (weakGps) 20.0 else 4.0
        val accuracyA=minAccuracyMeters+accuracyRangeMeters*RandomValues.unit(plan.seed,40,accuracyIndex)
        val accuracyB=minAccuracyMeters+accuracyRangeMeters*RandomValues.unit(plan.seed,40,accuracyIndex+1)
        val accuracyMeters=accuracyA+(accuracyB-accuracyA)*accuracyU
        fun altitudeErrorMeters(index: Long) = if (index==0L) 0.0 else 3*RandomValues.unit(plan.seed,41,index)-1.5
        val altitudeA=altitudeErrorMeters(accuracyIndex)
        val altitudeB=altitudeErrorMeters(accuracyIndex+1)
        return SimulationSnapshot(state,mode,position.routePoint,position.humanPoint,Geo.offset(position.humanPoint,error),speedMetersPerSecond,lastBearingDegrees,accuracyMeters,position.centerDistanceMeters,humanDistanceMeters,min(plan.config.loopCount,floor((position.centerDistanceMeters+1e-6)/plan.path.loopLengthMeters).toInt()),movingSeconds,walkingSeconds,completedStops.size,userIntervened,interventionDistanceMeters,message,
            altitudeMeters=plan.config.altitudeMeters+altitudeA+(altitudeB-altitudeA)*accuracyU,
            verticalAccuracyMeters=if (weakGps) accuracyMeters else max(3.0,accuracyMeters*0.75),
            satelliteCount=plan.config.satelliteCount)
    }
    private fun gpsError(seconds: Double): LocalVector {
        val index=floor(seconds/3).toLong()
        fun anchor(i: Long): LocalVector {
            if (i==0L) return LocalVector(0.0,0.0)
            val radiusMeters=2*sqrt(RandomValues.unit(plan.seed,30,i))
            val angle=2*PI*RandomValues.unit(plan.seed,31,i)
            return LocalVector(radiusMeters*cos(angle),radiusMeters*sin(angle))
        }
        val a=anchor(index); val b=anchor(index+1)
        return a+(b-a)*RandomValues.smoothStep5(seconds/3-index)
    }
}
