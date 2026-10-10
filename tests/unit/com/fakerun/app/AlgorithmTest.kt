package com.fakerun.app

import com.fakerun.app.geo.*
import com.fakerun.app.location.*
import com.fakerun.app.model.*
import com.fakerun.app.simulation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AlgorithmTest {
    private fun route(sideMeters: Double=250.0): Route {
        val origin=Wgs84Point(39.91,116.39)
        val points=listOf(origin,Geo.offset(origin,LocalVector(sideMeters,0.0)),Geo.offset(origin,LocalVector(sideMeters,sideMeters)),Geo.offset(origin,LocalVector(0.0,sideMeters)))
        return Route("synthetic-square","测试闭合方形",points.map {CoordinateTransformer.toGcj02(it)},true)
    }
    private fun composite(seed: Long=123456L)=SessionPlanner.prepare(route(),SimulationConfig(loopCount=2,stopCount=2,walkTimeRatio=0.2),seed)

    @Test fun independentCoordinateSamplesAndLocalMeters() {
        val stream=javaClass.getResourceAsStream("/coordinates.csv")!!
        stream.bufferedReader().use {reader ->reader.readLines().drop(1).forEach {line ->
            val values=line.split(',').map {it.toDouble()}
            val wgs=Wgs84Point(values[1],values[0])
            val expected=Gcj02Point(values[3],values[2])
            val actual=CoordinateTransformer.toGcj02(wgs)
            assertEquals(expected.latitude,actual.latitude,1e-10)
            assertEquals(expected.longitude,actual.longitude,1e-10)
            // 使用来自原作者实现的 GCJ 固定值，而非先调用自有正转换。
            if (SupportedRegion.contains(expected)) assertTrue(Geo.distanceMeters(wgs,CoordinateTransformer.toWgs84(expected))<0.2)
            assertEquals(2.0,Geo.distanceMeters(wgs,Geo.offset(wgs,LocalVector(2.0,0.0))),0.001)
        }}
        listOf(Gcj02Point(22.28,114.16),Gcj02Point(25.04,121.56),Gcj02Point(40.7,-74.0),Gcj02Point(27.7,85.3)).forEach {assertFalse(SupportedRegion.contains(it))}
    }

    @Test fun closureHumanArcLengthAndAvoidance() {
        val plan=composite()
        val path=plan.path
        assertTrue(path.distanceMeters>=path.loopLengthMeters*2)
        assertTrue(path.avoidanceEvents.any {abs(it.peakMeters)>1})
        assertEquals(0.0,Geo.distanceMeters(path.samples.first().humanPoint,path.samples.last().humanPoint),0.02)
        path.samples.zipWithNext().forEach {(a,b) ->
            assertTrue(b.humanDistanceMeters>a.humanDistanceMeters)
            assertTrue(b.centerDistanceMeters>a.centerDistanceMeters)
            assertTrue(b.humanDistanceMeters-a.humanDistanceMeters<=0.500001)
            assertTrue(Geo.distanceMeters(a.routePoint,a.humanPoint)<=3.00001)
        }
        val seamSample=path.samples.minBy {abs(it.centerDistanceMeters-path.loopLengthMeters)}
        val before=path.at(seamSample.humanDistanceMeters-0.01)
        val after=path.at(seamSample.humanDistanceMeters+0.01)
        assertTrue(Geo.distanceMeters(before.humanPoint,after.humanPoint)<0.03)
        assertTrue(abs(before.centerDistanceMeters-after.centerDistanceMeters)<0.03)
        assertEquals(path.distanceMeters,path.samples.zipWithNext().sumOf {(a,b)->Geo.distanceMeters(a.humanPoint,b.humanPoint)},0.01)
        val off=SessionPlanner.prepare(route(),SimulationConfig(maxLateralOffsetMeters=0.0),3L)
        assertTrue(off.path.avoidanceEvents.isEmpty())
        assertTrue(off.path.samples.all {Geo.distanceMeters(it.humanPoint,it.routePoint)<1e-6})
    }

    private fun verifySchedule(plan: PreparedSimulation) {
        val engine=SimulationEngine(plan)
        var previous=engine.tick(0.0)
        val speeds=mutableListOf(previous.speedMetersPerSecond)
        var measuredMovingSeconds=0.0
        var measuredWalkingSeconds=0.0
        var seconds=0.1
        while (seconds<plan.totalSeconds) {
            val s=engine.tick(seconds)
            assertTrue(s.humanDistanceMeters>=previous.humanDistanceMeters)
            assertTrue(s.centerDistanceMeters>=previous.centerDistanceMeters)
            assertTrue(Geo.distanceMeters(s.humanPoint,s.reportedPoint)<=2.0001)
            assertTrue(s.accuracyMeters in 4.0..8.0)
            if (s.mode!=MotionMode.DWELL) measuredMovingSeconds+=0.1
            if (s.mode==MotionMode.WALK) measuredWalkingSeconds+=0.1
            if (s.mode==MotionMode.RUN) assertTrue(s.speedMetersPerSecond*3.6 in (plan.config.runSpeedKmh-2-1e-6)..(plan.config.runSpeedKmh+2+1e-6))
            if (s.mode==MotionMode.WALK) assertTrue(s.speedMetersPerSecond*3.6 in (max(0.5,plan.config.walkSpeedKmh-1)-1e-6)..(plan.config.walkSpeedKmh+1+1e-6))
            speeds+=s.speedMetersPerSecond
            previous=s
            seconds+=0.1
        }
        val end=engine.tick(plan.totalSeconds+0.1)
        speeds+=end.speedMetersPerSecond
        val accelerations=speeds.zipWithNext().map {(a,b)->(b-a)/0.1}
        assertTrue("100 ms 加速度",accelerations.maxOf {abs(it)}<=0.8*1.02)
        assertTrue("100 ms 加速度变化率",accelerations.zipWithNext().maxOf {(a,b)->abs(b-a)/0.1}<=0.6*1.02)
        assertEquals(RunState.COMPLETED,end.state)
        assertEquals(plan.config.loopCount,end.completedLoops)
        assertEquals(plan.config.stopCount,end.completedStops)
        assertEquals(0.0,end.speedMetersPerSecond,1e-12)
        assertEquals(plan.path.distanceMeters,end.humanDistanceMeters,1e-6)
        assertEquals(plan.movingSeconds,end.movingSeconds,1e-6)
        assertEquals(plan.walkingSeconds,end.walkingSeconds,1e-6)
        assertEquals(plan.movingSeconds,measuredMovingSeconds,0.6)
        assertEquals(plan.walkingSeconds,measuredWalkingSeconds,0.6)
        assertTrue(abs(plan.config.walkTimeRatio-measuredWalkingSeconds/measuredMovingSeconds)<=0.0105)
        val cruises=plan.segments.filter {it.mode in listOf(MotionMode.RUN,MotionMode.WALK)}
        assertTrue(cruises.any {abs(it.curve.startMetersPerSecond-it.curve.endMetersPerSecond)>1e-6})
        cruises.forEach {assertTrue(it.curve.durationSeconds<=12+1e-9)}
        plan.segments.zipWithNext().forEach {(a,b) ->
            assertEquals(a.endMeters,b.startMeters,1e-5)
            assertEquals(a.curve.endMetersPerSecond,b.curve.startMetersPerSecond,1e-8)
        }
    }

    @Test fun compositeScheduleAndSpeedBoundaryRanges() {
        verifySchedule(composite())
        listOf(SimulationConfig(runSpeedKmh=4.0,walkSpeedKmh=1.0,walkTimeRatio=0.2),SimulationConfig(runSpeedKmh=20.0,walkSpeedKmh=7.0,walkTimeRatio=0.2)).forEach {verifySchedule(SessionPlanner.prepare(route(500.0),it,31L))}
        verifySchedule(SessionPlanner.prepare(route(),SimulationConfig(),4L))
    }

    @Test fun stopDurationsSeedAndStationaryDrift() {
        for (baseline in listOf(3,10,120)) {
            val plan=SessionPlanner.prepare(route(),SimulationConfig(stopCount=2,stopDurationSeconds=baseline),9L)
            plan.stops.forEach {assertTrue(it.durationSeconds in max(3,baseline-5)..min(120,baseline+5))}
            val engine=SimulationEngine(plan)
            plan.segments.filter {it.mode==MotionMode.DWELL}.forEach {dwell ->
                assertEquals(plan.stops[dwell.stopIndex!!-1].durationSeconds.toDouble(),dwell.curve.durationSeconds,1e-9)
                val a=engine.tick(dwell.startSeconds+0.1)
                val b=engine.tick(dwell.endSeconds-0.1)
                assertEquals(MotionMode.DWELL,a.mode)
                assertEquals(0.0,b.speedMetersPerSecond,1e-12)
                assertEquals(0.0,Geo.distanceMeters(a.humanPoint,b.humanPoint),1e-8)
                assertTrue(Geo.distanceMeters(a.reportedPoint,b.reportedPoint)>0.01)
                assertTrue(Geo.distanceMeters(b.humanPoint,b.reportedPoint)<=2.0001)
            }
        }
        val first=composite()
        val second=composite()
        assertEquals(first.stops,second.stops)
        assertEquals(first.segments,second.segments)
        assertEquals(first.path.samples,second.path.samples)
        val a=SimulationEngine(first); val b=SimulationEngine(second)
        a.tick(17.0); a.tick(27.0)
        assertEquals(a.tick(40.0),b.tick(40.0))
    }

    @Test fun rejectsImpossibleInputsAndCanCancel() {
        fun rejected(action: ()->Unit) {try {action(); fail("必须拒绝")} catch (expected: IllegalArgumentException) {assertFalse(expected.message.isNullOrBlank())}}
        rejected {SessionPlanner.prepare(route(30.0),SimulationConfig(runSpeedKmh=20.0,stopCount=20),2L)}
        rejected {SessionPlanner.prepare(route(30.0),SimulationConfig(walkTimeRatio=0.01),2L)}
        rejected {SessionPlanner.prepare(route(),SimulationConfig(loopCount=100),2L)}
        rejected {SessionPlanner.prepare(route().copy(closed=false),SimulationConfig(),2L)}
        rejected {SessionPlanner.prepare(route().copy(nodes=route().nodes.let {listOf(it[0],it[1],it[0],it[3])}),SimulationConfig(),2L)}
        try {SessionPlanner.prepare(route(),SimulationConfig(),2L) {true}; fail("取消必须终止后台计算")} catch (_: java.util.concurrent.CancellationException) { }
        SimulationConfig(walkTimeRatio=0.0,walkSpeedKmh=50.0).validate()
    }

    @Test fun pauseResumePreservesPathAndEvents() {
        val plan=composite()
        val engine=SimulationEngine(plan)
        val cruise=plan.segments.first {it.mode==MotionMode.RUN && it.startMeters>100}
        var seconds=cruise.startSeconds+0.3
        val before=engine.tick(seconds)
        engine.pause()
        assertEquals(RunState.PAUSING,engine.state)
        while (engine.state==RunState.PAUSING && seconds<cruise.endSeconds+30) {seconds+=0.1; engine.tick(seconds)}
        assertEquals(RunState.PAUSED,engine.state)
        val paused=engine.snapshot()
        assertTrue(paused.humanDistanceMeters>before.humanDistanceMeters)
        assertEquals(0.0,paused.speedMetersPerSecond,1e-12)
        val delayed=engine.tick(seconds+30)
        assertEquals(paused.humanDistanceMeters,delayed.humanDistanceMeters,1e-9)
        assertEquals(paused.movingSeconds,delayed.movingSeconds,1e-9)
        seconds+=30
        engine.resume()
        assertEquals(RunState.RESUMING,engine.state)
        var prior=engine.snapshot()
        while (engine.state in listOf(RunState.RESUMING,RunState.RUNNING) && seconds<plan.totalSeconds+200) {
            seconds+=0.1
            val next=engine.tick(seconds)
            assertTrue(next.humanDistanceMeters>=prior.humanDistanceMeters)
            prior=next
        }
        assertEquals(RunState.COMPLETED,engine.state)
        assertEquals(2,prior.completedStops)
        assertTrue(prior.userIntervened)
        assertTrue(prior.interventionDistanceMeters>0)
    }

    @Test fun dwellPauseAndUnrecoverableControl() {
        val plan=composite()
        val dwell=plan.segments.first {it.mode==MotionMode.DWELL}
        val engine=SimulationEngine(plan)
        val before=engine.tick(dwell.startSeconds+1)
        engine.pause()
        assertEquals(RunState.PAUSED,engine.state)
        engine.tick(dwell.startSeconds+31)
        engine.resume()
        val after=engine.tick(dwell.endSeconds+31)
        assertEquals(before.completedStops+1,after.completedStops)
        assertTrue(after.humanDistanceMeters>=before.humanDistanceMeters)
        val ending=SimulationEngine(plan)
        val finalSegment=plan.segments.last()
        ending.tick(finalSegment.startSeconds+0.1); ending.pause()
        assertEquals(RunState.COMPLETED,ending.tick(plan.totalSeconds+1).state)
        val remainingPlan=plan.copy(stops=plan.stops+StopEvent(99,100.0,5))
        val interrupted=SimulationEngine(remainingPlan)
        val cruise=plan.segments.first {it.mode==MotionMode.RUN && it.endMeters>95}
        interrupted.tick(cruise.startSeconds+0.1); interrupted.pause()
        interrupted.tick(cruise.endSeconds+20)
        if (interrupted.state==RunState.PAUSED) interrupted.resume()
        assertEquals(RunState.INTERRUPTED,interrupted.state)
    }

    @Test fun altitudeAndGpsSignalRemainDeterministicAndDoNotAlterMotion() {
        val plan=SessionPlanner.prepare(route(),SimulationConfig(altitudeMeters=123.5,satelliteCount=7),51L)
        val normal=SimulationEngine(plan)
        val replay=SimulationEngine(plan)
        val weak=SimulationEngine(plan.copy(config=plan.config.copy(satelliteCount=3)))
        assertEquals(123.5,normal.snapshot().altitudeMeters,1e-9)
        listOf(0.0,0.1,14.99,15.0,15.01,30.0,60.0).forEach {seconds ->
            val actual=normal.tick(seconds)
            val repeated=replay.tick(seconds)
            val degraded=weak.tick(seconds)
            assertEquals(actual,repeated)
            assertEquals(7,actual.satelliteCount)
            assertEquals(3,degraded.satelliteCount)
            assertTrue(actual.accuracyMeters in 4.0..8.0)
            assertTrue(actual.verticalAccuracyMeters in 3.0..6.0)
            assertTrue(degraded.accuracyMeters in 20.0..40.0)
            assertEquals(degraded.accuracyMeters,degraded.verticalAccuracyMeters,1e-9)
            assertTrue(abs(actual.altitudeMeters-123.5)<=1.5)
            assertEquals(actual.altitudeMeters,degraded.altitudeMeters,1e-9)
            assertEquals(actual.humanDistanceMeters,degraded.humanDistanceMeters,1e-9)
            assertEquals(actual.speedMetersPerSecond,degraded.speedMetersPerSecond,1e-9)
            assertEquals(actual.reportedPoint,degraded.reportedPoint)
        }
        val before=normal.snapshot().altitudeMeters
        val after=normal.tick(60.01).altitudeMeters
        assertTrue("高度在观测锚点不应跳变",abs(after-before)<0.001)
        listOf(-500.0,9000.0).forEach {SimulationConfig(altitudeMeters=it).validate()}
        listOf(-500.1,9000.1,Double.NaN).forEach {height ->
            assertThrows(IllegalArgumentException::class.java) {SimulationConfig(altitudeMeters=height).validate()}
        }
        listOf(0,32).forEach {SimulationConfig(satelliteCount=it).validate()}
        listOf(-1,33).forEach {count ->
            assertThrows(IllegalArgumentException::class.java) {SimulationConfig(satelliteCount=count).validate()}
        }
    }

    @Test fun providerCleanupOnlyOwnsSuccessfullyCreatedResources() {
        class FakeAccess: ProviderAccess {
            val created=mutableListOf<String>()
            val removed=mutableListOf<String>()
            override fun create(name: String) {if (name=="network") throw SecurityException("测试拒绝 network"); created+=name}
            override fun enable(name: String)=Unit
            override fun output(name: String,snapshot: SimulationSnapshot,wallTimeMillis: Long,monotonicTimeNanos: Long)=Unit
            override fun remove(name: String) {removed+=name}
        }
        val fake=FakeAccess()
        val controller=MockLocationController(fake)
        try {controller.open(true); fail("network 接入须失败")} catch (_: SecurityException) { }
        assertEquals(emptyList<String>(),controller.close())
        controller.close()
        assertEquals(listOf("gps"),fake.created)
        assertEquals(listOf("gps"),fake.removed)
    }
}
