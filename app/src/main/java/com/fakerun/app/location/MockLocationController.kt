package com.fakerun.app.location

import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import com.fakerun.app.model.SimulationSnapshot

interface ProviderAccess {
    fun create(name: String)
    fun enable(name: String)
    fun output(name: String, snapshot: SimulationSnapshot, wallTimeMillis: Long, monotonicTimeNanos: Long)
    fun remove(name: String)
}

class AndroidProviderAccess(private val manager: LocationManager): ProviderAccess {
    @Suppress("DEPRECATION")
    override fun create(name: String) {
        // 覆盖前读取系统属性，避免把 GPS 改成低功耗或把 network 改成精确定位来源。
        // 本应用输出高度、速度和方向，因此显式声明这些能力。
        if (Build.VERSION.SDK_INT>=31) {
            check(manager.hasProvider(name)) {missingProviderMessage(name)}
            val properties=manager.getProviderProperties(name)
                ?: throw IllegalStateException("$name 的系统定位属性尚未就绪，请稍后重试")
            manager.addTestProvider(name,ProviderProperties.Builder(properties)
                .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true).build())
        } else {
            val provider=manager.getProvider(name)
                ?: throw IllegalStateException(missingProviderMessage(name))
            manager.addTestProvider(name,provider.requiresNetwork(),provider.requiresSatellite(),
                provider.requiresCell(),provider.hasMonetaryCost(),true,true,true,
                provider.powerRequirement,provider.accuracy)
        }
    }
    private fun missingProviderMessage(name: String) = if (name==LocationManager.NETWORK_PROVIDER)
        "设备没有 network 定位来源，请关闭“同时模拟 network”后重新生成预览"
        else "设备没有 $name 定位来源，无法开始模拟"
    override fun enable(name: String) = manager.setTestProviderEnabled(name,true)
    override fun output(name: String, snapshot: SimulationSnapshot, wallTimeMillis: Long, monotonicTimeNanos: Long) {
        val location=Location(name).apply {
            latitude=snapshot.reportedPoint.latitude
            longitude=snapshot.reportedPoint.longitude
            accuracy=snapshot.accuracyMeters.toFloat()
            speed=snapshot.speedMetersPerSecond.toFloat()
            bearing=snapshot.bearingDegrees.toFloat()
            time=wallTimeMillis
            elapsedRealtimeNanos=monotonicTimeNanos
            altitude=snapshot.altitudeMeters
            verticalAccuracyMeters=snapshot.verticalAccuracyMeters.toFloat()
            if (name==LocationManager.GPS_PROVIDER) {
                // 与参考项目一致的旧客户端附加字段，不会产生 GNSS 卫星状态回调。
                extras=Bundle().apply {putInt("satellites",snapshot.satelliteCount)}
            }
        }
        manager.setTestProviderLocation(name,location)
    }
    override fun remove(name: String) = manager.removeTestProvider(name)
}

class MockLocationController(private val access: ProviderAccess) {
    private val created=mutableListOf<String>()
    fun open(mockNetwork: Boolean) {
        val names=if (mockNetwork) listOf("gps","network") else listOf("gps")
        names.forEach {name ->
            access.create(name)
            created+=name
            access.enable(name)
        }
    }
    fun output(snapshot: SimulationSnapshot, wallTimeMillis: Long, monotonicTimeNanos: Long) = created.forEach {access.output(it,snapshot,wallTimeMillis,monotonicTimeNanos)}
    fun close(): List<String> {
        val errors=mutableListOf<String>()
        created.asReversed().forEach {name ->
            try {access.remove(name)} catch (error: SecurityException) {errors+="$name 清理被系统拒绝：${error.message}"} catch (error: IllegalArgumentException) {errors+="$name 清理失败：${error.message}"}
        }
        created.clear()
        return errors
    }
}
