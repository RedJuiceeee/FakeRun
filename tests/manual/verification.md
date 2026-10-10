# 本轮实现与验收记录

日期：2026-10-10。应用版本：1.0.0（versionCode 1）。本文件仅保留简洁结论，不保存 AK、私人路线、签名私钥或设备原始日志。

## 环境与范围

- JDK 21；Gradle 8.12、AGP 8.10.1、Kotlin 2.1.20；compile/target 36、min 26。
- 已安装和运行的设备为 Android Emulator，型号 `sdk_gphone16k_x86_64`，Android 17 / API 37，x86_64、16 KB 页；AVD 为 Pixel 10 Pro。不是实体手机验收。
- 百度地图 8.2.0；用户在未提交的 `local.properties` 配置 AK，控制台绑定包名和当前 APK 签名。APK 为 debug 签名，SHA-1：`24:7D:FB:FE:FD:58:DD:5D:3F:5F:CF:7C:DC:11:8E:AA:A0:FA:A8:6F`。
- 模拟器使用测试授权允许精确定位、通知和 `android:mock_location`；系统定位开启。真实手机仍须在开发者选项由用户选择 FakeRun。
- 验收只承诺 Android `LocationManager` 的 gps/network 读取接口；不推断第三方融合定位采用情况或实体手机卫星恢复结果。

## 构建、算法和文件纪律

命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain`。

8 个算法测试通过，0 失败、0 错误。覆盖原作者 JavaScript 固定坐标样例、曲线/闭合与弧长、人物偏移及完整避让、非恒速巡航和速度范围边界、100 ms 加速度/变化率采样、精确距离积分、3/10/120 秒驻留范围、步行比例、固定随机流、GPS 分层、暂停恢复、不可行剩余排程和仅清理自有 provider。

普通 APK 和设备测试 APK 构建通过。最终中断提示版本 Lint 为 0 错误、62 个警告；警告主要包括界面中文硬编码、会话级唤醒锁、测试中的旧 provider 查询及 SDK 相关提示，未用 baseline 隐藏错误。百度 `common:1.0.43` 编译出现 D8 栈映射警告；本机 SDK 原生库加载成功。

最终普通 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，80,575,212 字节；SHA-256：`6BE297DEFC18ED9D9851A3FE49F1681A082EA4BCCF5E279701426583090A6B12`。`apksigner verify --print-certs` 复核成功，签名指纹与 AK 绑定一致。严格完整场景使用的普通 APK SHA-256 为 `30A00991A356CE1F96648DBCACB08F7E3BCC0B38BE576385AA9D46390BB55AD1`；最终包仅增加准备页独立的上次会话记录，模拟引擎、定位服务与地图逻辑相同。新界面按受影响项目单独复核。

`scripts/check-text.ps1` 确认纳入版本管理的文本为 UTF-8 无 BOM、LF；二进制 Wrapper JAR 不做换行转换。检查 Gradle 源集和生产代码依赖：`tests/` 仅用于 test/androidTest 的源码和资源，普通 APK 不依赖它；未删除用户测试来证明。AK、构建输出、原始日志、签名文件及采集截图被忽略。

## 设备验证

命令统一使用 `adb shell am instrument -w -e class ... com.fakerun.app.test/androidx.test.runner.AndroidJUnitRunner`；本机连接曾使用测试专用 ADB 端口，该端口不属于产品要求。

- 静态 WGS84 注入：独立 gps listener 收到指定坐标和单调时间戳，speed/accuracy/mock 字段符合要求，无未建模高度；清理后底层 gps provider 存在。已通过。
- 短场景：真实服务暂停、GPS 漂移继续、恢复不倒退、立即结束、运行中撤销模拟权限停止。已通过，相应源码与最终包一致。
- JSON 保存/重读与 Activity 重建：最终普通 APK 已通过，重建后保留 `12.3 km/h` 输入草稿，截图文字与控件可见。重建截图在地图首帧完成前采集，不用它证明地图渲染。
- 百度 SDK：初始化前同意隐私，GCJ-02 模式下实际点击绘制四点闭合路线；首点取独立 WGS84 样例，点击与逆转换偏差小于 3 米。已通过。单独地图复核使用 1208×1179 地图视窗、缩放 17，所有节点投影落在视窗内；人工查看采集截图，四个编号 marker 与蓝色闭合 polyline 在点击处一致显示。
- 地图/服务短场景命令增加 `-e checkMap true`，runner 报告 `OK (7 tests)`，其中实际执行地图、静态注入、JSON/重建、服务控制 4 项，另 3 项按参数条件跳过。拒绝权限及错误绑定随后各单独执行一次。此包尚未增加准备页独立会话提示。
- 最终包安装后，单独执行 `interruptedSessionReminderRemainsVisible` 与 `jsonPersistenceAndActivityRecreation`，`OK (2 tests)`，4.66 秒。持久运行标记显示上次中断，鉴权消息不覆盖独立记录，没有自动启动会话；输入草稿与 JSON 仍符合要求。该测试使用持久标记模拟上次异常退出，不声称 SIGKILL 会执行服务销毁回调。
- 拒绝定位权限：外部撤销应用 fine 权限后，以 `-e denyFine true -e class ...#readinessRejectsMissingFineLocation` 执行；明确报“精确定位”条件不满足，未创建会话，`OK (1 test)`。随后恢复测试授权。
- 错误绑定：本地忽略的临时构建仅将 applicationId 改为 `com.fakerun.app.invalid`，生产源码、AK 与签名均相同，测试源集仍指向原根目录 `tests/`。以该隔离 APK 运行 `-e checkBadMap true -e class ...#mapAuthFailureIsReported`，实际收到 230，界面显示 AK/包名/签名核对原因，`OK (1 test)`。临时 APK 已从设备卸载，正常 APK 和用户配置未改动。

