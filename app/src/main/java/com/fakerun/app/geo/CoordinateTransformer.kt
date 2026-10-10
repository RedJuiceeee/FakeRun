package com.fakerun.app.geo

import com.fakerun.app.model.*
import kotlin.math.*

// 移植自 wandergis/coordtransform，原作者及 MIT 许可见 docs/third-party.md。
object CoordinateTransformer {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun toGcj02(point: Wgs84Point): Gcj02Point {
        val x = point.longitude - 105.0
        val y = point.latitude - 35.0
        var latitudeDelta = -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*sqrt(abs(x))
        latitudeDelta += (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
        latitudeDelta += (20*sin(y*PI) + 40*sin(y/3*PI))*2/3
        latitudeDelta += (160*sin(y/12*PI) + 320*sin(y*PI/30))*2/3
        var longitudeDelta = 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*sqrt(abs(x))
        longitudeDelta += (20*sin(6*x*PI) + 20*sin(2*x*PI))*2/3
        longitudeDelta += (20*sin(x*PI) + 40*sin(x/3*PI))*2/3
        longitudeDelta += (150*sin(x/12*PI) + 300*sin(x/30*PI))*2/3
        val latitudeRadians = Math.toRadians(point.latitude)
        val magic = 1 - EE*sin(latitudeRadians).pow(2)
        latitudeDelta *= 180 / ((A*(1-EE))/(magic*sqrt(magic))*PI)
        longitudeDelta *= 180 / (A/sqrt(magic)*cos(latitudeRadians)*PI)
        return Gcj02Point(point.latitude + latitudeDelta, point.longitude + longitudeDelta)
    }

    fun toWgs84(point: Gcj02Point): Wgs84Point {
        require(SupportedRegion.contains(point)) { "坐标位于首版不支持的地区或边界附近，请选中国大陆普通城市区域" }
        val forward = toGcj02(Wgs84Point(point.latitude, point.longitude))
        var result = Wgs84Point(2*point.latitude-forward.latitude, 2*point.longitude-forward.longitude)
        repeat(20) {
            val check = toGcj02(result)
            if (Geo.distanceMeters(Wgs84Point(check.latitude, check.longitude), Wgs84Point(point.latitude, point.longitude)) < 0.2) return result
            result = Wgs84Point(result.latitude-(check.latitude-point.latitude), result.longitude-(check.longitude-point.longitude))
        }
        throw IllegalArgumentException("坐标逆转换在 20 次迭代内未达到 0.2 米回代残差，请修改节点")
    }
}

object SupportedRegion {
    // 保守的大陆内陆范围，不宣称覆盖国界或领土；沿轮廓另保留 20 km 缓冲。
    private val outline = listOf(
        Gcj02Point(39.0,75.0), Gcj02Point(42.0,80.0), Gcj02Point(47.0,83.0),
        Gcj02Point(48.0,87.0), Gcj02Point(42.0,91.0), Gcj02Point(42.0,96.0),
        Gcj02Point(40.0,101.0), Gcj02Point(42.0,110.0), Gcj02Point(46.0,117.0),
        Gcj02Point(49.0,120.0), Gcj02Point(52.0,122.0), Gcj02Point(53.0,125.0),
        Gcj02Point(48.0,130.0), Gcj02Point(47.0,134.0), Gcj02Point(43.0,131.0),
        Gcj02Point(40.0,124.0), Gcj02Point(38.0,123.0), Gcj02Point(35.0,123.0),
        Gcj02Point(31.0,122.5), Gcj02Point(27.0,121.0), Gcj02Point(24.0,118.5),
        Gcj02Point(22.65,115.0), Gcj02Point(22.65,113.0), Gcj02Point(21.0,110.0),
        Gcj02Point(21.0,108.0), Gcj02Point(23.0,106.0), Gcj02Point(22.0,102.0),
        Gcj02Point(24.0,98.0), Gcj02Point(28.0,99.0), Gcj02Point(29.0,96.0),
        Gcj02Point(28.0,92.0), Gcj02Point(29.0,86.0), Gcj02Point(32.0,79.0),
        Gcj02Point(36.0,79.0)
    )
    fun contains(point: Gcj02Point): Boolean {
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) return false
        if (point.latitude in 18.3..19.9 && point.longitude in 109.0..110.8) return true
        var inside = false
        outline.indices.forEach { i ->
            val a = outline[i]
            val b = outline[(i+1)%outline.size]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude-a.longitude)*(point.latitude-a.latitude)/(b.latitude-a.latitude)+a.longitude) inside = !inside
        }
        if (!inside) return false
        val origin = Wgs84Point(point.latitude, point.longitude)
        return outline.indices.all { i ->
            val a = Geo.deltaMeters(origin, Wgs84Point(outline[i].latitude, outline[i].longitude))
            val b = Geo.deltaMeters(origin, Wgs84Point(outline[(i+1)%outline.size].latitude, outline[(i+1)%outline.size].longitude))
            val edge = b-a
            val u = (-(a.eastMeters*edge.eastMeters+a.northMeters*edge.northMeters)/(edge.lengthMeters*edge.lengthMeters)).coerceIn(0.0,1.0)
            (a+edge*u).lengthMeters > 20000
        }
    }
}
