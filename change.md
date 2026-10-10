# 本次任务交接

日期：2026-10-10。当前交付：FakeRun 1.0.1 / versionCode 2。

## 当前任务及范围

用户要求参考 ZCShou/GoGoGo 继续构建应用，明确 ADB 已断开、由用户安装测试；随后追加要求参考其高度、卫星数量与弱 GPS 内容模拟真实观测。本轮已完成源码修改、必要算法/构建检查和可安装签名 APK 交付。步道乐跑是否采用位置、弱 GPS 提示是否改善尚未实测，不能宣称兼容问题已修复或新版设备完整验收完成。

## 完成内容与修改文件

- `app/src/main/java/com/fakerun/app/location/MockLocationController.kt`：覆盖前保留设备原 gps/network 来源、功耗和精度类型，声明高度/速度/方向能力；来源缺失、属性未知时明确报错。输出模拟 WGS84 椭球高度、垂直精度，GPS 添加 satellites 字段，保持 mock 标记与两种 provider 同步输出及自有资源清理。
- `app/src/main/java/com/fakerun/app/model/Models.kt`、`simulation/SimulationEngine.kt`：新增高度基准默认 55 米、卫星数默认 7；高度 ±1.5 米连续观测误差与独立随机流；少于 4 颗使用 20～40 米弱信号精度档位。默认水平精度仍为 4～8 米，正常垂直精度 3～6 米。高度不改变水平路径、圈数或速度。
- `app/src/main/java/com/fakerun/app/ui/MainActivity.kt`：新增参数编辑、预览和实时高度/卫星/精度显示。
- `app/src/main/java/com/fakerun/app/data/RouteRepository.kt`：保存新增参数，旧 JSON 缺字段迁移到默认值；非法新字段明确拒绝，卫星小数不被截断。
- `app/build.gradle.kts`：版本 1.0.1 / code 2，沿用本机调试签名和本地 AK。
- `tests/unit/com/fakerun/app/AlgorithmTest.kt`：新增高度/信号观测连续性、重现性、范围和行进不受影响检查。
- `tests/device/com/fakerun/app/DeviceAcceptanceTest.kt`：新增 provider 属性/输出/恢复检查，更新高度与卫星断言、JSON 保存迁移检查；仅编译，未运行。
- `plan.md`、`README.md`、`docs/third-party.md`：按用户追加要求同步行为与边界，登记 GoGoGo 固定参考提交。
- `tests/manual/gogogo-reference.md`、`tests/manual/acceptance.md`、`tests/manual/location-compatibility.md`：记录新版构建证据和无 ADB 测试步骤，明确旧版真机结论的版本范围。
- `change.md`：本次最终交付与未验证事项。

源码与测试提交：`5dadd15`（参考定位来源属性并加入模拟高度、卫星数与弱信号档位）。没有修改第三方 APK、用户路线、AK 或签名私钥，没有推送。

## 验证证据

最终命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain`。结果 BUILD SUCCESSFUL，52 秒；9 项算法测试实际执行，0 失败、0 错误、0 跳过；普通 APK 与设备测试 APK 构建通过。Lint 0 错误、61 警告。用户追加要求及 JSON 小数校验修正使此前中间版本结论失效，因此重跑受影响检查。

交付：`app/build/outputs/apk/debug/FakeRun-1.0.1-debug.apk`，80,575,776 字节；与标准 `app-debug.apk` 相同。SHA-256：`D163D638D6EC4353DDD11458F88A606C1A0421DEAE3C0161473F582731140BD7`。

`apksigner verify --verbose --print-certs` 通过，v2 签名；SHA-1 与旧版相同：`24:7D:FB:FE:FD:58:DD:5D:3F:5F:CF:7C:DC:11:8E:AA:A0:FA:A8:6F`。`aapt dump badging` 确认 `com.fakerun.app`、1.0.1 / code 2、min 26 / target 36，以及四种 ABI。已有本地 AK 参与构建，未暴露或提交密钥。

文本与差异检查通过，文本为 UTF-8 无 BOM、LF。APK、构建输出、密钥和原始设备日志不进入 Git。Gradle 测试源集仅映射 `tests/`，生产代码与普通 APK 不依赖测试目录；未实际删除测试。

## 未完成事项、明确阻碍及所需外部条件

本轮用户明确断开 ADB 并要求自行测试；因此新版未安装到手机，provider 动态属性行为、系统输出、高度/卫星字段被第三方采用情况、旧 JSON 实际迁移、新增界面显示、地图鉴权、后台/息屏、暂停恢复和完整两圈设备场景均未实测。设备测试 APK 构建成功不等于测试通过。

卫星数是 GPS Location 的附加字段，公开 test provider 不会生成 GNSS 卫星状态/NMEA/原始测量回调。模拟高度为平坦路线观测模型，不是测量地形。本轮没有 Root、Hook、清除 mock 标记或替换真实传感器回调。目标若依据未模拟接口或 SDK 过滤判断弱信号，仍可能显示原提示。

外部条件：用户安装本次 APK，在同一手机、同一目标准备页反馈采用位置与错误文案，以及正常后台/结束恢复情况。若仍不能采用且无法获得有用错误信息，仍需目标可调试版本、相关定位配置源码或官方诊断区分其最终 SDK 分支。1.0.0 的真机系统输出证据不可作为新版验收结果。

## 下一执行者的准确下一步

1. 阅读 `tests/manual/gogogo-reference.md`，先确认用户安装版本为 1.0.1 / code 2，对应上述 APK 哈希；先用默认 55 米、7 颗卫星和 network 开启复测准备页。
2. 根据用户反馈核对是否有接入失败或仍报 GPS 信号弱。若重新提供设备连接，优先运行 `DeviceAcceptanceTest#nativeProviderCharacteristicsAndSynchronizedOutput` 及受影响保存/服务检查，再按 `tests/manual/acceptance.md` 完成新版两圈、两次停留、20% 步行与后台/息屏场景；不要反复重跑旧版本对照。
3. 若仍拒绝位置，取得 SDK 的定位类型、错误码、mock 与最终坐标诊断后再定位根因。不能因高度或 satellites 字段存在就宣称已解决目标兼容性，不能把未运行项目标为通过。
