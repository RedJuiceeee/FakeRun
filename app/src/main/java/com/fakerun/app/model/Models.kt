package com.fakerun.app.model

import com.fakerun.app.geo.HumanPath
import com.fakerun.app.simulation.SpeedCurve

data class Wgs84Point(val latitude: Double, val longitude: Double)
data class Gcj02Point(val latitude: Double, val longitude: Double)
data class Route(val id: String, val name: String, val nodes: List<Gcj02Point>, val closed: Boolean, val version: Int = 1)

data class SimulationConfig(
    val loopCount: Int = 1,
    val runSpeedKmh: Double = 10.0,
    val stopCount: Int = 0,
    val stopDurationSeconds: Int = 10,
    val walkSpeedKmh: Double = 5.0,
    val walkTimeRatio: Double = 0.0,
    val naturalSway: Boolean = true,
    val avoidance: Boolean = true,
    val maxLateralOffsetMeters: Double = 3.0,
    val gpsDrift: Boolean = true,
    val mockNetwork: Boolean = true,
    val altitudeMeters: Double = 55.0,
    val satelliteCount: Int = 7
) {
    fun validate() {
        require(loopCount in 1..100) { "循环次数须为 1～100" }
        require(runSpeedKmh.isFinite() && runSpeedKmh in 4.0..20.0) { "跑步速度须为 4～20 km/h" }
        require(stopCount in 0..20) { "中途停止次数须为 0～20" }
        require(stopDurationSeconds in 3..120) { "停留基准须为 3～120 秒" }
        require(walkTimeRatio.isFinite() && walkTimeRatio in 0.0..0.8 && kotlin.math.abs(walkTimeRatio * 100 - kotlin.math.round(walkTimeRatio * 100)) < 1e-6) { "步行占比须为 0～80% 的整数百分点" }
        if (walkTimeRatio > 0) require(walkSpeedKmh.isFinite() && walkSpeedKmh in 1.0..7.0 && walkSpeedKmh < runSpeedKmh) { "步行速度须为 1～7 km/h，且低于跑步速度" }
        require(maxLateralOffsetMeters.isFinite() && maxLateralOffsetMeters in 0.0..3.0) { "最大横向偏移须为 0～3 米" }
        require(altitudeMeters.isFinite() && altitudeMeters in -500.0..9000.0) { "模拟 WGS84 椭球高度须为 -500～9000 米" }
        require(satelliteCount in 0..32) { "模拟 GPS 卫星数量须为 0～32" }
    }
}

enum class RunState { IDLE, PREPARING, RUNNING, PAUSING, PAUSED, RESUMING, STOPPING, COMPLETED, INTERRUPTED, ERROR }
enum class MotionMode { RUN, WALK, TRANSITION, DWELL }
data class StopEvent(val index: Int, val humanDistanceMeters: Double, val durationSeconds: Int)
data class WalkingRegion(val startMeters: Double, val endMeters: Double)
data class MotionSegment(
    val mode: MotionMode,
    val startSeconds: Double,
    val startMeters: Double,
    val curve: SpeedCurve,
    val stopIndex: Int? = null,
    val cruiseRegion: Int? = null
) {
    val endSeconds get() = startSeconds + curve.durationSeconds
    val endMeters get() = startMeters + curve.distanceMeters
}
data class PreparedSimulation(
    val route: Route,
    val config: SimulationConfig,
    val seed: Long,
    val path: HumanPath,
    val stops: List<StopEvent>,
    val walkingRegion: WalkingRegion?,
    val segments: List<MotionSegment>,
    val movingSeconds: Double,
    val walkingSeconds: Double
) {
    val totalSeconds get() = segments.last().endSeconds
}
data class SimulationSnapshot(
    val state: RunState,
    val mode: MotionMode,
    val routePoint: Wgs84Point,
    val humanPoint: Wgs84Point,
    val reportedPoint: Wgs84Point,
    val speedMetersPerSecond: Double,
    val bearingDegrees: Double,
    val accuracyMeters: Double,
    val centerDistanceMeters: Double,
    val humanDistanceMeters: Double,
    val completedLoops: Int,
    val movingSeconds: Double,
    val walkingSeconds: Double,
    val completedStops: Int,
    val userIntervened: Boolean,
    val interventionDistanceMeters: Double = 0.0,
    val message: String = "",
    val altitudeMeters: Double = 55.0,
    val verticalAccuracyMeters: Double = 3.0,
    val satelliteCount: Int = 7
)
