package com.fakerun.app.simulation

import kotlin.math.*

data class SpeedCurve(val startMetersPerSecond: Double, val endMetersPerSecond: Double, val durationSeconds: Double) {
    val distanceMeters get() = (startMetersPerSecond+endMetersPerSecond)*durationSeconds/2
    fun speedAt(seconds: Double): Double = startMetersPerSecond+(endMetersPerSecond-startMetersPerSecond)*RandomValues.smoothStep5((seconds/durationSeconds).coerceIn(0.0,1.0))
    fun distanceAt(seconds: Double): Double {
        val u=(seconds/durationSeconds).coerceIn(0.0,1.0)
        return durationSeconds*(startMetersPerSecond*u+(endMetersPerSecond-startMetersPerSecond)*(2.5*u.pow(4)-3*u.pow(5)+u.pow(6)))
    }
    companion object {
        fun transition(startMetersPerSecond: Double, endMetersPerSecond: Double): SpeedCurve {
            val delta=abs(endMetersPerSecond-startMetersPerSecond)
            return SpeedCurve(startMetersPerSecond,endMetersPerSecond,max(2.0,max(1.875*delta/0.8,sqrt(5.774*delta/0.6))))
        }
    }
}
