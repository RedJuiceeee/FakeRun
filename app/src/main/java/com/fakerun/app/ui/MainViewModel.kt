package com.fakerun.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fakerun.app.data.*
import com.fakerun.app.location.SessionState
import com.fakerun.app.model.*
import com.fakerun.app.simulation.SessionPlanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class EditorState(val route: Route=Route(UUID.randomUUID().toString(),"未命名路线",emptyList(),false), val config: SimulationConfig=SimulationConfig(), val prepared: PreparedSimulation?=null, val preparing: Boolean=false, val message: String="", val revision: Int=0)
class MainViewModel(application: Application): AndroidViewModel(application) {
    private val repository=RouteRepository(application)
    private val mutableState=MutableStateFlow(EditorState())
    val state=mutableState.asStateFlow()
    var routes: List<Route> = emptyList()
        private set
    private var dataLoaded=false
    private val history=ArrayDeque<Route>()
    private var prepareJob: Job?=null
    private val dataMutex=Mutex()
    val draftFields=mutableMapOf<String,String>()
    val draftChecks=mutableMapOf<String,Boolean>()
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data=repository.load()
                routes=data.routes
                dataLoaded=true
                withContext(Dispatchers.Main) {mutableState.value=mutableState.value.copy(config=data.config,message=repository.previousSessionMessage())}
            } catch (error: java.io.IOException) {showMessage("本地文件读取失败：${error.message}")}
            catch (error: org.json.JSONException) {showMessage("本地 JSON 损坏：${error.message}；保留原文件")}
            catch (error: IllegalArgumentException) {showMessage("本地数据无效：${error.message}；保留原文件")}
        }
    }
    fun showMessage(message: String) {mutableState.value=mutableState.value.copy(message=message)}
    fun edit(route: Route) {
        if (SessionState.status.value.active) {showMessage("运行期间路线已冻结，请结束后编辑"); return}
        prepareJob?.cancel()
        history.addLast(mutableState.value.route)
        if (history.size>50) history.removeFirst()
        mutableState.value=mutableState.value.copy(route=route,prepared=null,preparing=false,revision=mutableState.value.revision+1,message="")
    }
    fun undo() {
        if (history.isEmpty() || SessionState.status.value.active) return
        val route=history.removeLast()
        prepareJob?.cancel()
        mutableState.value=mutableState.value.copy(route=route,prepared=null,preparing=false,revision=mutableState.value.revision+1)
    }
    fun invalidatePreview() {
        if (mutableState.value.preparing) cancelPrepare()
        mutableState.value=mutableState.value.copy(prepared=null)
    }
    fun saveRoute(name: String, config: SimulationConfig) {
        if (!dataLoaded) {showMessage("数据尚未成功读取，不能覆盖原文件"); return}
        val route=mutableState.value.route.copy(name=name.trim())
        require(route.name.isNotEmpty() && route.name.length<=80) {"路线名称须为 1～80 字"}
        viewModelScope.launch {
            try {
                dataMutex.withLock {
                    val next=routes.filterNot {it.id==route.id}+route
                    withContext(Dispatchers.IO) {repository.save(SavedData(next,config))}
                    routes=next
                    mutableState.value=mutableState.value.copy(route=route,config=config,message="路线与参数已保存")
                }
            } catch (error: java.io.IOException) {showMessage("保存失败：${error.message}")}
        }
    }
    fun deleteRoute(id: String) {
        if (!dataLoaded) return
        viewModelScope.launch {
            try {dataMutex.withLock {val next=routes.filterNot {it.id==id}; withContext(Dispatchers.IO) {repository.save(SavedData(next,mutableState.value.config))}; routes=next; showMessage("已删除保存的路线")}}
            catch (error: java.io.IOException) {showMessage("删除失败：${error.message}")}
        }
    }
    fun prepare(config: SimulationConfig) {
        if (SessionState.status.value.active || mutableState.value.preparing) return
        val route=mutableState.value.route
        mutableState.value=mutableState.value.copy(config=config,prepared=null,preparing=true,message="正在生成轨迹与时间表，可取消")
        prepareJob=viewModelScope.launch {
            try {
                val seed=java.security.SecureRandom().nextLong()
                val prepared=withContext(Dispatchers.Default) {
                    val job=currentCoroutineContext().job
                    SessionPlanner.prepare(route,config,seed) {!job.isActive}
                }
                if (dataLoaded) dataMutex.withLock {withContext(Dispatchers.IO) {repository.save(SavedData(routes,config))}}
                mutableState.value=mutableState.value.copy(prepared=prepared,preparing=false,message="预览已固定，确认后点击开始模拟")
            } catch (error: CancellationException) {throw error}
            catch (error: IllegalArgumentException) {mutableState.value=mutableState.value.copy(preparing=false,message=error.message.orEmpty())}
            catch (error: java.io.IOException) {mutableState.value=mutableState.value.copy(preparing=false,message="参数保存失败：${error.message}")}
        }
    }
    fun cancelPrepare() {prepareJob?.cancel(); mutableState.value=mutableState.value.copy(preparing=false,message="已取消生成")}
}
