# 步道乐跑定位差异排查

日期：2026-10-10。状态：静态排查与真机准备页对照完成，目标仍未采用模拟位置，具体 SDK 分支未确认，尚未修复。生产代码和目标 APK 未修改；新增设备对照测试。

## 已确认的证据

- 用户反馈：开启 FakeRun 后，高德地图可以获取模拟位置，步道乐跑显示真实位置。该反馈尚未在本机复现。
- 输入为 `example/budaolepao.apk`。`aapt dump badging` 确认 applicationId 为 `com.lptiyu.tanke`，versionName 为 `4.1.7`，versionCode 为 `217`，minSdk 为 24，targetSdk 为 33。
- 输入 APK SHA-256 为 `1E1434F57802E48CE1A38942D7DBF1EC57B5292F0C4DB5108D377A24EEA375CA`。后续须确认实际安装版本与输入包一致。
- Manifest 声明 `com.amap.api.location.APSService`；安装包包含高德地图原生库。这证明包含高德定位组件，不能证明所有页面采用相同定位流程或具体 SDK 选项。
- 可解析的主 DEX 只有 5 个 `com.secneo.apkwrapper` 壳类；原生库包含 `libDexHelper.so`。DEX 尾部能找到 `AMapLocationClient`、`setMockEnable`、`isFromMockProvider` 字符串，但不能恢复其业务调用关系、实参或分支。仅有方法名不能推断调用了 `setMockEnable(false)`。
- APK 原生库仅有 `arm64-v8a` 和 `armeabi-v7a`。现有验收环境是 x86_64 Android 模拟器，没有证据证明能运行此 ARM 加固包；不能用之前 FakeRun 的验收代替目标应用验收。
- 第一轮静态分析时没有连接设备；随后用户连接 Honor Magic 8 Pro，真机结果见下节。
- FakeRun 当前使用 `LocationManager.addTestProvider` / `setTestProviderLocation`，创建 `gps` 和可选 `network`；`mockNetwork` 默认开启。两个 provider 使用同一份 WGS84 快照和时间戳，系统保留 mock 标记。没有注入 `fused`，没有改写其他应用的定位 SDK。

静态工具使用已有 Android Build Tools 的 `aapt` 与 `dexdump`，只读检查 APK。解压文件和原始输出在忽略的分析缓存中，未加入 Git；未复制 APK 的 AK 或其他配置到本记录。

## 候选原因，尚待区分

1. 目标页面采用 SDK 的 Wi-Fi / 基站网络定位，结果不经过系统 `network` test provider。
2. 目标应用或 SDK 拒绝带 mock 标记的 GPS，随后采用网络结果或历史缓存。
3. 页面使用历史位置，或读取尚未覆盖的系统融合接口。
4. 手机实际运行配置、权限或厂商定位实现与已验收环境不同。

上述均为候选原因，不是已确认的根因。高德地图采用模拟位置，不能证明另一个包含高德 SDK 的应用也会采用它。

