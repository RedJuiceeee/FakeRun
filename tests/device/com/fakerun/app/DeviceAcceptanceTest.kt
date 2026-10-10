package com.fakerun.app

import android.app.Activity
import android.content.*
import android.location.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.map.*
import com.baidu.mapapi.model.LatLng
import com.fakerun.app.data.*
import com.fakerun.app.geo.*
import com.fakerun.app.location.*
import com.fakerun.app.model.*
import com.fakerun.app.simulation.SessionPlanner
import com.fakerun.app.ui.MainActivity
import com.fakerun.app.ui.MainViewModel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val manager=context.getSystemService(LocationManager::class.java)
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use {it.readBytes().toString(Charsets.UTF_8)}}
    private fun waitFor(timeoutSeconds: Double, condition: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtimeNanos()+(timeoutSeconds*1e9).toLong()
        while (!condition() && SystemClock.elapsedRealtimeNanos()<deadline) Thread.sleep(50)
        assertTrue("在 $timeoutSeconds 秒内未达到预期状态：${SessionState.status.value}",condition())
    }
    private fun activity(): Activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun syntheticRoute(sideMeters: Double=250.0): Route {
        val origin=Wgs84Point(39.91,116.39)
        return Route("device-synthetic","设备合成闭合路线",listOf(origin,Geo.offset(origin,LocalVector(sideMeters,0.0)),Geo.offset(origin,LocalVector(sideMeters,sideMeters)),Geo.offset(origin,LocalVector(0.0,sideMeters))).map {CoordinateTransformer.toGcj02(it)},true)
    }
    private fun start(plan: PreparedSimulation) {
        SessionState.pendingPlan.set(plan)
        context.startForegroundService(Intent(context,SimulationService::class.java).setAction(SimulationService.START))
        waitFor(5.0) {SessionState.status.value.active && SessionState.status.value.snapshot!=null}
    }
    private fun command(action: String) {context.startService(Intent(context,SimulationService::class.java).setAction(action))}
    private fun end() {
        if (SessionState.status.value.active) {command(SimulationService.IMMEDIATE_END); waitFor(4.0) {!SessionState.status.value.active}}
    }
    private fun report(text: String) {instrumentation.sendStatus(0,Bundle().apply {putString("stream","\n$text\n")})}

    private fun drawRouteThroughSdk(activity: Activity): Route {
        require(MapSettingsRepository(context).loadApiKey().isNotBlank()) {"地图验收需要先在应用内填写真实 AK"}
        if (!context.getSharedPreferences("privacy",Context.MODE_PRIVATE).getBoolean("agreed",false)) {
            instrumentation.runOnMainSync {activity.findViewById<android.widget.Button>(R.id.privacy).performClick()}
            waitFor(5.0) {instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("同意")?.any {it.text?.toString()=="同意"}==true}
            val agree=instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("同意").first {it.text?.toString()=="同意"}
            assertTrue(agree.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        val mapView=AtomicReference<MapView>()
        waitFor(15.0) {
            instrumentation.runOnMainSync {
                val host=activity.findViewById<android.widget.FrameLayout>(R.id.map_host)
                for (i in 0 until host.childCount) if (host.getChildAt(i) is MapView) mapView.set(host.getChildAt(i) as MapView)
            }
            mapView.get()!=null
        }
        waitFor(30.0) {MainActivity.latestMapAuthCode!=null}
        assertEquals("百度 SDK 鉴权错误码 ${MainActivity.latestMapAuthCode}，请检查 AK 与签名",0,MainActivity.latestMapAuthCode)
        assertEquals(CoordType.GCJ02,SDKInitializer.getCoordType())
        val model=ViewModelProvider(activity as androidx.lifecycle.ViewModelStoreOwner)[MainViewModel::class.java]
        instrumentation.runOnMainSync {
            activity.findViewById<android.widget.Button>(R.id.tab_route).performClick()
            activity.findViewById<android.widget.Button>(R.id.new_route).performClick()
            activity.findViewById<android.widget.Button>(R.id.draw).performClick()
            // 固定样例验证地图任意点点击，避免点到 POI 时 SDK 返回地点中心。
            mapView.get().map.showMapPoi(false)
        }
        val origin=Wgs84Point(39.915,116.404)
        // 首点为原作者 JavaScript 独立样例，其余为合成方形节点。
        val points=listOf(Gcj02Point(39.91640428150164,116.41024449916938),CoordinateTransformer.toGcj02(Geo.offset(origin,LocalVector(250.0,0.0))),CoordinateTransformer.toGcj02(Geo.offset(origin,LocalVector(250.0,250.0))),CoordinateTransformer.toGcj02(Geo.offset(origin,LocalVector(0.0,250.0))))
        points.forEachIndexed {index,point ->
            instrumentation.runOnMainSync {mapView.get().map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(LatLng(point.latitude,point.longitude),20f))}
            Thread.sleep(1000)
            val screen=AtomicReference<android.graphics.Point>()
            instrumentation.runOnMainSync {
                val view=mapView.get()
                val offset=IntArray(2); view.getLocationOnScreen(offset)
                val local=view.map.projection.toScreenLocation(LatLng(point.latitude,point.longitude))
                screen.set(android.graphics.Point(local.x+offset[0],local.y+offset[1]))
            }
            val down=SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,screen.get().x.toFloat(),screen.get().y.toFloat(),0))
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down,down+80,android.view.MotionEvent.ACTION_UP,screen.get().x.toFloat(),screen.get().y.toFloat(),0))
            waitFor(5.0) {model.state.value.route.nodes.size==index+1}
            val actual=model.state.value.route.nodes[index]
            val actualWgs=CoordinateTransformer.toWgs84(actual)
            val expectedWgs=if (index==0) origin else CoordinateTransformer.toWgs84(point)
            assertTrue("SDK 节点 ${index+1}：GCJ-02=$actual，目标=$point，WGS84 偏差 ${Geo.distanceMeters(actualWgs,expectedWgs)} 米",Geo.distanceMeters(actualWgs,expectedWgs)<3.0)
        }
        instrumentation.runOnMainSync {activity.findViewById<android.widget.Button>(R.id.close_route).performClick()}
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val nodes=model.state.value.route.nodes
            val center=LatLng(nodes.map {it.latitude}.average(),nodes.map {it.longitude}.average())
            mapView.get().map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(center,17f))
        }
        Thread.sleep(3000)
        instrumentation.runOnMainSync {
            val view=mapView.get()
            report("地图视窗 ${view.width}×${view.height}，缩放 ${view.map.mapStatus.zoom}，中心 ${view.map.mapStatus.target}。")
            model.state.value.route.nodes.forEach {point ->
                val pixel=view.map.projection.toScreenLocation(LatLng(point.latitude,point.longitude))
                assertTrue("节点覆盖物应在地图视窗内：$pixel",pixel.x in 0..view.width && pixel.y in 0..view.height)
            }
        }
        val screenshot=instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null),"test-baidu-route.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        screenshot.recycle()
        report("百度 8.2.0 鉴权成功，GCJ-02 模式下实际点击得到 4 个方形节点，独立 WGS84 首点偏差 <3 米；已生成覆盖物截图。")
        return model.state.value.route
    }

    @Suppress("DEPRECATION")
    private fun providerCharacteristics(name: String): List<Any> {
        val provider=manager.getProvider(name) ?: error("设备没有 $name 定位来源")
        return listOf(provider.requiresNetwork(),provider.requiresSatellite(),provider.requiresCell(),
            provider.hasMonetaryCost(),provider.supportsAltitude(),provider.supportsSpeed(),
            provider.supportsBearing(),provider.powerRequirement,provider.accuracy)
    }

    @Test fun nativeProviderCharacteristicsAndSynchronizedOutput() {
        val activity=activity()
        val controller=MockLocationController(AndroidProviderAccess(manager))
        val names=listOf("gps","network")
        val original=names.associateWith {providerCharacteristics(it)}
        val received=mutableMapOf<String,Location>()
        val latch=CountDownLatch(2)
        val now=SystemClock.elapsedRealtimeNanos()
        val wallTimeMillis=System.currentTimeMillis()
        val point=Wgs84Point(39.915,116.404)
        val snapshot=SimulationSnapshot(RunState.PAUSED,MotionMode.DWELL,point,point,point,
            0.0,90.0,5.0,0.0,0.0,0,0.0,0.0,0,false)
        val listener=object: LocationListener {
            override fun onLocationChanged(location: Location) {
                // 只核对本次时间戳，避免把注册时返回的历史样本当作新输出。
                if (location.elapsedRealtimeNanos!=now) return
                synchronized(received) {
                    if (received.put(location.provider!!,Location(location))==null) latch.countDown()
                }
            }
        }
        try {
            SimulationService.checkReadiness(context)
            controller.open(true)
            names.forEach {name ->
                val expected=original.getValue(name).toMutableList().apply {this[4]=true; this[5]=true; this[6]=true}
                assertEquals("$name 的来源/功耗/精度或输出能力不正确",expected,providerCharacteristics(name))
                manager.requestLocationUpdates(name,0,0f,listener,Looper.getMainLooper())
            }
            controller.output(snapshot,wallTimeMillis,now)
            assertTrue("未收到 gps/network 的同步新位置",latch.await(5,TimeUnit.SECONDS))
            names.forEach {name ->
                val location=synchronized(received) {received.getValue(name)}
                assertEquals(name,location.provider)
                assertEquals(point.latitude,location.latitude,1e-9)
                assertEquals(point.longitude,location.longitude,1e-9)
                assertEquals(wallTimeMillis,location.time)
                assertEquals(now,location.elapsedRealtimeNanos)
                assertEquals(5f,location.accuracy,0f)
                assertEquals(0f,location.speed,0f)
                assertEquals(90f,location.bearing,0f)
                assertTrue(location.hasAltitude())
                assertEquals(55.0,location.altitude,1e-9)
                assertTrue(location.hasVerticalAccuracy())
                assertEquals(3f,location.verticalAccuracyMeters,0f)
                if (name=="gps") assertEquals(7,location.extras!!.getInt("satellites"))
                else assertFalse(location.extras?.containsKey("satellites")==true)
                @Suppress("DEPRECATION")
                assertTrue(location.isFromMockProvider)
            }
        } finally {
            manager.removeUpdates(listener)
            val errors=controller.close()
            instrumentation.runOnMainSync {activity.finish()}
            assertTrue(errors.joinToString("；"),errors.isEmpty())
        }
        names.forEach {assertEquals("$it 清理后属性未恢复",original[it],providerCharacteristics(it))}
        report("gps/network 来源属性、模拟高度/卫星字段、同步 WGS84/时间/零速/mock 输出和清理恢复通过。")
    }

    @Test fun staticWgs84InjectionAndOwnedCleanup() {
        val activity=activity()
        SimulationService.checkReadiness(context)
        val received=AtomicReference<Location>()
        val latch=CountDownLatch(1)
        val listener=object: LocationListener {override fun onLocationChanged(location: Location) {received.set(location); latch.countDown()}}
        val controller=MockLocationController(AndroidProviderAccess(manager))
        val point=Wgs84Point(39.915,116.404)
        val snapshot=SimulationSnapshot(RunState.PAUSED,MotionMode.DWELL,point,point,point,0.0,90.0,5.0,0.0,0.0,0,0.0,0.0,0,false)
        try {
            controller.open(false)
            manager.requestLocationUpdates("gps",0,0f,listener,Looper.getMainLooper())
            val now=SystemClock.elapsedRealtimeNanos()
            controller.output(snapshot,System.currentTimeMillis(),now)
            assertTrue("独立 gps 读取器没有收到位置",latch.await(5,TimeUnit.SECONDS))
            val actual=received.get()
            assertEquals(point.latitude,actual.latitude,1e-9)
            assertEquals(point.longitude,actual.longitude,1e-9)
            assertEquals(now,actual.elapsedRealtimeNanos)
            assertTrue(actual.hasAccuracy())
            assertTrue(actual.hasSpeed())
            assertTrue(actual.hasAltitude())
            assertEquals(55.0,actual.altitude,1e-9)
            assertEquals(7,actual.extras!!.getInt("satellites"))
            @Suppress("DEPRECATION")
            assertTrue(actual.isFromMockProvider)
        } finally {manager.removeUpdates(listener); assertTrue(controller.close().isEmpty()); instrumentation.runOnMainSync {activity.finish()}}
        assertNotNull(manager.getProvider("gps"))
        report("静态 WGS84 GPS 注入和自有 provider 清理通过；底层 gps provider 恢复可用。")
    }

    @Test fun mapDrawingAndOverlayDisplay() {
        Assume.assumeTrue("单独地图复核需显式启用 checkMap=true",InstrumentationRegistry.getArguments().getString("checkMap")=="true")
        val activity=activity()
        try {drawRouteThroughSdk(activity)} finally {instrumentation.runOnMainSync {activity.finish()}}
    }

    @Test fun readinessRejectsMissingFineLocation() {
        Assume.assumeTrue("缺少权限场景需外部撤销授权并显式启用 denyFine=true",InstrumentationRegistry.getArguments().getString("denyFine")=="true")
        assertNotEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION))
        val error=Assert.assertThrows(IllegalStateException::class.java) {SimulationService.checkReadiness(context)}
        assertTrue(error.message.orEmpty().contains("精确定位"))
        assertFalse(SessionState.status.value.active)
        report("拒绝精确定位时开始条件明确拒绝，未创建模拟服务。")
    }

    @Test fun mapAuthFailureIsReported() {
        Assume.assumeTrue("错误包名验收需单独 APK 并显式启用 checkBadMap=true",InstrumentationRegistry.getArguments().getString("checkBadMap")=="true")
        val activity=activity()
        try {
            instrumentation.runOnMainSync {activity.findViewById<android.widget.Button>(R.id.privacy).performClick()}
            waitFor(5.0) {instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("同意")?.any {it.text?.toString()=="同意"}==true}
            instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("同意").first {it.text?.toString()=="同意"}.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitFor(30.0) {MainActivity.latestMapAuthCode!=null}
            assertEquals(230,MainActivity.latestMapAuthCode)
            val model=ViewModelProvider(activity as androidx.lifecycle.ViewModelStoreOwner)[MainViewModel::class.java]
            waitFor(5.0) {model.state.value.message.contains("230")}
            assertTrue(model.state.value.message.contains("鉴权失败"))
            report("错误包名 APK 实际返回 230；界面明确显示鉴权失败、AK/包名/签名核对原因。")
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }

    @Test fun jsonPersistenceAndActivityRecreation() {
        val storage=context.createDeviceProtectedStorageContext()
        val file=java.io.File(storage.filesDir,"routes.json")
        val previous=if (file.exists()) file.readBytes() else null
        val repository=RouteRepository(storage)
        try {
            val expected=SavedData(listOf(syntheticRoute()),SimulationConfig(stopCount=2,walkTimeRatio=0.2,altitudeMeters=123.5,satelliteCount=9))
            repository.save(expected)
            assertEquals(expected,repository.load())
            assertFalse(file.readBytes().take(3)==listOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()))
            val legacy=org.json.JSONObject(file.readText(Charsets.UTF_8))
            legacy.getJSONObject("config").apply {remove("altitudeMeters"); remove("satelliteCount")}
            file.writeText(legacy.toString(),Charsets.UTF_8)
            assertEquals(expected.copy(config=expected.config.copy(altitudeMeters=55.0,satelliteCount=7)),repository.load())
            legacy.getJSONObject("config").put("satelliteCount",1.5)
            file.writeText(legacy.toString(),Charsets.UTF_8)
            assertThrows(IllegalArgumentException::class.java) {repository.load()}
        } finally {if (previous==null) file.delete() else file.writeBytes(previous)}
        val first=activity()
        instrumentation.runOnMainSync {
            first.findViewById<android.widget.Button>(R.id.tab_route).performClick()
            val fields=first.findViewById<android.widget.LinearLayout>(R.id.config_fields)
            ((fields.getChildAt(1) as android.widget.LinearLayout).getChildAt(1) as android.widget.EditText).setText("12.3")
            first.recreate()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val recreated=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first {it is MainActivity}
            val fields=recreated.findViewById<android.widget.LinearLayout>(R.id.config_fields)
            assertEquals("12.3",((fields.getChildAt(1) as android.widget.LinearLayout).getChildAt(1) as android.widget.EditText).text.toString())
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null),"test-route-ui.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            screenshot.recycle()
            recreated.finish()
        }
        report("JSON 保存/重读一致，页面重建保留 12.3 km/h 输入草稿且未崩溃；已生成界面截图。")
    }

    @Test fun interruptedSessionReminderRemainsVisible() {
        val preferences=context.getSharedPreferences("session",Context.MODE_PRIVATE)
        val previousActive=preferences.getBoolean("active",false)
        val previousMessage=preferences.getString("message","").orEmpty()
        val repository=RouteRepository(context)
        repository.setSessionMarker(true,"运行中")
        val activity=activity()
        try {
            val model=ViewModelProvider(activity as androidx.lifecycle.ViewModelStoreOwner)[MainViewModel::class.java]
            waitFor(5.0) {model.state.value.previousSessionMessage.contains("中断")}
            instrumentation.runOnMainSync {model.showMessage("百度地图鉴权成功，可开始绘制路线")}
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val reminder=activity.findViewById<android.widget.TextView>(R.id.last_session)
                assertEquals(android.view.View.VISIBLE,reminder.visibility)
                assertTrue(reminder.text.toString().contains("上次运行被进程或系统中断"))
            }
            assertFalse("重新打开不得自动续跑",SessionState.status.value.active)
            report("持久运行标记在重新打开后显示中断，独立提示未被鉴权消息覆盖，未自动启动会话。")
        } finally {repository.setSessionMarker(previousActive,previousMessage); instrumentation.runOnMainSync {activity.finish()}}
    }

    @Test fun foregroundPauseResumeImmediateEndAndPermissionRevocation() {
        val activity=activity()
        try {
            val plan=SessionPlanner.prepare(syntheticRoute(),SimulationConfig(),77L)
            start(plan)
            waitFor(20.0) {SessionState.status.value.snapshot?.mode==MotionMode.RUN}
            command(SimulationService.PAUSE)
            waitFor(30.0) {SessionState.status.value.snapshot?.state==RunState.PAUSED}
            val paused=SessionState.status.value.snapshot!!
            Thread.sleep(1000)
            val stationary=SessionState.status.value.snapshot!!
            assertEquals(paused.humanDistanceMeters,stationary.humanDistanceMeters,1e-6)
            assertEquals(0.0,stationary.speedMetersPerSecond,1e-9)
            assertTrue(Geo.distanceMeters(paused.reportedPoint,stationary.reportedPoint)>0)
            command(SimulationService.RESUME)
            waitFor(20.0) {SessionState.status.value.snapshot?.state==RunState.RUNNING}
            assertTrue(SessionState.status.value.snapshot!!.humanDistanceMeters>paused.humanDistanceMeters)
            end()
            assertEquals(RunState.IDLE,SessionState.status.value.snapshot!!.state)
            assertNotNull(manager.getProvider("gps"))
            start(plan)
            shell("appops set com.fakerun.app android:mock_location deny")
            waitFor(4.0) {!SessionState.status.value.active}
            assertEquals(RunState.ERROR,SessionState.status.value.snapshot!!.state)
            assertTrue(SessionState.status.value.message.contains("权限") || SessionState.status.value.message.contains("拒绝"))
        } finally {shell("appops set com.fakerun.app android:mock_location allow"); end(); instrumentation.runOnMainSync {activity.finish()}}
        report("前台服务暂停、继续、立即结束及模拟权限撤销停止通过。")
    }

    @Test fun fullTwoLoopBackgroundSessionWithIndependentReader() {
        Assume.assumeTrue("完整真实时钟场景需显式启用 runFull=true",InstrumentationRegistry.getArguments().getString("runFull")=="true")
        val activity=activity()
        val useMapRoute=InstrumentationRegistry.getArguments().getString("useMapRoute")!="false"
        val plan=if (useMapRoute) {
            drawRouteThroughSdk(activity)
            val model=ViewModelProvider(activity as androidx.lifecycle.ViewModelStoreOwner)[MainViewModel::class.java]
            instrumentation.runOnMainSync {
                val fields=activity.findViewById<android.widget.LinearLayout>(R.id.config_fields)
                listOf("2","10","2","10","5","20","3").forEachIndexed {i,value -> ((fields.getChildAt(i) as android.widget.LinearLayout).getChildAt(1) as android.widget.EditText).setText(value)}
                activity.findViewById<android.widget.Button>(R.id.prepare).performClick()
            }
            waitFor(30.0) {model.state.value.prepared!=null}
            model.state.value.prepared!!
        } else SessionPlanner.prepare(syntheticRoute(),SimulationConfig(loopCount=2,stopCount=2,walkTimeRatio=0.2),123456L)
        val gps=mutableListOf<Location>()
        val network=mutableListOf<Location>()
        val mismatches=mutableListOf<String>()
        val listener=object: LocationListener {
            override fun onLocationChanged(location: Location) {
                synchronized(gps) {
                    (if (location.provider=="gps") gps else network).add(Location(location))
                    val snapshot=SessionState.status.value.snapshot
                    if (snapshot!=null) {
                        val delaySeconds=abs(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1e9
                        val distanceMeters=Geo.distanceMeters(Wgs84Point(location.latitude,location.longitude),snapshot.reportedPoint)
                        if (distanceMeters>3+snapshot.speedMetersPerSecond*delaySeconds) mismatches+="读取位置偏差 $distanceMeters 米"
                        @Suppress("DEPRECATION")
                        if (!location.isFromMockProvider) mismatches+="收到非 mock 位置"
                        if (!location.hasSpeed() || !location.hasBearing() || !location.hasAccuracy() || !location.hasAltitude() || !location.hasVerticalAccuracy()) mismatches+="字段不符合计划"
                        if (abs(location.altitude-snapshot.altitudeMeters)>0.1) mismatches+="高度与观测快照不一致"
                        if (location.provider=="gps" && location.extras?.getInt("satellites")!=snapshot.satelliteCount) mismatches+="模拟卫星数与配置不一致"
                        if (abs(location.speed-snapshot.speedMetersPerSecond)>0.3) mismatches+="报告速度与人物速度不一致"
                        if (snapshot.mode==MotionMode.RUN && location.speed*3.6 !in 7.999..12.001) mismatches+="跑步巡航超出 8～12 km/h"
                        if (snapshot.mode==MotionMode.WALK && location.speed*3.6 !in 3.999..6.001) mismatches+="步行巡航超出 4～6 km/h"
                    }
                }
            }
        }
        try {
            if (useMapRoute) {
                instrumentation.runOnMainSync {activity.findViewById<android.widget.Button>(R.id.start).performClick()}
                waitFor(5.0) {SessionState.status.value.active && SessionState.status.value.snapshot!=null}
            } else start(plan)
            report("完整预览 seed=${plan.seed}；人物总距 ${plan.path.distanceMeters} 米，预计移动 ${plan.movingSeconds} 秒；驻留 ${plan.stops.map {it.durationSeconds}} 秒；预计步行 ${plan.walkingSeconds/plan.movingSeconds*100}%。")
            manager.requestLocationUpdates("gps",0,0f,listener,Looper.getMainLooper())
            manager.requestLocationUpdates("network",0,0f,listener,Looper.getMainLooper())
            instrumentation.runOnMainSync {activity.moveTaskToBack(true)}
            shell("input keyevent KEYCODE_SLEEP")
            var lastReport=SystemClock.elapsedRealtime()
            val deadline=SystemClock.elapsedRealtime()+(plan.totalSeconds*1000).toLong()+10000
            while (SessionState.status.value.active && SystemClock.elapsedRealtime()<deadline) {
                Thread.sleep(500)
                if (SystemClock.elapsedRealtime()-lastReport>=60000) {
                    val current=SessionState.status.value.snapshot!!
                    report("完整场景进度：${current.humanDistanceMeters.toInt()}/${plan.path.distanceMeters.toInt()} 米，${current.completedLoops} 圈，停留 ${current.completedStops} 次，状态 ${current.state}")
                    lastReport=SystemClock.elapsedRealtime()
                }
            }
            val status=SessionState.status.value
            assertFalse(status.message,status.active)
            val result=status.snapshot!!
            assertEquals(status.message,RunState.COMPLETED,result.state)
            assertEquals(2,result.completedLoops)
            assertEquals(2,result.completedStops)
            assertTrue(abs(result.walkingSeconds/result.movingSeconds-0.2)<=0.010001)
            assertEquals(plan.path.distanceMeters,result.humanDistanceMeters,1e-6)
            assertTrue("独立读取器与计划偏差：${mismatches.take(5)}",mismatches.isEmpty())
            synchronized(gps) {
                assertTrue("gps 频率不足",gps.size>=plan.totalSeconds*4)
                assertTrue("network 频率不足",network.size>=plan.totalSeconds*4)
                gps.zipWithNext().forEach {(a,b) ->assertTrue(b.elapsedRealtimeNanos>a.elapsedRealtimeNanos)}
                val byTime=network.associateBy {it.elapsedRealtimeNanos}
                val paired=gps.mapNotNull {gpsPoint -> byTime[gpsPoint.elapsedRealtimeNanos]?.let {gpsPoint to it}}
                assertTrue("两种 provider 没有同步输出",paired.size>=gps.size-3)
                paired.forEach {(a,b) ->assertEquals(a.latitude,b.latitude,1e-9); assertEquals(a.longitude,b.longitude,1e-9); assertEquals(a.time,b.time); assertEquals(a.speed,b.speed,0f); assertEquals(a.accuracy,b.accuracy,0f)}
                val dwells=plan.segments.filter {it.mode==MotionMode.DWELL}
                dwells.forEach {segment ->
                    val point=plan.path.at(segment.startMeters).humanPoint
                    val stationary=gps.filter {it.speed==0f && Geo.distanceMeters(point,Wgs84Point(it.latitude,it.longitude))<2.001}
                    assertTrue("驻留采样不足",stationary.isNotEmpty())
                    val durationSeconds=(stationary.last().elapsedRealtimeNanos-stationary.first().elapsedRealtimeNanos)/1e9+0.2
                    report("停止 ${segment.stopIndex}：预览 ${segment.curve.durationSeconds} 秒，系统零速观测 $durationSeconds 秒，误差 ${abs(segment.curve.durationSeconds-durationSeconds)} 秒。")
                    assertEquals(segment.curve.durationSeconds,durationSeconds,0.2)
                }
            }
            assertNotNull(manager.getProvider("gps"))
            assertNotNull(manager.getProvider("network"))
            report("完整 2 圈 / 2 次驻留 / 20% 步行、后台及息屏、gps 与 network 独立读取通过。移动 ${result.movingSeconds} 秒；GPS ${gps.size} 条、network ${network.size} 条。")
        } finally {manager.removeUpdates(listener); shell("input keyevent KEYCODE_WAKEUP"); end(); instrumentation.runOnMainSync {activity.finish()}}
    }
}
