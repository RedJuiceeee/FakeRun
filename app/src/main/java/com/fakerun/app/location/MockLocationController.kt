package com.fakerun.app.location

import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
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
        if (Build.VERSION.SDK_INT>=31) {
            manager.addTestProvider(name,ProviderProperties.Builder().setHasNetworkRequirement(false).setHasSatelliteRequirement(name==LocationManager.GPS_PROVIDER).setHasCellRequirement(false).setHasMonetaryCost(false).setHasAltitudeSupport(false).setHasSpeedSupport(true).setHasBearingSupport(true).setPowerUsage(ProviderProperties.POWER_USAGE_LOW).setAccuracy(ProviderProperties.ACCURACY_FINE).build())
        } else manager.addTestProvider(name,false,name==LocationManager.GPS_PROVIDER,false,false,false,true,true,ProviderProperties.POWER_USAGE_LOW,ProviderProperties.ACCURACY_FINE)
    }
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
