package com.fakerun.app

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fakerun.app.data.RouteRepository
import com.fakerun.app.geo.CoordinateTransformer
import com.fakerun.app.geo.Geo
import com.fakerun.app.geo.LocalVector
import com.fakerun.app.location.SessionState
import com.fakerun.app.location.SimulationService
import com.fakerun.app.model.Route
import com.fakerun.app.model.RunState
import com.fakerun.app.model.SimulationConfig
import com.fakerun.app.model.Wgs84Point
import com.fakerun.app.simulation.SessionPlanner
import com.fakerun.app.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ProviderCompatibilityTest {
    @Test fun stationaryMockWindowForExternalReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("外部读取对照须显式启用 checkTarget=true", arguments.getString("checkTarget") == "true")
        val holdSeconds = arguments.getString("holdSeconds", "90").toInt()
        require(holdSeconds in 30..180) { "对照窗口须在 30～180 秒内" }
        assertFalse("不得中断正在运行的用户会话", SessionState.status.value.active)
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
        val previousMessage = preferences.getString("message", "").orEmpty()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val manager = context.getSystemService(LocationManager::class.java)
        val counts = listOf("gps", "network", "passive").associateWith { AtomicInteger() }
        val mismatches = listOf("gps", "network", "passive").associateWith { AtomicInteger() }
        val listeners = mutableListOf<LocationListener>()
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        fun awaitState(timeoutSeconds: Int, condition: () -> Boolean) {
            val deadlineNanos = SystemClock.elapsedRealtimeNanos() + timeoutSeconds * 1_000_000_000L
            while (!condition() && SystemClock.elapsedRealtimeNanos() < deadlineNanos) Thread.sleep(50)
            assertTrue("服务未达到预期状态：${SessionState.status.value}", condition())
        }
        fun command(action: String) = context.startService(Intent(context, SimulationService::class.java).setAction(action))
        try {
            SimulationService.checkReadiness(context)
            // 使用公开合成坐标及真实前台服务，避免测试注入线程在后台变慢；不保存用户路线。
            val origin = Wgs84Point(39.915, 116.404)
            val nodes = listOf(origin, Geo.offset(origin, LocalVector(250.0, 0.0)), Geo.offset(origin, LocalVector(250.0, 250.0)), Geo.offset(origin, LocalVector(0.0, 250.0)))
            val route = Route("compatibility-synthetic", "定位接口对照合成路线", nodes.map { CoordinateTransformer.toGcj02(it) }, true)
            val plan = SessionPlanner.prepare(route, SimulationConfig(maxLateralOffsetMeters = 0.0, gpsDrift = false), 20261010L)
            SessionState.pendingPlan.set(plan)
            context.startForegroundService(Intent(context, SimulationService::class.java).setAction(SimulationService.START))
            awaitState(5) { SessionState.status.value.active && SessionState.status.value.snapshot != null }
            command(SimulationService.PAUSE)
            awaitState(25) { SessionState.status.value.snapshot?.state == RunState.PAUSED }
            val point = SessionState.status.value.snapshot!!.reportedPoint
            val startTimeNanos = SystemClock.elapsedRealtimeNanos()
            counts.forEach { (name, count) ->
                val listener = object : LocationListener {
                    @Suppress("DEPRECATION")
                    override fun onLocationChanged(location: Location) {
                        // 只核对暂停完成后的新样本，排除注册时送来的历史缓存。
                        if (location.elapsedRealtimeNanos < startTimeNanos) return
                        count.incrementAndGet()
                        if (kotlin.math.abs(location.latitude - point.latitude) > 1e-9 ||
                            kotlin.math.abs(location.longitude - point.longitude) > 1e-9 || !location.isFromMockProvider) {
                            mismatches.getValue(name).incrementAndGet()
                        }
                    }
                }
                manager.requestLocationUpdates(name, 0, 0f, listener, Looper.getMainLooper())
                listeners += listener
            }
            report("DIAGNOSTIC_READY：真实前台服务已暂停，固定合成 WGS84 位置，窗口 $holdSeconds 秒；可对照外部应用准备页。")
            var nextReportSeconds = 20
            while (SystemClock.elapsedRealtimeNanos() - startTimeNanos < holdSeconds * 1_000_000_000L) {
                assertTrue("前台模拟意外结束：${SessionState.status.value.message}", SessionState.status.value.active)
                if ((SystemClock.elapsedRealtimeNanos() - startTimeNanos) / 1_000_000_000L >= nextReportSeconds) {
                    report("对照 $nextReportSeconds 秒：接收 ${counts.mapValues { it.value.get() }}，不匹配 ${mismatches.mapValues { it.value.get() }}")
                    nextReportSeconds += 20
                }
                Thread.sleep(200)
            }
            listOf("gps", "network").forEach { name ->
                // 系统可合并客户端回调；输出频率由生产服务决定，不要求读取器收到每个 tick。
                assertTrue("$name 没有持续收到合成位置", counts.getValue(name).get() >= holdSeconds)
                assertEquals("$name 输出不是指定 mock 位置", 0, mismatches.getValue(name).get())
            }
            report("DIAGNOSTIC_FINISHED：接收 ${counts.mapValues { it.value.get() }}，不匹配 ${mismatches.mapValues { it.value.get() }}；仅验证系统读取器，外部应用表现另记。")
        } finally {
            listeners.forEach { manager.removeUpdates(it) }
            if (SessionState.status.value.active) {
                command(SimulationService.IMMEDIATE_END)
                awaitState(5) { !SessionState.status.value.active }
            }
            RouteRepository(context).setSessionMarker(false, previousMessage)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
