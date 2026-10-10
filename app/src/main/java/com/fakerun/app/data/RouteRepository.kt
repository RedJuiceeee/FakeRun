package com.fakerun.app.data

import android.content.Context
import android.util.AtomicFile
import com.fakerun.app.geo.SupportedRegion
import com.fakerun.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedData(val routes: List<Route>, val config: SimulationConfig)

class RouteRepository(context: Context) {
    private val file=AtomicFile(File(context.filesDir,"routes.json"))
    private val preferences=context.getSharedPreferences("session",Context.MODE_PRIVATE)
    fun load(): SavedData {
        if (!file.baseFile.exists()) return SavedData(emptyList(),SimulationConfig())
        val root=file.openRead().use {JSONObject(it.readBytes().toString(Charsets.UTF_8))}
        require(root.getInt("version")==1) {"本地文件版本不受支持"}
        val routeArray=root.getJSONArray("routes")
        val routes=(0 until routeArray.length()).map {index ->
            val item=routeArray.getJSONObject(index)
            require(item.getInt("version")==1) {"路线保存版本不受支持"}
            val nodeArray=item.getJSONArray("nodesGcj02")
            require(nodeArray.length() in 0..500) {"本地路线节点超过限制"}
            val nodes=(0 until nodeArray.length()).map {nodeIndex ->
                val node=nodeArray.getJSONObject(nodeIndex)
                Gcj02Point(node.getDouble("latitude"),node.getDouble("longitude")).also {require(SupportedRegion.contains(it)) {"本地节点位于不支持地区"}}
            }
            val id=item.getString("id")
            val name=item.getString("name")
            require(id.isNotBlank() && name.isNotBlank() && name.length<=80) {"本地路线 ID 或名称无效"}
            Route(id,name,nodes,item.getBoolean("closed"))
        }
        require(routes.map {it.id}.distinct().size==routes.size) {"本地路线 ID 重复"}
        val value=root.getJSONObject("config")
        val config=SimulationConfig(value.getInt("loopCount"),value.getDouble("runSpeedKmh"),value.getInt("stopCount"),value.getInt("stopDurationSeconds"),value.getDouble("walkSpeedKmh"),value.getDouble("walkTimeRatio"),value.getBoolean("naturalSway"),value.getBoolean("avoidance"),value.getDouble("maxLateralOffsetMeters"),value.getBoolean("gpsDrift"),value.getBoolean("mockNetwork"))
        config.validate()
        require(config.walkSpeedKmh.isFinite()) {"本地 JSON 步行速度不是有限数值"}
        return SavedData(routes,config)
    }
    fun save(data: SavedData) {
        val routes=JSONArray()
        data.routes.forEach {route ->
            val nodes=JSONArray()
            route.nodes.forEach {nodes.put(JSONObject().put("latitude",it.latitude).put("longitude",it.longitude))}
            routes.put(JSONObject().put("id",route.id).put("name",route.name).put("version",route.version).put("closed",route.closed).put("nodesGcj02",nodes))
        }
        val c=data.config
        val config=JSONObject().put("loopCount",c.loopCount).put("runSpeedKmh",c.runSpeedKmh).put("stopCount",c.stopCount).put("stopDurationSeconds",c.stopDurationSeconds).put("walkSpeedKmh",c.walkSpeedKmh).put("walkTimeRatio",c.walkTimeRatio).put("naturalSway",c.naturalSway).put("avoidance",c.avoidance).put("maxLateralOffsetMeters",c.maxLateralOffsetMeters).put("gpsDrift",c.gpsDrift).put("mockNetwork",c.mockNetwork)
        val bytes=JSONObject().put("version",1).put("routes",routes).put("config",config).toString(2).toByteArray(Charsets.UTF_8)
        val stream=file.startWrite()
        try {stream.write(bytes); file.finishWrite(stream)} catch (error: java.io.IOException) {file.failWrite(stream); throw error}
    }
    fun setSessionMarker(active: Boolean, message: String) {
        if (!preferences.edit().putBoolean("active",active).putString("message",message).commit()) throw java.io.IOException("无法保存运行标记")
    }
    fun previousSessionMessage(): String = if (preferences.getBoolean("active",false)) "上次运行被进程或系统中断，请重新预览并开始" else preferences.getString("message","").orEmpty()
}
