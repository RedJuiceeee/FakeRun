# 本次任务交接

日期：2026-10-10。

## 当前任务及范围

用户要求排查并修复 FakeRun 模拟位置能被高德地图采用、步道乐跑却显示真实位置的问题，随后提供 Honor Magic 8 Pro / Android 17 并连接 ADB 继续检查。

已完成静态分析和该真机准备页对照：当前系统注入正常、真实前台服务持续运行，目标准备页仍不采用模拟位置。具体 SDK 分支未确认，兼容修复未完成。未修改生产代码或第三方 APK，未生成新版普通 APK。

此前首版实现和验收记录见 `tests/manual/verification.md`；完整旧交付说明可通过 Git 提交 `19f5b13` 查阅。第一轮静态排查见提交 `836cc2d`，其“未连接手机”阻碍已解除，以下为最新状态。

## 完成内容与修改文件

- `tests/device/com/fakerun/app/ProviderCompatibilityTest.kt`：新增显式启用的跨应用对照窗口，使用合成路线及真实前台服务，暂停后监听 gps/network/passive 新样本，检查服务持续运行，并清理、恢复会话提示。测试不保存合成路线。
- `tests/manual/location-compatibility.md`：更新真机版本、权限、对照结果、工具修订原因、退出恢复证据、已排除项与仍未确认项。
- `README.md`：更新兼容问题的真实进度与记录链接。
- `change.md`：更新本次任务状态和下一步。

生产源码、用户路线、目标 APK、应用权限、AK 和本机配置未修改。设备日志、截图数据和构建输出仅存忽略目录，不提交。

## 验证证据

- 手机报告型号 `BKQ-AN10`、Android 17 / API 37；FakeRun 1.0.0 / code 1，步道乐跑 4.1.7 / code 217。精确定位授权、系统定位和 FakeRun mock 资格正常。
- 目标在准备页登记 gps/network/passive 请求，未观察到 fused 请求。保持注入、重新启动目标进程并重新进入准备页后，仍未采用合成位置，显示“GPS信号弱”。计时、距离和打卡数为零，没有启动计时或提交运动记录。
- 最终构建命令为 `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`，构建成功。设备测试重新编译；普通 APK 与算法为 UP-TO-DATE，沿用未改源码版本的 8 项算法通过结果，不声称重新执行了算法测试。
- 第一次工具直接在后台测试线程注入，频率断言失败；改用生产前台服务并允许客户端合并回调后重建、重测。工具改动没有降低生产输出频率。
- 最终设备命令：`adb shell am instrument -w -e class com.fakerun.app.ProviderCompatibilityTest#stationaryMockWindowForExternalReader -e checkTarget true -e holdSeconds 120 com.fakerun.app.test/androidx.test.runner.AndroidJUnitRunner`。结果 `OK (1 test)`，134.46 秒。120 秒固定窗口中 gps/network 各 563 条、passive 1126 条新样本，坐标与 mock 标记不匹配均为 0，服务一直 active。
- 退出后系统 gps/network identity 恢复，无本应用 mock override、SimulationService 或活跃会话唤醒锁。测试 APK 已卸载，临时设备 UI 文件已删除，USB 供电亮屏设置恢复为原值 0；用户会话提示恢复。
- 执行文本编码检查和差异检查；原始设备信息未提交。完整方法与局限见排查记录。

## 未完成事项、明确阻碍及所需外部条件

尚未获得目标最终定位回调的定位类型、错误码、mock 标记与坐标，不能确认它调用了 `setMockEnable(false)`，也不能在 SDK 独立网络位置、持久缓存、过滤或设备对特定目标的处理之间作最终归因。

明确阻碍：提供 APK 的业务代码经过加固，目标日志没有暴露上述诊断信息，普通 ADB 不足以检查其内部定位选项。公开系统 test provider 能输出 mock 位置，无法要求第三方采用；没有可证明的 FakeRun 注入缺陷可供修复。`plan.md` 排除 Root、Hook、mock 隐藏、第三方检测绕过及传感器伪造，本轮没有擅自扩大范围。

需要外部条件：目标应用提供的可调试测试版本、相关定位配置源码或官方定位诊断。无需重新索要手机连接、账户密码或用户运动记录。

## 下一执行者的准确下一步

先阅读 `tests/manual/location-compatibility.md`。不要重复已经通过的同设备同源码系统对照，也不要无证据增加 fused 或伪造卫星信息。

取得目标诊断后，核对最终 SDK 回调和系统新样本：若确认系统接口注入缺陷，修正对应边界、运行必要算法/构建及目标真机验证；若确认目标主动拒绝 mock 或选择独立 SDK 位置，明确报告现有系统模拟方案的兼容限制。只有目标实际采用模拟位置并完成相关验收，才能将原修复任务标为完成。