## 完整真实时钟场景

命令额外参数：`-e runFull true -e class com.fakerun.app.DeviceAcceptanceTest#fullTwoLoopBackgroundSessionWithIndependentReader`。默认通过真实百度地图点击绘制，再用界面生成完整预览并点击开始；没有加速引擎时钟，也没有在完整场景中人工暂停。

本次配置为 2 圈、跑步 10 km/h、全程 2 次停止、停留基准 10 秒、步行 5 km/h、目标 20%，默认人物横摆、避让和 GPS 漂移。预览 seed `-1277037718551488717`，人物总距离 `2056.9284665506716` 米，移动 `841.5703851831175` 秒，两次驻留 `6、14` 秒，预计步行比例 `19.00000000006328%`，与目标差不超过 1 个百分点。

首次完整运行 `OK (1 test)`，870.589 秒：最终 2 圈、2 次驻留，移动 `841.5703851831175` 秒，gps/network 各 4308 条，同一单调时间戳的坐标、速度、accuracy 和墙上时间一致；位置、mock、字段及速度范围检查通过。后台与息屏期间设备电源状态为 Asleep，服务为 location 类型前台服务，持有 `FakeRun:session` 局部唤醒锁。该测试最初驻留观测阈值为 0.25 秒，不能用它单独声称严格满足 0.2 秒。

最终测试已将驻留观测误差阈值改为 plan.md 的 0.2 秒，严格完整场景 `OK (1 test)`，862.026 秒。本次预览 seed `-6711073986997598959`，人物总距离 `2038.5514200252787` 米，预计移动 `824.1998736808516` 秒，驻留 `12、14` 秒，步行比例 `19.000000000043716%`。

结果：恰好完成 2 圈、2 次自动驻留，人物最终距离与预览一致；移动 `824.1998736808517` 秒，步行实际占比满足目标 ±1 个百分点；gps/network 各 4251 条，输出频率、递增单调时间戳、同刻两种 provider 字段一致、人物速度与输出速度、巡航范围和 WGS84 报告位置检查通过。

停止 1 系统零速观测 `12.0084574` 秒，误差 `0.0084574` 秒；停止 2 为 `13.998937` 秒，误差 `0.001063` 秒；均严格小于 0.2 秒。后台/息屏期间正常完成；无人工干预，不改变原预计占比。

结束后系统诊断确认 gps identity 恢复为 `android[GnssService]`，network 恢复为 Google 系统位置服务，均没有本应用 mock provider；network 原本受设备设置限制，恢复为其原有 disabled 状态。无 `FakeRun:session` 活跃唤醒锁、无 SimulationService 前台实例、无本应用活动运行通知。不把 provider 恢复身份等同于已接收卫星定位。

## 修复与重跑理由

首次地图请求返回 230（APP Mcode 校验失败）；用户更新 AK 控制台包名与签名绑定。SDK 使用过旧鉴权缓存，验收模拟器清除本应用的 SDK 鉴权缓存后得到成功结果。界面增加初始化前注册的 SDK 鉴权监听，并在销毁时移除，避免广播未送达时漏报鉴权结果。

首次固定点点击最后一个节点偏差约 4.39 米，是 SDK 的 POI 点击返回地点中心；任意点坐标验收关闭 POI 显示后通过，产品保留点击 POI 添加地点中心的正常行为。算法、构建或相关设备检查只因这些修复和界面重建修复重跑，不因覆盖率重复运行。

第一次地图截图的自动 bounds 取景仍停留在局部缩放，未展示完整覆盖物；短场景改用明确中心/缩放并检查节点屏幕投影后，完整路线截图通过视觉复核。仅调整测试取景，没有修改产品坐标或覆盖物实现。最终完整场景重跑的原因是将驻留验收阈值从 0.25 秒收紧到要求的 0.2 秒。

复核中断记录时发现 SDK 鉴权消息可能覆盖通用提示；准备页增加独立“上次会话”记录，保留持久运行标记对应的原因。仅影响提示显示，必要算法测试、构建及最终包上的设备恢复提示检查均已通过。完整场景的引擎、服务、地图源码没有变化，因此只补验受影响界面，没有再重复长场景。

## 未验证范围

实体手机、API 26～36 的各系统分支、厂商后台限制、第三方融合位置读取、联网额度/账户权益，以及真实卫星条件下清理后的首次定位尚未实测。后续设备验收按 `acceptance.md`，不能把 provider 恢复存在写成已经获得卫星定位。
