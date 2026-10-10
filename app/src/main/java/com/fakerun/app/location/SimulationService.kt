package com.fakerun.app.location

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.*
import com.fakerun.app.R
import com.fakerun.app.data.RouteRepository
import com.fakerun.app.model.*
import com.fakerun.app.simulation.SimulationEngine
import com.fakerun.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class ServiceStatus(val active: Boolean=false, val snapshot: SimulationSnapshot?=null, val message: String="")
object SessionState {
    private val mutableStatus=MutableStateFlow(ServiceStatus())
    val status=mutableStatus.asStateFlow()
    val pendingPlan=AtomicReference<PreparedSimulation?>(null)
    @Volatile var currentPlan: PreparedSimulation?=null
    fun publish(status: ServiceStatus) {mutableStatus.value=status}
}

class SimulationService: Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var repository: RouteRepository
    private lateinit var manager: LocationManager
    private var controller: MockLocationController?=null
    private var engine: SimulationEngine?=null
    private var wakeLock: PowerManager.WakeLock?=null
    private var startTimeNanos=0L
    private var nextTickNanos=0L
    private var cleaned=false
    private var lastNotificationState: RunState?=null

    override fun onCreate() {
        super.onCreate()
        repository=RouteRepository(this)
        manager=getSystemService(LocationManager::class.java)
        thread=HandlerThread("FakeRunLocation")
        thread.start()
        handler=Handler(thread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"模拟位置运行",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action==START) {
            val plan=SessionState.pendingPlan.getAndSet(null)
            if (plan!=null && !SessionState.status.value.active) {
                try {
                    if (Build.VERSION.SDK_INT>=29) startForeground(1,notification(RunState.RUNNING),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    else startForeground(1,notification(RunState.RUNNING))
                    SessionState.currentPlan=plan
                    SessionState.publish(ServiceStatus(true,message="正在准备模拟位置 provider"))
                    handler.post {startSession(plan)}
                } catch (error: RuntimeException) {
                    SessionState.publish(ServiceStatus(message="系统拒绝前台服务：${error.message}"))
                    handler.post {fail("系统拒绝前台服务：${error.message}")}
                }
            }
        } else handler.post {
            val activeEngine=engine ?: return@post
            try {
                activeEngine.tick(elapsedSeconds())
                when (intent?.action) {PAUSE -> activeEngine.pause(); RESUME -> activeEngine.resume(); END -> activeEngine.endSmoothly(); IMMEDIATE_END -> activeEngine.immediatelyEnd()}
                publish()
                if (activeEngine.state in TERMINAL) cleanup(activeEngine.message)
            } catch (error: RuntimeException) {fail("操作失败：${error.message}")}
        }
        return START_NOT_STICKY
    }
    private fun startSession(plan: PreparedSimulation) {
        try {
            checkReadiness(this)
            repository.setSessionMarker(true,"运行中")
            engine=SimulationEngine(plan)
            controller=MockLocationController(AndroidProviderAccess(manager))
            controller!!.open(plan.config.mockNetwork)
            wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FakeRun:session").apply {acquire()}
            startTimeNanos=SystemClock.elapsedRealtimeNanos()
            nextTickNanos=startTimeNanos
            handler.post(tick)
        } catch (error: RuntimeException) {fail("模拟定位接入失败，请检查开发者选项：${error.message}")}
        catch (error: java.io.IOException) {fail("运行标记保存失败：${error.message}")}
    }
    private val tick=object: Runnable {
        override fun run() {
            val activeEngine=engine ?: return
            if (cleaned) return
            val now=SystemClock.elapsedRealtimeNanos()
            if (now-nextTickNanos>1_000_000_000L) {
                activeEngine.interrupt("定位回调迟到超过 1 秒，已中断，请重新开始")
                publish(); cleanup(activeEngine.message); return
            }
            try {
                checkReadiness(this@SimulationService)
                val snapshot=activeEngine.tick((now-startTimeNanos)/1e9)
                controller!!.output(snapshot,System.currentTimeMillis(),now)
                publish()
                if (activeEngine.state in TERMINAL) {cleanup(activeEngine.message); return}
                nextTickNanos+=200_000_000L
                while (nextTickNanos<=now) nextTickNanos+=200_000_000L
                handler.postDelayed(this,maxOf(1,(nextTickNanos-SystemClock.elapsedRealtimeNanos())/1_000_000))
            } catch (error: RuntimeException) {fail("定位权限撤销或注入失败：${error.message}")}
        }
    }
    private fun elapsedSeconds() = if (startTimeNanos==0L) 0.0 else (SystemClock.elapsedRealtimeNanos()-startTimeNanos)/1e9
    private fun publish() {
        val snapshot=engine?.snapshot() ?: return
        SessionState.publish(ServiceStatus(!cleaned,snapshot,snapshot.message))
        if (snapshot.state!=lastNotificationState) {
            getSystemService(NotificationManager::class.java).notify(1,notification(snapshot.state))
            lastNotificationState=snapshot.state
        }
    }
    private fun fail(message: String) {
        engine?.error(message)
        SessionState.publish(ServiceStatus(false,engine?.snapshot(),message))
        cleanup(message)
    }
    private fun cleanup(message: String) {
        if (cleaned) return
        cleaned=true
        handler.removeCallbacks(tick)
        val errors=controller?.close().orEmpty()
        controller=null
        wakeLock?.let {if (it.isHeld) it.release()}
        wakeLock=null
        var result=(listOf(message)+errors).filter {it.isNotBlank()}.joinToString("；")
        try {repository.setSessionMarker(false,result)} catch (error: java.io.IOException) {result+="；无法保存结束状态：${error.message}"}
        SessionState.publish(ServiceStatus(false,engine?.snapshot(),result))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        handler.post {
            if (!cleaned) {engine?.interrupt("系统停止了模拟服务，请重新开始"); cleanup("系统停止了模拟服务，请重新开始")}
            thread.quitSafely()
        }
        super.onDestroy()
    }
    private fun notification(state: RunState): Notification {
        fun action(action: String, request: Int) = PendingIntent.getService(this,request,Intent(this,SimulationService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_runner).setContentTitle("FakeRun 模拟位置").setContentText(if (state==RunState.PAUSED) "已暂停，点击返回应用" else "运行中，点击查看状态").setContentIntent(open).setOngoing(true)
        if (state==RunState.PAUSED) builder.addAction(Notification.Action.Builder(null,"继续",action(RESUME,2)).build())
        else if (state==RunState.RUNNING) builder.addAction(Notification.Action.Builder(null,"暂停",action(PAUSE,1)).build())
        builder.addAction(Notification.Action.Builder(null,"立即结束",action(IMMEDIATE_END,3)).build())
        return builder.build()
    }
    companion object {
        const val START="com.fakerun.app.START"
        const val PAUSE="com.fakerun.app.PAUSE"
        const val RESUME="com.fakerun.app.RESUME"
        const val END="com.fakerun.app.END"
        const val IMMEDIATE_END="com.fakerun.app.IMMEDIATE_END"
        private const val CHANNEL="simulation"
        private val TERMINAL=listOf(RunState.IDLE,RunState.COMPLETED,RunState.INTERRUPTED,RunState.ERROR)
        @Suppress("DEPRECATION")
        fun checkReadiness(context: Context) {
            check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {"请允许精确定位权限"}
            val locationEnabled=if (Build.VERSION.SDK_INT>=28) context.getSystemService(LocationManager::class.java).isLocationEnabled
            else android.provider.Settings.Secure.getInt(context.contentResolver,android.provider.Settings.Secure.LOCATION_MODE,0)!=android.provider.Settings.Secure.LOCATION_MODE_OFF
            check(locationEnabled) {"请打开系统定位开关"}
            val appOps=context.getSystemService(AppOpsManager::class.java)
            check(appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED) {"请在开发者选项中选择 FakeRun 为模拟位置信息应用"}
        }
    }
}
