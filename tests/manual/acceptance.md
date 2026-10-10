# 设备验收步骤

设备型号、Android/API 版本、APK SHA-256、签名与 AK 绑定、命令及结论记录到 `verification.md`；原始位置日志不提交。

## 最小接入与地图

1. 构建并安装 debug APK，登记该机器 `:app:signingReport` 的 SHA-1 和 `com.fakerun.app`。用户自行配置真实 `BAIDU_MAP_AK`，不把密钥写入记录。
2. 未同意隐私时确认没有 MapView；拒绝后地图不初始化。同意且 AK 正确后地图鉴权成功；错误 AK/签名时应明确报错。
3. 申请定位权限；在开发者选项手工选择 FakeRun。拒绝精确定位或关闭系统定位时不能开始。通知拒绝仍保留前台服务系统要求的通知。
4. 点击大陆固定点，记录 GCJ-02 点击数值，添加 marker 与 polyline 检查覆盖物一致性；与可核对的独立 WGS84 点比较。不能只看自有正逆互测。
5. 静态 test provider 用下面测试读取 WGS84，核对 `time`、`elapsedRealtimeNanos`、accuracy、speed、bearing 和 mock 标记；清理后真实 provider 恢复可用。真实 GPS 恢复接收还需设备卫星条件，不能把 provider 存在等同于已定位。

## 完整运行

绘制约 1 km、至少 200 米可用直线的闭合路线。配置 2 圈、10 km/h、2 次停止、停留基准 10 秒、5 km/h、20% 步行，其他默认开启。先检查完整预览可行，记录 seed、各驻留实际时长、预计步行比例与地图步行范围；不在本场景手动暂停。

独立读取器监听 gps/network，验证两圈、两次驻留（每次 5～15 秒且与预览相符）、跑步 8～12 km/h 和步行 4～6 km/h 非恒定平滑变化、起终点零速、圈接缝连续、集中步行且实际比例差不超过 1 个百分点。至少一个 >1 米避让有起落/保持/回归。停留时人物固定、报告位置仍在 2 米圆内缓慢漂移。后台与息屏不中断，结束后通知、唤醒锁和 test provider 释放。

人工暂停/继续另用短场景：等待锚点后减速，冻结人物位置但 GPS 继续；恢复位置不倒退、不重复事件，展示实际占比。剩余事件冲突或回调迟到超过 1 秒应中断。页面平滑结束、通知立即结束、运行中撤销模拟资格分别检查。进程杀死后下次打开显示中断，不自动续跑。

恢复提示自动检查 `interruptedSessionReminderRemainsVisible` 使用持久运行标记模拟上次进程中断，验证准备页“上次会话”记录在新的鉴权消息之后仍可见，且没有自动启动会话；该检查不宣称强杀进程后必然执行服务销毁回调。

## 自动设备读取器

先手工完成授权，安装应用和设备测试 APK。模拟器可使用 ADB 设置测试授权；真实手机验收仍须记录用户开发者选项选择过程。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.fakerun.app.DeviceAcceptanceTest com.fakerun.app.test/androidx.test.runner.AndroidJUnitRunner
```

默认跳过耗时完整真实时钟场景。单独启用它：先验证真实 AK 鉴权，在百度地图 GCJ-02 模式下实际点击绘制约 1 km 方形，再通过界面生成预览并开始。seed 由界面生成并输出到简洁验证结果；实际 5 Hz，不加速时钟。

```powershell
adb shell am instrument -w -e runFull true -e class com.fakerun.app.DeviceAcceptanceTest#fullTwoLoopBackgroundSessionWithIndependentReader com.fakerun.app.test/androidx.test.runner.AndroidJUnitRunner
```

读取器验证输出与 SessionState 计划位置一致，检查时间、字段、mock、两种 provider 同时刻字段一致、驻留时长和最终统计。地图绘制截图保存到设备应用外部私有目录 `test-baidu-route.png`，界面重建截图为 `test-route-ui.png`，只在本地检查，不提交原始采集文件。

缺少 AK 时可在完整场景命令中加 `-e useMapRoute false`，改用固定 seed `123456` 的合成路线。此结果只能证明模拟引擎和系统接口，不能代替百度地图实际绘制/覆盖物与手机兼容性验收。定位原始日志仅留在本地忽略的输出目录。
