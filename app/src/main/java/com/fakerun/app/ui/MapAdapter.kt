package com.fakerun.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import com.baidu.mapapi.map.*
import com.baidu.mapapi.model.LatLng
import com.fakerun.app.geo.CoordinateTransformer
import com.fakerun.app.model.*

class MapAdapter(context: Context, private val onAdd: (Gcj02Point)->Unit, private val onEdit: (Int)->Unit, private val onMove: (Int,Gcj02Point)->Unit) {
    val view=MapView(context)
    private val map=view.map
    private val nodeIcons=mutableListOf<BitmapDescriptor>()
    private var editable=true
    var drawing=false
    private var humanMarker: Marker?=null
    private var reportedMarker: Marker?=null
    private var trail: Polyline?=null
    private val trailPoints=ArrayList<LatLng>()
    private val humanIcon=icon(Color.rgb(8,127,115),"人")
    private val reportedIcon=icon(Color.rgb(225,116,39),"GPS")
    private var lastRouteId: String?=null
    private var displayedPlan: PreparedSimulation?=null
    init {
        map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(LatLng(39.915,116.404),17f))
        map.setOnMapClickListener(object: BaiduMap.OnMapClickListener {
            override fun onMapClick(point: LatLng) {if (drawing && editable) onAdd(Gcj02Point(point.latitude,point.longitude))}
            override fun onMapPoiClick(poi: MapPoi) {if (drawing && editable) onAdd(Gcj02Point(poi.position.latitude,poi.position.longitude))}
        })
        map.setOnMarkerClickListener {marker ->
            val index=marker.extraInfo?.getInt("nodeIndex",-1) ?: -1
            if (editable && index>=0) onEdit(index)
            true
        }
        map.setOnMarkerDragListener(object: BaiduMap.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
                val index=marker.extraInfo?.getInt("nodeIndex",-1) ?: -1
                if (editable && index>=0) onMove(index,Gcj02Point(marker.position.latitude,marker.position.longitude))
            }
        })
    }
    private fun latLng(point: Wgs84Point): LatLng = CoordinateTransformer.toGcj02(point).let {LatLng(it.latitude,it.longitude)}
    fun show(route: Route, plan: PreparedSimulation?, active: Boolean) {
        map.clear()
        nodeIcons.forEach {it.recycle()}; nodeIcons.clear()
        humanMarker=null; reportedMarker=null; trail=null
        trailPoints.clear()
        editable=!active
        val routePoints=route.nodes.map {LatLng(it.latitude,it.longitude)}.toMutableList()
        if (route.closed && routePoints.isNotEmpty()) routePoints+=routePoints.first()
        if (routePoints.size>=2) map.addOverlay(PolylineOptions().points(routePoints).color(Color.rgb(77,108,178)).width(6f))
        route.nodes.forEachIndexed {index,point ->
            val bitmap=icon(Color.rgb(77,108,178),(index+1).toString())
            nodeIcons+=bitmap
            map.addOverlay(MarkerOptions().position(LatLng(point.latitude,point.longitude)).icon(bitmap).draggable(editable).extraInfo(Bundle().apply {putInt("nodeIndex",index)}))
        }
        if (plan!=null) {
            // 地图只画一圈中心线；完整人物路径覆盖每圈的独立侧向动作。
            val samples=plan.path.samples.filterIndexed {index,_ -> index%10==0}.map {latLng(it.humanPoint)}.toMutableList()
            samples+=latLng(plan.path.samples.last().humanPoint)
            drawLine(samples,Color.rgb(8,127,115),4f)
            plan.walkingRegion?.let {region ->
                val walking=plan.path.samples.filter {it.humanDistanceMeters in region.startMeters..region.endMeters}.filterIndexed {index,_ -> index%10==0}.map {latLng(it.humanPoint)}
                drawLine(walking,Color.rgb(139,73,183),7f)
            }
            plan.stops.forEach {event ->
                val bitmap=icon(Color.rgb(171,57,48),"停${event.index}"); nodeIcons+=bitmap
                map.addOverlay(MarkerOptions().position(latLng(plan.path.at(event.humanDistanceMeters).humanPoint)).icon(bitmap))
            }
            if (displayedPlan!==plan) map.setMapStatus(MapStatusUpdateFactory.newLatLng(latLng(plan.path.at(0.0).humanPoint)))
        } else if (routePoints.isNotEmpty() && lastRouteId!=route.id) map.setMapStatus(MapStatusUpdateFactory.newLatLng(routePoints.first()))
        displayedPlan=plan
        lastRouteId=route.id
    }
    fun update(snapshot: SimulationSnapshot) {
        val human=latLng(snapshot.humanPoint)
        val reported=latLng(snapshot.reportedPoint)
        if (humanMarker==null) humanMarker=map.addOverlay(MarkerOptions().position(human).icon(humanIcon).zIndex(20)) as Marker
        else humanMarker!!.position=human
        if (reportedMarker==null) reportedMarker=map.addOverlay(MarkerOptions().position(reported).icon(reportedIcon).zIndex(21)) as Marker
        else reportedMarker!!.position=reported
        if (snapshot.speedMetersPerSecond>0 && (trailPoints.isEmpty() || trailPoints.last()!=human)) {
            trailPoints+=human
            if (trailPoints.size>2500) {
                val reduced=trailPoints.filterIndexed {index,_ -> index%2==0}
                trailPoints.clear(); trailPoints.addAll(reduced)
            }
            if (trailPoints.size>=2) {
                if (trail==null) trail=map.addOverlay(PolylineOptions().points(trailPoints).color(Color.rgb(26,173,143)).width(8f)) as Polyline
                else trail!!.points=trailPoints.toList()
            }
        }
    }
    private fun drawLine(points: List<LatLng>, color: Int, width: Float) {
        var start=0
        while (start<points.lastIndex) {
            val end=minOf(points.size,start+5000)
            map.addOverlay(PolylineOptions().points(points.subList(start,end)).color(color).width(width))
            start=end-1
        }
    }
    private fun icon(color: Int, label: String): BitmapDescriptor {
        val bitmap=Bitmap.createBitmap(76,52,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color}
        canvas.drawRoundRect(2f,2f,74f,50f,20f,20f,paint)
        paint.color=Color.WHITE; paint.textAlign=Paint.Align.CENTER; paint.textSize=22f
        canvas.drawText(label,38f,34f,paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
    fun resume() = view.onResume()
    fun pause() = view.onPause()
    fun destroy() {view.onDestroy(); nodeIcons.forEach {it.recycle()}; humanIcon.recycle(); reportedIcon.recycle()}
}
