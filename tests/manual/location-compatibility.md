# 步道乐跑定位差异排查

日期：2026-10-10。状态：静态排查完成，真机原因未确认，尚未修复。没有修改 FakeRun 生产代码、目标 APK 或安装包。

## 已确认的证据

- 用户反馈：开启 FakeRun 后，高德地图可以获取模拟位置，步道乐跑显示真实位置。该反馈尚未在本机复现。
- 输入为 `example/budaolepao.apk`。`aapt dump badging` 确认 applicationId 为 `com.lptiyu.tanke`，versionName 为 `4.1.7`，versionCode 为 `217`，minSdk 为 24，targetSdk 为 33。
- 输入 APK SHA-256 为 `1E1434F57802E48CE1A38942D7DBF1EC57B5292F0C4DB5108D377A24EEA375CA`。后续须确认实际安装版本与输入包一致。
- Manifest 声明 `com.amap.api.location.APSService`；安装包包含高德地图原生库。这证明包含高德定位组件，不能证明所有页面采用相同定位流程或具体 SDK 选项。
- 可解析的主 DEX 只有 5 个 `com.secneo.apkwrapper` 壳类；原生库包含 `libDexHelper.so`。DEX 尾部能找到 `AMapLocationClient`、`setMockEnable`、`isFromMockProvider` 字符串，但不能恢复其业务调用关系、实参或分支。仅有方法名不能推断调用了 `setMockEnable(false)`。
- APK 原生库仅有 `arm64-v8a` 和 `armeabi-v7a`。现有验收环境是 x86_64 Android 模拟器，没有证据证明能运行此 ARM 加固包；不能用之前 FakeRun 的验收代替目标应用验收。
- 本轮 `adb devices -l` 没有连接设备，无法获取问题手机的实时 provider、系统请求、权限、输出或页面行为。
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

需要用户正在使用的手机连接 USB 调试并完成电脑授权。先记录型号、Android 版本、实际步道乐跑版本、显示真实位置的页面和 FakeRun 的 network 开关；不需要清除数据、卸载应用、提供账户密码或提交运动成绩。

1. 在 FakeRun 准备好合成闭合路线，选择与实际位置明显不同的起点，开始后暂停到零速；保留 GPS 漂移。记录起点坐标系为 WGS84，地图坐标为 GCJ-02，比较前需统一坐标系。
2. 只读采集 `adb shell dumpsys location` 与 `adb shell appops get com.fakerun.app android:mock_location`。核对 `gps` / 已启用的 `network` 是否为 FakeRun 的 mock provider，以及输出是否新鲜；同时检查目标包的系统定位请求。快照中没有请求不能单独证明没有定位，可能使用缓存、短请求或 SDK 内部定位。
3. 保持本次模拟，打开用户反馈的目标页面，等待一次刷新，分别观察地图和跑步准备页。没有明确授权时不启动或上传实际运动记录。记录显示位置是否不变、是否更新、是否报模拟定位错误；复核 APK 版本一致。
4. 若系统请求明确指向 `fused`，先在该设备增加独立融合读取器对照 `gps` / `network`，证明接口遗漏后再实现系统融合注入及清理测试，不能只增加 provider 名称就宣称修复。
5. 若系统 `gps` / `network` 已持续报告正确 mock 位置，而目标仍显示真实位置，则进一步确认 SDK 的定位类型、错误码或可获得的诊断信息。没有这些证据时，不能在“拒绝 mock”和“独立网络定位”之间下结论。
6. 若修复生产代码，运行必要算法测试、APK 构建、相关设备控制与清理测试，最后在同一手机、同一目标页面复测完整场景。目标采用模拟位置才算兼容问题修复；独立系统读取器通过仅证明系统注入。
7. 结束 FakeRun，核对本次创建的 provider、服务、通知及唤醒锁已清理。

设备原始日志可能包含实际位置、个人信息和其他应用数据，仅保存在被忽略的 `tests/manual/.local/`，只将去除个人信息的结论写入本文件。不要清空整个 logcat，也不要把未取得的日志或未运行的测试标为通过。

## 验证状态与下一步

已验证：APK 身份、摘要、Manifest、原生 ABI、可见 DEX 壳、FakeRun 注入实现、当前无连接设备，以及本轮文档编码和差异检查。

未验证：具体手机实时输出、目标页面读取接口、SDK mock 选项、拒绝分支、定位类型、缓存行为、融合接口兼容和修复后的表现。没有生产代码变化，本轮不运行应用构建或算法测试，不沿用旧设备结果宣称本次兼容通过。

下一执行者先取得问题手机的连接和复现页面，再执行以上最小对照。`plan.md` 明确排除 Root、Hook、隐藏 mock 标记、第三方检测绕过和传感器伪造；如果证据表明目标主动拒绝系统模拟位置，需要说明这个具体技术边界，不能把绕过实现悄悄加入项目。
