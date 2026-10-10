package com.fakerun.app.geo

import com.fakerun.app.model.Wgs84Point
import kotlin.math.*

data class LocalVector(val eastMeters: Double, val northMeters: Double) {
    val lengthMeters get() = hypot(eastMeters, northMeters)
    operator fun plus(other: LocalVector) = LocalVector(eastMeters+other.eastMeters, northMeters+other.northMeters)
    operator fun minus(other: LocalVector) = LocalVector(eastMeters-other.eastMeters, northMeters-other.northMeters)
    operator fun times(scale: Double) = LocalVector(eastMeters*scale, northMeters*scale)
}
object Geo {
    private const val A = 6378137.0
    private const val E2 = 0.00669437999014
    private fun scales(point: Wgs84Point): Pair<Double,Double> {
        val latitudeRadians = Math.toRadians(point.latitude)
        val magic = 1-E2*sin(latitudeRadians).pow(2)
        return A/sqrt(magic)*cos(latitudeRadians) to A*(1-E2)/magic.pow(1.5)
    }
    fun deltaMeters(origin: Wgs84Point, point: Wgs84Point): LocalVector {
        val (eastScale,northScale) = scales(origin)
        return LocalVector(Math.toRadians(point.longitude-origin.longitude)*eastScale, Math.toRadians(point.latitude-origin.latitude)*northScale)
    }
    fun offset(point: Wgs84Point, vector: LocalVector): Wgs84Point {
        val (eastScale,northScale) = scales(point)
        return Wgs84Point(point.latitude+Math.toDegrees(vector.northMeters/northScale), point.longitude+Math.toDegrees(vector.eastMeters/eastScale))
    }
    fun distanceMeters(a: Wgs84Point, b: Wgs84Point): Double = deltaMeters(Wgs84Point((a.latitude+b.latitude)/2,(a.longitude+b.longitude)/2), b).lengthMeters*2
    fun interpolate(a: Wgs84Point, b: Wgs84Point, fraction: Double) = offset(a, deltaMeters(a,b)*fraction)
    fun bearingDegrees(a: Wgs84Point, b: Wgs84Point): Double {
        val delta = deltaMeters(a,b)
        return (Math.toDegrees(atan2(delta.eastMeters,delta.northMeters))+360)%360
    }
}
