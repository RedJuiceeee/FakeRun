package com.fakerun.app.ui

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.*
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.RequestAuthResultListener
import com.fakerun.app.R
import com.fakerun.app.data.MapSettingsRepository
import com.fakerun.app.geo.SupportedRegion
import com.fakerun.app.location.*
import com.fakerun.app.model.*
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class MainActivity: ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val mapSettings by lazy { MapSettingsRepository(this) }
    private var mapAdapter: MapAdapter?=null
    private val fields=linkedMapOf<String,EditText>()
    private val checks=linkedMapOf<String,CheckBox>()
    private var fillingFields=false
    private var lastConfig: SimulationConfig?=null
    private var lastRevision=-1
    private var lastPlan: PreparedSimulation?=null
    private var lastActive=false
    private var page=0
    private var authListenerRegistered=false
    private val authListener=RequestAuthResultListener {code,_ ->
        if (!requiresMapRestart() && code!=RequestAuthResultListener.STATUS_AUTH_RESULT_NULL) {
            latestMapAuthCode=code
            runOnUiThread {model.showMessage(if (code==0) "百度地图鉴权成功，可开始绘制路线" else "百度地图鉴权失败（错误码 $code），请核对 AK、包名及当前 APK 签名 SHA-1")}
        }
    }
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.showMessage(if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) "精确定位未授权，不能开始模拟" else if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) "通知权限已拒绝，前台服务仍保留系统通知；可在应用内结束" else "权限已就绪，请检查开发者选项")
    }
    private val mapReceiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (requiresMapRestart()) return
            if (intent.action==SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_ERROR) {
                latestMapAuthCode=intent.getIntExtra(SDKInitializer.SDK_BROADTCAST_INTENT_EXTRA_INFO_KEY_ERROR_CODE,-1)
                model.showMessage("百度地图鉴权失败（错误码 $latestMapAuthCode），请核对 AK、包名及当前 APK 签名 SHA-1")
            } else if (intent.action==SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_OK) {
                latestMapAuthCode=0
                model.showMessage("百度地图鉴权成功，可开始绘制路线")
            }
            else if (intent.action==SDKInitializer.SDK_BROADCAST_ACTION_STRING_NETWORK_ERROR) model.showMessage("百度地图网络不可用，请检查网络")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root=findViewById<View>(R.id.root)
        val padding=(12*resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) {view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding+bars.left,padding+bars.top,padding+bars.right,padding+bars.bottom)
            insets
        }
        buildFields()
        bindButtons()
        renderMapSettings()
        val filter=IntentFilter().apply {addAction(SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_ERROR); addAction(SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_OK); addAction(SDKInitializer.SDK_BROADCAST_ACTION_STRING_NETWORK_ERROR)}
        ContextCompat.registerReceiver(this,mapReceiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED)
        page=savedInstanceState?.getInt("page") ?: if (SessionState.status.value.active) 2 else 0
        showPage(page)
        if (getSharedPreferences("privacy",MODE_PRIVATE).getBoolean("agreed",false)) initializeMap()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {model.state.collect {renderEditor(it)}}
                launch {SessionState.status.collect {renderService(it)}}
            }
        }
    }
    private fun button(id: Int, action: ()->Unit) {findViewById<Button>(id).setOnClickListener {action()}}
    private fun bindButtons() {
        button(R.id.tab_ready) {showPage(0)}; button(R.id.tab_route) {showPage(1)}; button(R.id.tab_run) {showPage(2)}
        button(R.id.show_map) {showPage(1)}
        button(R.id.privacy) {
            AlertDialog.Builder(this).setTitle("隐私说明").setMessage("FakeRun 的路线和参数只保存在本应用私有目录，不上传运动成绩。百度地图 SDK 为显示地图和鉴权会联网并按其隐私政策处理设备和网络信息。应用申请定位权限用于系统模拟定位；仅在你主动开始后创建模拟 provider。你可在系统设置清除应用数据撤回同意。\n\n百度地图隐私政策：https://lbsyun.baidu.com/index.php?title=openprivacy\n\n是否同意并启用地图？").setPositiveButton("同意") {_,_ ->
                getSharedPreferences("privacy",MODE_PRIVATE).edit().putBoolean("agreed",true).apply(); initializeMap()
            }.setNegativeButton("拒绝") {_,_ -> model.showMessage("未同意隐私说明，地图未初始化")}.show()
        }
        button(R.id.ak_help) {showMapSettings()}
        button(R.id.permissions) {
            val requested=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT>=33) requested+=Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(requested.toTypedArray())
        }
        button(R.id.location_settings) {openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)}
        button(R.id.developer_settings) {openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)}
        button(R.id.readiness) {inputBoundary {SimulationService.checkReadiness(this); model.showMessage("系统条件已满足；仍需生成完整预览，地图需 AK 鉴权成功")}}
        button(R.id.draw) {mapAdapter?.let {it.drawing=!it.drawing; findViewById<Button>(R.id.draw).text=if (it.drawing) "停止绘制" else "开启绘制"} ?: model.showMessage("请先在准备页启用地图")}
        button(R.id.undo) {model.undo()}
        button(R.id.close_route) {val route=model.state.value.route; if (route.nodes.size<3) model.showMessage("至少添加 3 个节点后闭合") else model.edit(route.copy(closed=!route.closed))}
        button(R.id.new_route) {model.edit(Route(UUID.randomUUID().toString(),"未命名路线",emptyList(),false))}
        button(R.id.save_route) {
            val input=EditText(this).apply {setText(model.state.value.route.name)}
            val dialog=AlertDialog.Builder(this).setTitle("保存路线").setView(input).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
            dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                inputBoundary {model.saveRoute(input.text.toString(),readConfig()); dialog.dismiss()}
            }}; dialog.show()
        }
        button(R.id.load_route) {
            val routes=model.routes
            if (routes.isEmpty()) {model.showMessage("还没有保存路线"); return@button}
            AlertDialog.Builder(this).setTitle("保存的路线").setItems(routes.map {"${it.name} · ${it.nodes.size} 个节点"}.toTypedArray()) {_,index ->
                val route=routes[index]
                AlertDialog.Builder(this).setTitle(route.name).setPositiveButton("打开") {_,_ -> model.edit(route)}.setNeutralButton("删除保存记录") {_,_ -> model.deleteRoute(route.id)}.setNegativeButton("取消",null).show()
            }.show()
        }
        button(R.id.prepare) {inputBoundary {model.prepare(readConfig())}}
        button(R.id.cancel_prepare) {model.cancelPrepare()}
        button(R.id.start) {startSimulation()}
        button(R.id.pause) {sendCommand(SimulationService.PAUSE)}
        button(R.id.resume) {sendCommand(SimulationService.RESUME)}
        button(R.id.end) {sendCommand(SimulationService.END)}
        button(R.id.immediate_end) {sendCommand(SimulationService.IMMEDIATE_END)}
    }
    private fun openSettings(action: String) {
        try {startActivity(Intent(action))} catch (_: ActivityNotFoundException) {model.showMessage("系统没有提供此设置页面，请手动打开系统设置")}
    }
    private fun inputBoundary(action: ()->Unit) {
        try {action()} catch (error: IllegalArgumentException) {model.showMessage(error.message.orEmpty())} catch (error: IllegalStateException) {model.showMessage(error.message.orEmpty())} catch (error: SecurityException) {model.showMessage("系统拒绝操作：${error.message}")}
    }
    private fun requiresMapRestart() = sdkInitialized && initializedApiKey != mapSettings.loadApiKey()

    private fun renderMapSettings() {
        findViewById<TextView>(R.id.ak_status).text=when {
            requiresMapRestart() -> "AK 配置已更新，请关闭并重新打开应用后生效"
            mapSettings.loadApiKey().isBlank() -> "尚未填写百度地图 AK；安装包不含预置密钥"
            else -> "已保存个人 AK，仅保存在本应用私有存储"
        }
    }

    private fun showMapSettings() {
        val identity="包名：$packageName\n签名 SHA-1：\n${SigningIdentity.sha1Fingerprints(this).joinToString("\n") }"
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8)}
        layout.addView(TextView(this).apply {
            text="1. 打开百度地图开放平台控制台，创建应用。\n2. 选择 Android SDK 类型，按下方包名和当前签名 SHA-1 创建 AK。\n3. 将 AK 粘贴到下方并保存，再同意隐私说明启用地图。\n\n地图已启用后更换或清除 AK，需要关闭并重开应用；请先保存未保存的路线。"
        })
        layout.addView(TextView(this).apply {text=identity; setTextIsSelectable(true); setPadding(0,16,0,8)})
        layout.addView(Button(this).apply {
            text="复制包名和 SHA-1"
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FakeRun 签名信息",identity))
                Toast.makeText(this@MainActivity,"包名和 SHA-1 已复制",Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text="打开百度 AK 控制台"
            setOnClickListener {
                try {startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://lbsyun.baidu.com/apiconsole/key")))}
                catch (_: ActivityNotFoundException) {model.showMessage("没有可用浏览器，请手动访问百度地图开放平台控制台")}
            }
        })
        val input=EditText(this).apply {
            id=R.id.ak_input
            hint="粘贴自己的 Android SDK AK"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled=false
            setText(mapSettings.loadApiKey())
        }
        layout.addView(input)
        val scroll=ScrollView(this).apply {addView(layout)}
        val dialog=AlertDialog.Builder(this).setTitle("填写 AK · 教程与签名").setView(scroll)
            .setPositiveButton("保存 AK",null).setNeutralButton("清除 AK",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener {
            fun save(clear: Boolean) {
                if (SessionState.status.value.active) {input.error="请先结束模拟，再修改 AK"; return}
                try {
                    if (clear) mapSettings.clearApiKey() else mapSettings.saveApiKey(input.text.toString())
                    dialog.dismiss()
                    applyMapSettings()
                } catch (error: IllegalArgumentException) {input.error=error.message}
                catch (error: java.io.IOException) {input.error=error.message}
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {save(false)}
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {save(true)}
        }
        dialog.show()
    }

    private fun applyMapSettings() {
        renderMapSettings()
        if (requiresMapRestart()) {
            // SDK 的 AK 与鉴权结果是进程级状态，更换后通过完整退出避免沿用旧鉴权。
            if (authListenerRegistered) {SDKInitializer.removeAuthResultListener(authListener); authListenerRegistered=false}
            mapAdapter?.let {adapter ->
                adapter.pause()
                findViewById<FrameLayout>(R.id.map_host).removeView(adapter.view)
                adapter.destroy()
            }
            mapAdapter=null
            latestMapAuthCode=null
            model.showMessage("AK 配置已更新，请关闭并重开应用后启用地图")
            AlertDialog.Builder(this).setTitle("重开应用使 AK 生效")
                .setMessage("新配置已保存。关闭应用后请手动重开；未保存的路线编辑会丢失，可先取消并保存路线。")
                .setPositiveButton("关闭应用") {_,_ -> finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid())}
                .setNegativeButton("稍后关闭",null).show()
        } else if (getSharedPreferences("privacy",MODE_PRIVATE).getBoolean("agreed",false)) initializeMap()
        else model.showMessage(if (mapSettings.loadApiKey().isBlank()) "AK 已清除，地图未启用" else "AK 已保存，请同意隐私说明启用地图")
    }

    private fun initializeMap() {
        if (requiresMapRestart()) {model.showMessage("AK 配置已更新，请关闭并重开应用后启用地图"); return}
        if (mapAdapter!=null) return
        val ak=mapSettings.loadApiKey()
        if (ak.isBlank()) {model.showMessage("未填写百度地图 AK，请在准备页点击“填写 AK · 教程与签名 SHA-1”"); return}
        try {
            if (!authListenerRegistered) {
                SDKInitializer.addAuthResultListener(authListener)
                authListenerRegistered=true
            }
            if (!sdkInitialized) {
                SDKInitializer.setAgreePrivacy(applicationContext,true)
                SDKInitializer.setApiKey(ak)
                SDKInitializer.initialize(applicationContext)
                SDKInitializer.setCoordType(CoordType.GCJ02)
                sdkInitialized=true
                initializedApiKey=ak
            }
            mapAdapter=MapAdapter(this,
                onAdd={point -> inputBoundary {require(SupportedRegion.contains(point)) {"该坐标位于不支持地区"}; val route=model.state.value.route; require(route.nodes.size<500) {"最多 500 个节点"}; model.edit(route.copy(nodes=route.nodes+point,closed=false))}},
                onEdit={index -> editNode(index)},
                onMove={index,point ->
                    if (!SupportedRegion.contains(point)) {model.showMessage("该节点位于不支持地区"); renderMap()}
                    else {val route=model.state.value.route; model.edit(route.copy(nodes=route.nodes.toMutableList().apply {set(index,point)}))}
                })
            findViewById<FrameLayout>(R.id.map_host).addView(mapAdapter!!.view)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapAdapter!!.resume()
            renderMap()
        } catch (error: RuntimeException) {model.showMessage("百度地图初始化失败：${error.message}")}
        catch (error: UnsatisfiedLinkError) {model.showMessage("百度地图原生库加载失败：${error.message}")}
    }
    private fun editNode(index: Int) {
        val route=model.state.value.route
        val point=route.nodes[index]
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8)}
        val latitude=EditText(this).apply {hint="GCJ-02 纬度"; inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(point.latitude.toString())}
        val longitude=EditText(this).apply {hint="GCJ-02 经度"; inputType=latitude.inputType; setText(point.longitude.toString())}
        layout.addView(latitude); layout.addView(longitude)
        val dialog=AlertDialog.Builder(this).setTitle("编辑节点 ${index+1}（GCJ-02）").setView(layout).setPositiveButton("修改",null).setNeutralButton("删除") {_,_ ->model.edit(route.copy(nodes=route.nodes.filterIndexed {i,_ ->i!=index},closed=false))}.setNegativeButton("取消",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            inputBoundary {
                val next=Gcj02Point(latitude.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("请输入有效纬度"),longitude.text.toString().toDoubleOrNull() ?: throw IllegalArgumentException("请输入有效经度"))
                require(SupportedRegion.contains(next)) {"该节点位于不支持地区"}
                model.edit(route.copy(nodes=route.nodes.toMutableList().apply {set(index,next)})); dialog.dismiss()
            }
        }}; dialog.show()
    }
    private fun buildFields() {
        val container=findViewById<LinearLayout>(R.id.config_fields)
        val specifications=listOf("loopCount" to "循环次数（1～100）","runSpeedKmh" to "跑步基准（km/h，4～20）","stopCount" to "全程中途停止次数（0～20）","stopDurationSeconds" to "停留基准（秒，3～120）","walkSpeedKmh" to "步行基准（km/h，1～7）","walkPercent" to "步行移动时间占比（%，0～80）","maxLateralOffsetMeters" to "最大人物横向偏移（米，0～3）","altitudeMeters" to "模拟椭球高度（米，-500～9000）","satelliteCount" to "模拟 GPS 卫星数（0～32；<4 为弱信号）")
        specifications.forEach {(key,label) ->
            val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL}
            val text=TextView(this).apply {this.text=label; textSize=15f}
            val input=EditText(this).apply {inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine(); contentDescription=label}
            if (key=="altitudeMeters") input.inputType=input.inputType or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            row.addView(text,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,2f))
            row.addView(input,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
            container.addView(row); fields[key]=input
            input.addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int)=Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {if (!fillingFields) {model.draftFields[key]=s.toString(); model.invalidatePreview()}}
                override fun afterTextChanged(s: Editable?)=Unit
            })
        }
        listOf("naturalSway" to "自然横摆（最大 0.35 米）","avoidance" to "连续侧向避让（完整起落与保持）","gpsDrift" to "GPS 漂移（最大半径 2 米）","mockNetwork" to "同时模拟 network provider").forEach {(key,label) ->
            val check=CheckBox(this).apply {text=label; setOnCheckedChangeListener {_,checked ->if (!fillingFields) {model.draftChecks[key]=checked; model.invalidatePreview()}}}
            checks[key]=check; container.addView(check)
        }
    }
    private fun readConfig(): SimulationConfig {
        fun number(key: String): Double = fields.getValue(key).text.toString().toDoubleOrNull()?.takeIf {it.isFinite()} ?: throw IllegalArgumentException("请检查 ${fields.getValue(key).contentDescription}")
        fun integer(key: String): Int {val value=number(key); require(value==kotlin.math.floor(value) && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {"${fields.getValue(key).contentDescription} 须为整数"}; return value.toInt()}
        val percent=integer("walkPercent")
        return SimulationConfig(integer("loopCount"),number("runSpeedKmh"),integer("stopCount"),integer("stopDurationSeconds"),if (percent==0) fields.getValue("walkSpeedKmh").text.toString().toDoubleOrNull()?.takeIf {it.isFinite()} ?: model.state.value.config.walkSpeedKmh else number("walkSpeedKmh"),percent/100.0,checks.getValue("naturalSway").isChecked,checks.getValue("avoidance").isChecked,number("maxLateralOffsetMeters"),checks.getValue("gpsDrift").isChecked,checks.getValue("mockNetwork").isChecked,
            altitudeMeters=number("altitudeMeters"),satelliteCount=integer("satelliteCount")).also {it.validate()}
    }
    private fun fillFields(config: SimulationConfig) {
        fillingFields=true
        val values=mapOf("loopCount" to config.loopCount,"runSpeedKmh" to config.runSpeedKmh,"stopCount" to config.stopCount,"stopDurationSeconds" to config.stopDurationSeconds,"walkSpeedKmh" to config.walkSpeedKmh,"walkPercent" to (config.walkTimeRatio*100).toInt(),"maxLateralOffsetMeters" to config.maxLateralOffsetMeters,"altitudeMeters" to config.altitudeMeters,"satelliteCount" to config.satelliteCount)
        values.forEach {(key,value) ->fields.getValue(key).setText(model.draftFields[key] ?: value.toString())}
        val checkValues=mapOf("naturalSway" to config.naturalSway,"avoidance" to config.avoidance,"gpsDrift" to config.gpsDrift,"mockNetwork" to config.mockNetwork)
        checkValues.forEach {(key,value) ->checks.getValue(key).isChecked=model.draftChecks[key] ?: value}
        fillingFields=false
    }
    private fun renderEditor(state: EditorState) {
        if (lastConfig!=state.config) {lastConfig=state.config; fillFields(state.config)}
        findViewById<TextView>(R.id.message).text=state.message
        findViewById<TextView>(R.id.last_session).apply {text="上次会话：${state.previousSessionMessage}"; visibility=if (state.previousSessionMessage.isBlank()) View.GONE else View.VISIBLE}
        findViewById<Button>(R.id.cancel_prepare).visibility=if (state.preparing) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.prepare).isEnabled=!state.preparing && !SessionState.status.value.active
        findViewById<TextView>(R.id.route_info).text="${state.route.name} · ${state.route.nodes.size} 个节点 · ${if (state.route.closed) "已闭合" else "未闭合"}\n相邻节点直线连接；长按拖动，点击编辑/删除。蓝：中心线，绿：人物，紫：步行，橙：GPS 输出。"
        findViewById<Button>(R.id.close_route).text=if (state.route.closed) "取消闭合" else "闭合路线"
        if (lastRevision!=state.revision || lastPlan!==state.prepared) {
            val newlyPrepared=state.prepared!=null && lastPlan!==state.prepared
            lastRevision=state.revision; lastPlan=state.prepared
            renderMap()
            if (newlyPrepared) showPage(2)
        }
        val preview=state.prepared ?: if (SessionState.status.value.active) SessionState.currentPlan else null
        renderPreview(preview)
        findViewById<Button>(R.id.start).isEnabled=state.prepared!=null && !SessionState.status.value.active
    }
    private fun renderPreview(plan: PreparedSimulation?) {
        if (plan==null) {findViewById<TextView>(R.id.preview).text="请先绘制闭合路线并生成预览"; return}
        val text=buildString {
            append("本次完整计划（预览不注入位置）\n")
            append("中心线每圈 ${format(plan.path.loopLengthMeters)} 米 × ${plan.config.loopCount} 圈\n人物总路程 ${format(plan.path.distanceMeters)} 米\n预计移动 ${format(plan.movingSeconds)} 秒，总时间 ${format(plan.totalSeconds)} 秒\n步行巡航占比 ${format(plan.walkingSeconds/plan.movingSeconds*100)}%\n")
            append("起点 WGS84：${formatPoint(plan.path.at(0.0).humanPoint)}\n")
            plan.stops.forEach {append("停止 ${it.index}：人物弧长 ${format(it.humanDistanceMeters)} 米，实际驻留 ${it.durationSeconds} 秒\nWGS84 ${formatPoint(plan.path.at(it.humanDistanceMeters).humanPoint)}\n")}
            plan.walkingRegion?.let {append("集中步行：人物弧长 ${format(it.startMeters)}～${format(it.endMeters)} 米（走跑过渡不计分子）\n")}
            append("人物横向上限 ${format(plan.config.maxLateralOffsetMeters)} 米，GPS 半径 ${if (plan.config.gpsDrift) 2 else 0} 米\n")
            append("模拟 WGS84 椭球高度 ${format(plan.config.altitudeMeters)} 米，连续观测误差 ±1.5 米（平坦路线模型）\n")
            append("模拟 GPS 卫星 ${plan.config.satelliteCount} 颗，${if (plan.config.satelliteCount<4) "弱信号：水平精度 20～40 米" else "正常信号：水平精度 4～8 米"}；模拟值不代表真实卫星接收\n")
            append("避让 ${plan.path.avoidanceEvents.size} 次，最大峰值 ${format(plan.path.avoidanceEvents.maxOfOrNull {kotlin.math.abs(it.peakMeters)} ?: 0.0)} 米\n")
            if (plan.config.avoidance && plan.path.avoidanceEvents.isEmpty()) append("当前路线/偏移限制没有可用的完整避让窗口\n")
            append("固定 seed：${plan.seed}；开始后路线与参数冻结。")
        }
        findViewById<TextView>(R.id.preview).text=text
    }
    private fun renderService(status: ServiceStatus) {
        if (lastActive!=status.active) {lastActive=status.active; renderMap(); renderEditor(model.state.value)}
        if (status.message.isNotBlank()) findViewById<TextView>(R.id.message).text=status.message
        status.snapshot?.let {s ->
            val stateText=when (s.state) {RunState.RUNNING->"运行中"; RunState.PAUSING->"平滑暂停中"; RunState.PAUSED->"已暂停"; RunState.RESUMING->"平滑恢复中"; RunState.STOPPING->"平滑结束中"; RunState.COMPLETED->"已完成"; RunState.INTERRUPTED->"已中断"; RunState.ERROR->"错误"; else->"未运行"}
            val modeText=when (s.mode) {MotionMode.RUN->"跑步巡航"; MotionMode.WALK->"步行巡航"; MotionMode.TRANSITION->"速度过渡"; MotionMode.DWELL->"自动驻留"}
            findViewById<TextView>(R.id.stats).text="$stateText · $modeText\n当前速度 ${format(s.speedMetersPerSecond*3.6)} km/h\n完成 ${s.completedLoops} 圈，自动停留 ${s.completedStops} 次\n中心线 ${format(s.centerDistanceMeters)} 米\n人物实际行进 ${format(s.humanDistanceMeters)} 米\n移动 ${format(s.movingSeconds)} 秒，步行 ${format(s.walkingSeconds)} 秒\n实际步行占比 ${format(if (s.movingSeconds>0) s.walkingSeconds/s.movingSeconds*100 else 0.0)}%\nGPS WGS84：${formatPoint(s.reportedPoint)}\n水平精度 ${format(s.accuracyMeters)} 米\n模拟椭球高度 ${format(s.altitudeMeters)} 米（垂直精度 ${format(s.verticalAccuracyMeters)} 米）\n模拟 GPS 卫星 ${s.satelliteCount} 颗 · ${if (s.satelliteCount<4) "弱信号档位" else "正常信号档位"}\n${if (s.userIntervened) "用户干预，原预计占比可能变化；额外减速行进 ${format(s.interventionDistanceMeters)} 米" else ""}"
            mapAdapter?.update(s)
        }
        val state=status.snapshot?.state
        findViewById<Button>(R.id.pause).isEnabled=status.active && state==RunState.RUNNING
        findViewById<Button>(R.id.resume).isEnabled=status.active && state==RunState.PAUSED
        findViewById<Button>(R.id.end).isEnabled=status.active && state in listOf(RunState.RUNNING,RunState.PAUSED)
        findViewById<Button>(R.id.immediate_end).isEnabled=status.active
        findViewById<Button>(R.id.start).isEnabled=!status.active && model.state.value.prepared!=null
        listOf(R.id.draw,R.id.undo,R.id.close_route,R.id.new_route,R.id.load_route,R.id.save_route,R.id.prepare).forEach {findViewById<Button>(it).isEnabled=!status.active && !model.state.value.preparing}
    }
    private fun renderMap() {
        val active=SessionState.status.value.active
        val plan=if (active) SessionState.currentPlan else model.state.value.prepared
        mapAdapter?.show(if (active && plan!=null) plan.route else model.state.value.route,plan,active)
        SessionState.status.value.snapshot?.takeIf {active}?.let {mapAdapter?.update(it)}
    }
    private fun startSimulation() {
        inputBoundary {
            check(getSharedPreferences("privacy",MODE_PRIVATE).getBoolean("agreed",false)) {"请先同意隐私说明"}
            val plan=model.state.value.prepared ?: throw IllegalStateException("请先生成完整预览")
            SimulationService.checkReadiness(this)
            check(!SessionState.status.value.active) {"已有运行会话"}
            SessionState.pendingPlan.set(plan)
            try {startForegroundService(Intent(this,SimulationService::class.java).setAction(SimulationService.START))}
            catch (error: RuntimeException) {SessionState.pendingPlan.set(null); model.showMessage("系统拒绝启动模拟服务：${error.message}")}
        }
    }
    private fun sendCommand(action: String) {
        try {startService(Intent(this,SimulationService::class.java).setAction(action))} catch (error: RuntimeException) {model.showMessage("系统拒绝服务操作：${error.message}")}
    }
    private fun showPage(index: Int) {
        page=index
        listOf(R.id.page_ready,R.id.page_route,R.id.page_run).forEachIndexed {i,id ->findViewById<View>(id).visibility=if (i==index) View.VISIBLE else View.GONE}
        listOf(R.id.tab_ready,R.id.tab_route,R.id.tab_run).forEachIndexed {i,id ->findViewById<Button>(id).isSelected=i==index}
    }
    private fun format(value: Double)=String.format(Locale.CHINA,"%.1f",value)
    private fun formatPoint(point: Wgs84Point)=String.format(Locale.US,"%.7f, %.7f",point.latitude,point.longitude)
    override fun onResume() {super.onResume(); mapAdapter?.resume()}
    override fun onPause() {mapAdapter?.pause(); super.onPause()}
    override fun onSaveInstanceState(outState: Bundle) {outState.putInt("page",page); super.onSaveInstanceState(outState)}
    override fun onDestroy() {unregisterReceiver(mapReceiver); if (authListenerRegistered) SDKInitializer.removeAuthResultListener(authListener); mapAdapter?.destroy(); super.onDestroy()}
    companion object {
        private var sdkInitialized=false
        private var initializedApiKey: String?=null
        @Volatile var latestMapAuthCode: Int?=null
            private set
    }
}