[高德定位接入文档](https://developer.amap.com/api/android-location-sdk/guide/android-location/getlocation)说明每个应用有自己的定位服务，Wi-Fi 信息会用于网络定位。[高德选项参考](https://a.amap.com/lbs/static/unzip/Android_Location_Doc/com/amap/api/location/AMapLocationClientOption.html)说明 `setMockEnable(false)` 可禁止模拟 GPS；从 3.4.0 起默认允许，因此不能归咎于“高德 SDK 默认禁止”。

[Android LocationManager 文档](https://developer.android.com/reference/android/location/LocationManager#setTestProviderLocation(java.lang.String,android.location.Location))说明 test provider 输出会被标为模拟位置。开发者选项授权不能要求第三方 SDK 接受该位置。系统 `fused` 与 SDK 自己的网络定位不是同一个接口；即使覆盖前者，也不能据此保证覆盖后者。

## 最小真机复现步骤

以下为复现流程。本轮已连接问题手机执行准备页对照。先记录型号、Android 版本、实际步道乐跑版本、显示真实位置的页面和 FakeRun 的 network 开关；不需要清除数据、卸载应用、提供账户密码或提交运动成绩。

1. 使用对照测试的合成闭合路线，选择与实际位置明显不同的起点，开始后暂停到零速；测试配置关闭 GPS 漂移以便逐样本比较，不改用户保存的参数。记录起点坐标系为 WGS84，地图坐标为 GCJ-02，比较前需统一坐标系。
2. 只读采集 `adb shell dumpsys location` 与 `adb shell appops get com.fakerun.app android:mock_location`。核对 `gps` / 已启用的 `network` 是否为 FakeRun 的 mock provider，以及输出是否新鲜；同时检查目标包的系统定位请求。快照中没有请求不能单独证明没有定位，可能使用缓存、短请求或 SDK 内部定位。
3. 保持本次模拟，打开用户反馈的目标页面，等待一次刷新，分别观察地图和跑步准备页。没有明确授权时不启动或上传实际运动记录。记录显示位置是否不变、是否更新、是否报模拟定位错误；复核 APK 版本一致。
4. 若系统请求明确指向 `fused`，先在该设备增加独立融合读取器对照 `gps` / `network`，证明接口遗漏后再实现系统融合注入及清理测试，不能只增加 provider 名称就宣称修复。
5. 若系统 `gps` / `network` 已持续报告正确 mock 位置，而目标仍显示真实位置，则进一步确认 SDK 的定位类型、错误码或可获得的诊断信息。没有这些证据时，不能在“拒绝 mock”和“独立网络定位”之间下结论。
6. 若修复生产代码，运行必要算法测试、APK 构建、相关设备控制与清理测试，最后在同一手机、同一目标页面复测完整场景。目标采用模拟位置才算兼容问题修复；独立系统读取器通过仅证明系统注入。
7. 结束 FakeRun，核对本次创建的 provider、服务、通知及唤醒锁已清理。

设备原始日志可能包含实际位置、个人信息和其他应用数据，仅保存在被忽略的 `tests/manual/.local/`，只将去除个人信息的结论写入本文件。不要清空整个 logcat，也不要把未取得的日志或未运行的测试标为通过。

## Honor 真机对照

设备为用户提供的 Honor Magic 8 Pro，系统报告型号 `BKQ-AN10`、Android 17、API 37。手机上的 FakeRun 为 1.0.0 / code 1，步道乐跑为 4.1.7 / code 217，与待分析版本一致。两个应用均有精确定位权限，FakeRun 的 `MOCK_LOCATION` 为 allow，系统定位开启。

### 工具与构建

新增 `tests/device/com/fakerun/app/ProviderCompatibilityTest.kt`。显式启用 `checkTarget=true` 后，它用公开合成路线启动真实 `SimulationService`，平滑暂停、关闭 GPS 漂移，保持固定输出；独立 gps/network/passive listener 只核对暂停完成后的新样本。保持窗口内持续检查服务 active，结束后立即清理并恢复此前会话提示，不保存合成路线到用户路线列表。

构建命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`。最终构建成功，33 秒。生产代码和算法源未改，普通 APK 与算法任务为 UP-TO-DATE；沿用该源码版本的 8 项算法通过结果，没有声称本轮重新执行了 8 项算法。设备测试 APK 已重新编译。

测试包安装起初因锁屏返回 `INSTALL_FAILED_ABORTED: User rejected permissions`；用户解锁并明确允许测试包安装后成功。仅安装/更新测试 APK，没有覆盖手机上的普通 FakeRun APK。

第一版工具在测试线程直接注入，退到后台后回调变慢，90 秒频率断言失败；该版本不能用来验证生产前台服务频率。为排除工具自身限制，改用生产服务，并允许系统合并读取器回调。实际输出仍由生产服务以 5 Hz 驱动，不降低生产频率。仅因此修改工具并重建、重跑受影响真机对照。

### 最终命令与结果

```powershell
adb shell am instrument -w -e class com.fakerun.app.ProviderCompatibilityTest#stationaryMockWindowForExternalReader -e checkTarget true -e holdSeconds 120 com.fakerun.app.test/androidx.test.runner.AndroidJUnitRunner
```

结果 `OK (1 test)`，134.46 秒，包含起步和平滑暂停；固定位置窗口为 120 秒。

| 读取接口 | 新位置数量 | 与暂停快照或 mock 标记不匹配的数量 |
| --- | --- | --- |
| gps | 563 | 0 |
| network | 563 | 0 |
| passive | 1126 | 0 |

服务全程 active，系统诊断显示 `isForeground=true`、location 类型 `0x8`；gps/network 的 mock provider identity 都是 FakeRun。读取器运行在被测 FakeRun 进程，独立监听系统回调，但不能直接检查目标应用回调的入参。

保持模拟期间，步道乐跑准备页没有采用合成位置，显示“GPS信号弱”；重新启动目标进程并重新进入准备页，等待刷新及点击回到当前位置后，仍显示原位置附近的距离。目标的计时、路程和打卡数全程为零，没有按下准备页底部启动计时按钮，没有提交运动记录。

系统显示目标进程持续登记 gps/network/passive 请求，没有观察到目标 fused 请求。两次相隔约 58 秒的系统快照中，目标的历史位置计数增加：gps +69、network +68、passive +549。此为系统诊断计数，不等同于拿到了目标 SDK 的最终回调坐标。目标进程日志没有可用的 mock 开关值、定位类型或错误码，无法据此指认 `setMockEnable(false)`。

目标重新启动后显示距离发生了米级变化，故单纯旧进程内存缓存不足以解释全部现象；SDK 持久缓存、独立网络定位和 mock 过滤仍未逐一确认。设置页没有观察到允许模拟定位或切换定位来源的入口。没有修改目标设置、权限、数据或 APK，没有增加 fused 或伪造卫星信息。

### 退出与恢复

结束后 gps identity 恢复为系统 `android[GnssService]`，network 恢复为 Honor 系统 `com.hihonor.lbs`，没有 FakeRun mock override、SimulationService 或活跃 `FakeRun:session` 唤醒锁；唤醒锁日志记录了本次 REL。测试 APK 已卸载，设备上的临时 UI dump 已删除，USB 供电亮屏设置恢复为原值 0。设备输出、UI 内容和构建日志只保存在忽略目录，未提交个人位置、学校信息、成绩、日志或本机路径。

## 当前结论、边界与下一步

已验证：同一真机上当前 gps/network 注入及新样本读取正确，真实前台服务没有中断，目标准备页仍未采用该模拟位置。未发现用增加 fused、修改坐标转换或修正后台循环即可解决的证据。

未验证：目标 SDK 的 mock 选项和具体过滤分支、最终定位类型及持久缓存策略、设备对特定目标的额外定位处理。未启动实际跑步或上传成绩，未声称运动成绩兼容通过，未交付修复 APK。

后续需要目标应用提供的可调试测试版本、源码中的定位选项或官方定位诊断，才能检查其最终回调的定位类型、错误码、mock 标记与坐标，并区分客户端选择和系统针对目标的处理。当前公开 test provider 授权无法要求另一个应用采用结果。

`plan.md` 明确排除 Root、Hook、隐藏 mock 标记、第三方检测绕过和传感器伪造；如果后续确认目标主动拒绝 mock，应明确说明现有方案的兼容边界，不能把绕过实现悄悄加入项目。本次修复任务仍未完成。
